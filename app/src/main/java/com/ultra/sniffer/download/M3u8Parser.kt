package com.ultra.sniffer.download

/** One rendition entry of an HLS master playlist. */
data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val resolution: String?,
    val audioGroup: String?
)

/** One audio rendition of an HLS master playlist. */
data class HlsAudioRendition(
    val url: String,
    val groupId: String,
    val name: String,
    val isDefault: Boolean
)

/** A parsed HLS media playlist. */
data class HlsMediaPlaylist(
    val segmentUrls: List<String>,
    val initUrl: String?,
    /** #EXTINF duration of every segment (seconds); 0.0 when the tag was missing. */
    val durations: List<Double> = emptyList()
)

/** Minimal RFC 8216 (m3u8) parser: master + media playlists. */
object M3u8Parser {

    fun isMaster(text: String): Boolean =
        text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-MEDIA:TYPE=AUDIO")

    fun parseMaster(text: String, baseUrl: String): List<HlsVariant> {
        val lines = text.lines().map { it.trim() }
        val variants = mutableListOf<HlsVariant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = parseAttrs(line.substringAfter(':'))
                val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull()
                    ?: attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    variants.add(
                        HlsVariant(
                            url = resolve(baseUrl, lines[j]),
                            bandwidth = bandwidth,
                            resolution = attrs["RESOLUTION"],
                            audioGroup = attrs["AUDIO"]
                        )
                    )
                    i = j
                }
            }
            i++
        }
        return variants
    }

    fun parseAudioRenditions(text: String, baseUrl: String): List<HlsAudioRendition> {
        val result = mutableListOf<HlsAudioRendition>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:")) continue
            val attrs = parseAttrs(line.substringAfter(':'))
            if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) continue
            val uri = attrs["URI"] ?: continue
            result.add(
                HlsAudioRendition(
                    url = resolve(baseUrl, uri),
                    groupId = attrs["GROUP-ID"] ?: "",
                    name = attrs["NAME"] ?: "audio",
                    isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true)
                )
            )
        }
        return result
    }

    fun parseMedia(text: String, baseUrl: String): HlsMediaPlaylist {
        var initUrl: String? = null
        val segments = mutableListOf<String>()
        val durations = mutableListOf<Double>()
        var pendingDuration = 0.0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    parseAttrs(line.substringAfter(':'))["URI"]?.let { initUrl = resolve(baseUrl, it) }
                }
                // #EXTINF:9.009,
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#") -> Unit // other tags (EXT-X-ENDLIST, EXT-X-KEY, ...)
                else -> {
                    segments.add(resolve(baseUrl, line))
                    durations.add(pendingDuration)
                    pendingDuration = 0.0
                }
            }
        }
        return HlsMediaPlaylist(segments, initUrl, durations)
    }

    /** Resolve a possibly relative URI against the playlist URL. */
    fun resolve(baseUrl: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("data:")) return ref
        return runCatching { java.net.URI(baseUrl).resolve(ref).toString() }.getOrDefault(ref)
    }

    private fun parseAttrs(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().uppercase()
            var j = eq + 1
            val value: String
            if (j < s.length && s[j] == '"') {
                val end = s.indexOf('"', j + 1)
                if (end < 0) break
                value = s.substring(j + 1, end)
                j = end + 1
            } else {
                var end = s.indexOf(',', j)
                if (end < 0) end = s.length
                value = s.substring(j, end).trim()
                j = end
            }
            out[key] = value
            val comma = s.indexOf(',', j)
            if (comma < 0) break
            i = comma + 1
        }
        return out
    }
}
