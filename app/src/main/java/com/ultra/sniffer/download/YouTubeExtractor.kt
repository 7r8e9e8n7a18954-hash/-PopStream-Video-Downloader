package com.ultra.sniffer.download

import com.ultra.sniffer.core.MediaKind
import com.ultra.sniffer.util.Net
import org.json.JSONArray
import org.json.JSONObject

/**
 * Best-effort YouTube stream extractor.
 *
 * Talks directly to YouTube's InnerTube `player` endpoint with several client
 * profiles (Android VR → iOS → Android → Web) and collects every format whose
 * URL is returned in plain form. When YouTube only returns a ciphered URL
 * (`signatureCipher`) for every format — which happens for some videos — the
 * extraction fails with a friendly message and the user is pointed to the
 * in-app browser, where the running player reveals its real URLs.
 *
 * Note: this talks to YouTube exactly like its own apps do. URLs are
 * time-limited and bound to the caller's IP, so they must be downloaded
 * immediately on this device (which is what we do).
 */
object YouTubeExtractor {

    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val REFERER = "https://www.youtube.com/"

    /** One entry of `streamingData.formats` / `adaptiveFormats`, URL already usable. */
    data class YtFormat(
        val itag: Int,
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val height: Int,
        val contentLength: Long,
        val audioQuality: String = "",
        /** true for `formats` (video+audio muxed together), false for `adaptiveFormats`. */
        val muxed: Boolean = false
    )

    data class Extraction(
        val title: String,
        val streams: List<ResolvedStream>,
        val error: String = "",
        val durationSec: Int = 0
    ) {
        val ok: Boolean get() = streams.isNotEmpty()
    }

    private data class Client(
        val name: String,
        val version: String,
        val userAgent: String,
        val osName: String,
        val osVersion: String,
        val sdk: Int,
        val deviceModel: String = ""
    )

    private val CLIENTS = listOf(
        Client(
            "ANDROID_VR", "1.60.19",
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip",
            "Android", "12", 32, "Quest 3"
        ),
        Client(
            "IOS", "19.45.4",
            "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X)",
            "iPhone", "18.1.0.22B83", 0, "iPhone16,2"
        ),
        Client(
            "ANDROID", "19.45.38",
            "com.google.android.youtube/19.45.38 (Linux; U; Android 14; GB) gzip",
            "Android", "14", 34, "SM-S918B"
        ),
        Client("WEB", "2.20241010.00.00", Net.USER_AGENT, "Windows", "10.0", 0)
    )

    // ------------------------------------------------------------------
    // URL matching
    // ------------------------------------------------------------------

    /** Video id of any YouTube / youtu.be / shorts / embed link, else null. */
    fun videoIdOf(url: String): String? {
        val u = url.trim()
        if (!u.contains("youtube.com", true) && !u.contains("youtu.be", true)) return null
        Regex("[?&]v=([A-Za-z0-9_\\-]{11})").find(u)?.let { return it.groupValues[1] }
        Regex("youtu\\.be/([A-Za-z0-9_\\-]{11})").find(u)?.let { return it.groupValues[1] }
        Regex("/(?:embed|shorts|live|v)/([A-Za-z0-9_\\-]{11})").find(u)?.let { return it.groupValues[1] }
        return null
    }

    // ------------------------------------------------------------------
    // Extraction
    // ------------------------------------------------------------------

    /**
     * Tries every client profile until one returns usable formats.
     * @throws IllegalStateException with a user-readable (Hinglish) reason.
     */
    suspend fun extract(videoId: String): Extraction {
        var last: Extraction? = null
        var networkError = ""
        for (client in CLIENTS) {
            try {
                val json = Net.postJson(
                    PLAYER_URL,
                    buildPlayerBody(videoId, client),
                    mapOf(
                        "User-Agent" to client.userAgent,
                        "Referer" to REFERER,
                        "Origin" to "https://www.youtube.com",
                        "X-Youtube-Client-Name" to clientNameId(client.name).toString(),
                        "X-Youtube-Client-Version" to client.version
                    )
                )
                val parsed = parsePlayer(json, client)
                if (parsed.ok) return parsed
                last = parsed
            } catch (t: Throwable) {
                networkError = t.message ?: t.javaClass.simpleName
            }
        }
        throw IllegalStateException(
            last?.error?.takeIf { it.isNotBlank() }
                ?: "YouTube ने stream URL नहीं दिया${if (networkError.isBlank()) "" else " ($networkError)"}"
        )
    }

