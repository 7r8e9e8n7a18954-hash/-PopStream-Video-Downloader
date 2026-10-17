package com.ultra.sniffer.vpn

import com.ultra.sniffer.core.MediaKind
import com.ultra.sniffer.core.MediaSource
import com.ultra.sniffer.core.SniffedMedia
import com.ultra.sniffer.download.PageMediaExtractor
import com.ultra.sniffer.util.MediaUrlUtils
import java.nio.charset.StandardCharsets

/**
 * Deep inspection of intercepted flows:
 *
 *  - plaintext HTTP (port 80/8080/...): parses request line + Host header and
 *    response Content-Type. This yields full, directly downloadable URLs.
 *  - TLS (port 443/8443): parses the ClientHello SNI field. HTTPS payloads are
 *    encrypted, so only the host name is visible — these become "host hints".
 *
 * Called from relay threads: keep it cheap, synchronized and bounded.
 */
class TrafficSniffer(
    private val emit: (SniffedMedia) -> Unit,
    private val sniLogging: () -> Boolean
) {

    private companion object {
        const val MAX_HTTP_BUFFER = 32 * 1024
        const val MAX_TLS_BUFFER = 8 * 1024
        const val MAX_FLOWS = 1024

        val HTTP_PORTS = setOf(80, 8080, 8000, 8001, 8081, 8090, 8888, 3128, 5000, 9000)
        val TLS_PORTS = setOf(443, 8443, 9443, 8883)

        val VIDEO_HOST_SUFFIXES = listOf(
            "googlevideo.com", "youtube.com", "ytimg.com", "youtu.be",
            "fbcdn.net", "facebook.com", "instagram.com", "cdninstagram.com",
            "tiktokcdn.com", "tiktokv.com", "byteoversea.com", "muscdn.com",
            "twimg.com", "video.twimg.com", "twitter.com", "x.com",
            "dailymotion.com", "dmcdn.net", "vimeo.com", "vimeocdn.com",
            "akamaized.net", "cloudfront.net", "streamable.com", "bitchute.com",
            "rumble.com", "reddit.com", "redd.it", "redditmedia.com", "pinimg.com",
            "snapchat.com", "spotify.com", "scdn.co", "soundcloud.com", "sndcdn.com",
            "jiocinema.com", "hotstar.com", "disneyplus.com", "sonyliv.com",
            "zee5.com", "mxplayer.in", "sharechat.com", "moj.com",
            "jiosaavn.com", "saavncdn.com", "gaana.com", "wynk.in"
        )
    }

    private class HttpFlow {
        val request = ByteArray(MAX_HTTP_BUFFER)
        var requestLen = 0
        var requestParsed = false
        var host: String? = null
        var path: String? = null
        val response = ByteArray(8 * 1024)
        var responseLen = 0
        var responseParsed = false
    }

    private class TlsFlow {
        val hello = ByteArray(MAX_TLS_BUFFER)
        var len = 0
        var parsed = false
    }

    /** URLs that a playlist/manifest referenced: they are children, not videos. */
    private val playlistChildren = HashSet<String>()

    private val httpFlows = HashMap<FlowKey, HttpFlow>()
    private val httpRequests = HashMap<FlowKey, HttpFlow>()
    private val tlsFlows = HashMap<FlowKey, TlsFlow>()

    /** Chunk files are internal parts of a stream — never useful as list entries. */
    private fun isChunkUrl(url: String): Boolean =
        MediaUrlUtils.extOf(url) in setOf("ts", "m4s", "m2ts", "aac")

    private fun isPlaylistChild(url: String): Boolean = playlistChildren.contains(url)

    /**
     * Reads a downloaded playlist/manifest body and remembers every URL it
     * references, so ONE video shows as ONE list entry (the master manifest)
     * instead of master + variants + audio + chunk noise.
     */
    private fun rememberPlaylistChildren(baseUrl: String, body: String, contentType: String) {
        val children = ArrayList<String>(32)
        if (contentType.contains("mpegurl", true)) {
            for (line in body.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty()) continue
                if (t.startsWith("#")) {
                    if (t.startsWith("#EXT-X-MAP", true)) {
                        t.substringAfter("URI=\"", "").substringBefore('"').takeIf { it.isNotBlank() }
                            ?.let { children.add(PageMediaExtractor.absolutize(baseUrl, it)) }
                    }
                    continue
                }
                children.add(PageMediaExtractor.absolutize(baseUrl, t))
            }
        } else {
            Regex("(?:media|initialization|sourceURL|href)\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .findAll(body).forEach { children.add(PageMediaExtractor.absolutize(baseUrl, it.groupValues[1])) }
            Regex("<BaseURL[^>]*>([^<]+)</BaseURL>", RegexOption.IGNORE_CASE)
                .findAll(body).forEach { children.add(PageMediaExtractor.absolutize(baseUrl, it.groupValues[1].trim())) }
        }
        synchronized(playlistChildren) {
            if (playlistChildren.size > 8192) playlistChildren.clear()
            playlistChildren.addAll(children)
        }
    }

    // ------------------------------------------------------------------
    // Entry points (called by the relay engines)
    // ------------------------------------------------------------------

    @Synchronized
    fun clientData(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val key = FlowKey(srcIp, srcPort, dstIp, dstPort)
        when {
            // Known HTTP port, or the payload really starts with an HTTP method
            dstPort in HTTP_PORTS || looksLikeHttpRequest(data, off, len) ->
                httpRequest(key, data, off, len)
            // Known TLS port, or a TLS record header (0x16 0x03 …)
            dstPort in TLS_PORTS || looksLikeTls(data, off, len) ->
                tlsClientHello(key, data, off, len)
        }
    }

    @Synchronized
    fun serverData(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        // response travels server -> client; match against the stored request
        val reqKey = FlowKey(srcIp = dstIp, srcPort = dstPort, dstIp = srcIp, dstPort = srcPort)
        if (reqKey in httpRequests || srcPort in HTTP_PORTS) httpResponse(reqKey, data, off, len)
    }

    /** Cheap probe: does this payload start with "GET " / "POST" / … ? */
    private fun looksLikeHttpRequest(data: ByteArray, off: Int, len: Int): Boolean {
        if (len < 4) return false
        fun at(i: Int) = (data[off + i].toInt() and 0xFF).toChar()
        val c0 = at(0)
        if (c0 != 'G' && c0 != 'P' && c0 != 'H' && c0 != 'D' && c0 != 'O' && c0 != 'C') return false
        val head = buildString(minOf(len, 8)) {
            for (i in 0 until minOf(len, 8)) append(at(i))
        }
        return head.startsWith("GET ") || head.startsWith("POST ") || head.startsWith("HEAD ") ||
            head.startsWith("PUT ") || head.startsWith("OPTIONS ") || head.startsWith("DELETE ") ||
            head.startsWith("CONNECT ")
    }

    /** TLS record: content type 0x16 (handshake) + version 0x03 0x0x */
    private fun looksLikeTls(data: ByteArray, off: Int, len: Int): Boolean {
        if (len < 3) return false
        return (data[off].toInt() and 0xFF) == 0x16 && (data[off + 1].toInt() and 0xFF) == 0x03
    }

    // ------------------------------------------------------------------
    // HTTP request / response
    // ------------------------------------------------------------------

    private fun httpRequest(key: FlowKey, data: ByteArray, off: Int, len: Int) {
        if (httpFlows.size > MAX_FLOWS) httpFlows.clear()
        val flow = httpFlows.getOrPut(key) { HttpFlow() }
        if (flow.requestParsed) return
        val copy = minOf(len, flow.request.size - flow.requestLen)
        if (copy <= 0) { flow.requestParsed = true; return }
        System.arraycopy(data, off, flow.request, flow.requestLen, copy)
        flow.requestLen += copy

        val text = String(flow.request, 0, flow.requestLen, StandardCharsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n")
        val firstLineEnd = text.indexOf("\r\n")
        if (firstLineEnd < 0) return   // wait for more data

        val firstLine = text.substring(0, firstLineEnd)
        val parts = firstLine.split(" ")
        if (parts.size < 3) { flow.requestParsed = true; return }
        var path = parts[1]
        var host: String? = null

        if (headerEnd >= 0) {
            val headers = text.substring(firstLineEnd + 2, headerEnd)
            headerer@ for (line in headers.split("\r\n")) {
                if (line.startsWith("Host:", ignoreCase = true)) {
                    host = line.substringAfter(':').trim()
                    break@headerer
                }
            }
        } else {
            // we have the request line; host header should be right after
            val rest = text.substring(firstLineEnd + 2)
            for (line in rest.split("\r\n")) {
                if (line.startsWith("Host:", ignoreCase = true)) {
                    host = line.substringAfter(':').trim()
                    break
                }
            }
        }
        if (host.isNullOrBlank()) return

        flow.requestParsed = true
        flow.host = host

        // absolute-form request target (GET http://host/path HTTP/1.1)
        if (path.startsWith("http://") || path.startsWith("https://")) {
            flow.path = path.substringAfter("://").substringAfter('/', "")
            flow.path = "/" + flow.path
            flow.host = MediaUrlUtils.hostOf(path)
        } else {
            flow.path = path
        }

        val fullHost = flow.host!!
        val url = "http://$fullHost${flow.path}"
        httpRequests[key] = flow

        if (MediaUrlUtils.isLikelyMedia(url) && !isChunkUrl(url) && !isPlaylistChild(url)) {
            emit(SniffedMedia.fromUrl(url, MediaSource.HTTP))
        }
    }

    private fun httpResponse(reqKey: FlowKey, data: ByteArray, off: Int, len: Int) {
        val flow = httpRequests[reqKey] ?: return
        if (flow.responseParsed) return
        val copy = minOf(len, flow.response.size - flow.responseLen)
        if (copy <= 0) { flow.responseParsed = true; return }
        System.arraycopy(data, off, flow.response, flow.responseLen, copy)
        flow.responseLen += copy

        val text = String(flow.response, 0, flow.responseLen, StandardCharsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd < 0) {
            if (flow.responseLen >= flow.response.size) flow.responseParsed = true
            return
        }
        flow.responseParsed = true
        val headers = text.substring(0, headerEnd)

        var contentType: String? = null
        for (line in headers.split("\r\n")) {
            if (line.startsWith("Content-Type:", ignoreCase = true)) {
                contentType = line.substringAfter(':').trim()
            }
        }
        val host = flow.host ?: return
        val path = flow.path ?: return
        val url = "http://$host$path"

        val kind: MediaKind? = MediaUrlUtils.kindFromContentType(contentType)

        // Learn the children of every playlist/manifest we see.
        if (kind == MediaKind.HLS || kind == MediaKind.DASH) {
            val bodyStart = headerEnd + 4
            if (bodyStart < text.length) {
                rememberPlaylistChildren(url, text.substring(bodyStart), contentType.orEmpty())
            }
        }

        if (kind != null && !isChunkUrl(url) && !isPlaylistChild(url)) {
            emit(
                SniffedMedia(
                    url = url,
                    host = MediaUrlUtils.hostOf(url),
                    kind = kind,
                    source = MediaSource.HTTP,
                    label = MediaUrlUtils.guessDisplayName(url, MediaUrlUtils.hostOf(url))
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // TLS ClientHello SNI
    // ------------------------------------------------------------------

    private fun tlsClientHello(key: FlowKey, data: ByteArray, off: Int, len: Int) {
        if (tlsFlows.size > MAX_FLOWS) tlsFlows.clear()
        val flow = tlsFlows.getOrPut(key) { TlsFlow() }
        if (flow.parsed) return
        val copy = minOf(len, flow.hello.size - flow.len)
        if (copy > 0) {
            System.arraycopy(data, off, flow.hello, flow.len, copy)
            flow.len += copy
        }
        val sni = parseSni(flow.hello, flow.len) ?: run {
            if (flow.len >= flow.hello.size) flow.parsed = true
            return
        }
        flow.parsed = true
        if (isVideoHost(sni) && sniLogging()) {
            emit(SniffedMedia.hostHint(sni))
        }
    }

    /** Bounds-checked TLS ClientHello SNI parser. Returns null when incomplete/invalid. */
    private fun parseSni(b: ByteArray, len: Int): String? {
        if (len < 5 + 4) return null
        if ((b[0].toInt() and 0xFF) != 0x16) return null      // TLS handshake record
        val recordLen = ((b[3].toInt() and 0xFF) shl 8) or (b[4].toInt() and 0xFF)
        val recordEnd = minOf(len, 5 + recordLen)
        if (5 + recordLen > len) return null                  // wait for full record
        if (recordEnd < 9) return null
        if ((b[5].toInt() and 0xFF) != 0x01) return null      // ClientHello

        var p = 5 + 4 + 2 + 32                                // handshake hdr + version + random
        if (p >= recordEnd) return null
        val sessionIdLen = b[p].toInt() and 0xFF
        p += 1 + sessionIdLen
        if (p + 2 > recordEnd) return null
        val cipherLen = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        p += 2 + cipherLen
        if (p + 1 > recordEnd) return null
        val compLen = b[p].toInt() and 0xFF
        p += 1 + compLen
        if (p + 2 > recordEnd) return null
        val extLen = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        p += 2
        val extEnd = minOf(recordEnd, p + extLen)

        while (p + 4 <= extEnd) {
            val extType = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
            val eLen = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            p += 4
            if (p + eLen > extEnd) return null
            if (extType == 0x0000) { // server_name
                var q = p
                if (q + 2 > p + eLen) return null
                q += 2 // server_name_list length
                while (q + 3 <= p + eLen) {
                    val nameType = b[q].toInt() and 0xFF
                    val nameLen = ((b[q + 1].toInt() and 0xFF) shl 8) or (b[q + 2].toInt() and 0xFF)
                    q += 3
                    if (q + nameLen > p + eLen) return null
                    if (nameType == 0) {
                        return String(b, q, nameLen, StandardCharsets.US_ASCII)
                    }
                    q += nameLen
                }
                return null
            }
            p += eLen
        }
        return null
    }

    private fun isVideoHost(host: String): Boolean {
        val h = host.lowercase()
        return VIDEO_HOST_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }
}