    private fun clientNameId(name: String): Int = when (name) {
        "ANDROID_VR" -> 28
        "IOS" -> 5
        "ANDROID" -> 3
        else -> 1
    }

    private fun buildPlayerBody(videoId: String, client: Client): String {
        val clientObj = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", "hi")
            .put("gl", "IN")
            .put("timeZone", "Asia/Kolkata")
            .put("utcOffsetMinutes", 330)
            .put("osName", client.osName)
            .put("osVersion", client.osVersion)
        if (client.sdk > 0) clientObj.put("androidSdkVersion", client.sdk)
        if (client.deviceModel.isNotBlank()) {
            clientObj.put("deviceModel", client.deviceModel)
            if (client.name.startsWith("ANDROID")) clientObj.put("deviceMake", "Google")
        }
        val context = JSONObject()
            .put("client", clientObj)
            .put("user", JSONObject().put("lockedSafetyMode", false))
            .put("request", JSONObject().put("useSsl", true))
        return JSONObject()
            .put("context", context)
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private fun parsePlayer(json: String, client: Client): Extraction {
        val root = JSONObject(json)
        val status = root.optJSONObject("playabilityStatus")
        val code = status?.optString("status").orEmpty()
        val reason = status?.optString("reason").orEmpty()
        val details = root.optJSONObject("videoDetails")
        val title = details?.optString("title").orEmpty()
        val duration = details?.optString("lengthSeconds")?.toIntOrNull() ?: 0

        val streaming = root.optJSONObject("streamingData")
        val formats = readFormats(streaming?.optJSONArray("formats"), muxed = true) +
            readFormats(streaming?.optJSONArray("adaptiveFormats"), muxed = false)

        val streams = buildStreams(formats, duration, client.userAgent)
        if (streams.isNotEmpty()) return Extraction(title, streams, "", duration)

        val message = when (code) {
            "LOGIN_REQUIRED" ->
                "YouTube ने इस video पर sign-in / bot-check लगाया है। Video को in-app browser में चलाएँ — वहाँ से सीधा link मिल जाता है।"
            "LIVE_STREAM_OFFLINE" -> "Live stream अभी offline है।"
            "UNPLAYABLE", "ERROR" -> reason.ifBlank { "यह video उपलब्ध नहीं है या region-locked है।" }
            "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED" -> "Age-restricted video — YouTube बिना sign-in नहीं देता।"
            else -> reason.ifBlank {
                "YouTube ने सीधा stream URL देने से मना किया (extra protection)। In-app browser से video चलाकर try करें।"
            }
        }
        return Extraction(title, emptyList(), message, duration)
    }

    private fun readFormats(array: JSONArray?, muxed: Boolean): List<YtFormat> {
        if (array == null) return emptyList()
        val list = ArrayList<YtFormat>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue // ciphered format — needs JS deciphering, skip
            list.add(
                YtFormat(
                    itag = o.optInt("itag"),
                    url = url,
                    mimeType = o.optString("mimeType"),
                    bitrate = o.optInt("bitrate"),
                    height = o.optInt("height"),
                    contentLength = o.optString("contentLength").toLongOrNull() ?: -1L,
                    audioQuality = o.optString("audioQuality"),
                    muxed = muxed
                )
            )
        }
        return list
    }

    /**
     * Turns raw formats into user-facing download options (pure, unit-tested):
     *  - one merge option per video height, paired with the best audio track
     *  - plus the ready-made muxed mp4 files as a lighter alternative
     *  - audio-only only when no video format survived
     */
    internal fun buildStreams(
        formats: List<YtFormat>,
        durationSec: Int = 0,
        userAgent: String = Net.USER_AGENT
    ): List<ResolvedStream> {
        val usable = formats.filter { it.url.isNotBlank() }
        val headers = mapOf("Referer" to REFERER, "User-Agent" to userAgent)

        val audioOnly = usable.filter { it.mimeType.startsWith("audio/") }
        val bestAudio = audioOnly.filter { it.mimeType.contains("mp4a") || it.mimeType.contains("audio/mp4") }
            .maxByOrNull { it.bitrate }
            ?: audioOnly.filterNot { it.mimeType.contains("opus", true) }.maxByOrNull { it.bitrate }
            ?: audioOnly.maxByOrNull { it.bitrate }

        val result = ArrayList<ResolvedStream>()

        // 1) adaptive video-only + best audio → muxed MP4 (best quality)
        val videoOnly = usable.filter { !it.muxed && it.mimeType.startsWith("video/") }
        for ((height, group) in videoOnly.groupBy { it.height }.toSortedMap(reverseOrder())) {
            if (height < 144) continue
            val best = group.filter { it.mimeType.contains("avc1") }.maxByOrNull { it.bitrate }
                ?: group.filter { it.mimeType.startsWith("video/mp4") }.maxByOrNull { it.bitrate }
                ?: group.filterNot { it.mimeType.contains("av01") }.maxByOrNull { it.bitrate }
                ?: group.maxByOrNull { it.bitrate }
                ?: continue
            val audioSize = bestAudio?.let { sizeOf(it, durationSec) } ?: -1L
            val size = listOf(sizeOf(best, durationSec), audioSize)
                .filter { it > 0 }.sum().takeIf { it > 0 } ?: -1L
            result.add(
                ResolvedStream(
                    url = best.url,
                    kind = MediaKind.MP4,
                    label = "${height}p • ${codecName(best.mimeType)}" +
                        (if (bestAudio != null) " + audio" else "") +
                        (ResolvedStream.sizeLabel(size).takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""),
                    audioUrl = bestAudio?.url,
                    quality = height,
                    sizeBytes = size,
                    headers = headers,
                    tag = "${height}p"
                )
            )
        }

        // 2) ready muxed files (usually 360p/720p, download in one piece)
        for (f in usable.filter { it.muxed && it.mimeType.startsWith("video/") }.sortedByDescending { it.height }) {
            if (f.height < 144) continue
            val size = sizeOf(f, durationSec)
            result.add(
                ResolvedStream(
                    url = f.url,
                    kind = MediaKind.MP4,
                    label = "${f.height}p • mp4 (single file)" +
                        (ResolvedStream.sizeLabel(size).takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""),
                    quality = f.height - 1, // just below the merge option of the same height
                    sizeBytes = size,
                    headers = headers,
                    tag = "${f.height}p"
                )
            )
        }

        // 3) music-only videos
        if (result.isEmpty() && bestAudio != null) {
            val size = sizeOf(bestAudio, durationSec)
            result.add(
                ResolvedStream(
                    url = bestAudio.url,
                    kind = MediaKind.AUDIO,
                    label = "Audio only • ${bestAudio.bitrate / 1000}kbps" +
                        (ResolvedStream.sizeLabel(size).takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""),
                    quality = bestAudio.bitrate / 1000,
                    sizeBytes = size,
                    headers = headers,
                    tag = "audio"
                )
            )
        }

        return result.sortedByDescending { it.quality }.take(10)
    }

    private fun sizeOf(f: YtFormat, durationSec: Int): Long {
        if (f.contentLength > 0) return f.contentLength
        if (durationSec > 0 && f.bitrate > 0) return f.bitrate.toLong() * durationSec / 8
        return -1L
    }

    private fun codecName(mimeType: String): String = when {
        mimeType.contains("avc1", true) -> "H.264"
        mimeType.contains("vp09", true) || mimeType.contains("vp9", true) -> "VP9"
        mimeType.contains("av01", true) -> "AV1"
        else -> "video"
    }
}
