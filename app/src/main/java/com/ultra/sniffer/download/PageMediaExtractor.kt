package com.ultra.sniffer.download

import com.ultra.sniffer.util.MediaUrlUtils

/**
 * Finds playable media URLs inside a web page — plain HTML, `<video>` tags,
 * `og:video` meta tags and URLs hidden inside embedded JSON/JS.
 *
 * Pure string logic on purpose: no Android classes, so every rule is covered
 * by JVM unit tests ([com.ultra.sniffer.download.ExtractorTest]).
 */
object PageMediaExtractor {

    /** One media URL found inside a page. */
    data class Found(
        val url: String,
        val label: String,
        val kindHint: String,   // "hls" | "dash" | "video" | "audio" | "chunk"
        val from: String        // where it was found: "html", "og:video", "json", …
    )

    private const val MAX_RESULTS = 80

    private val EXT = "(?:m3u8|mpd|mp4|m4v|webm|mkv|mov|flv|3gp|ts|m4s|mp3|m4a|aac|opus|ogg)"

    /** http(s) URL ending (or containing) a media extension. */
    private val DIRECT_URL = Regex(
        "https?://[^\\s\"'<>()\\[\\]{}\\\\,;|]{3,}?\\.$EXT(?:[?#][^\\s\"'<>()\\[\\]{}\\\\|]*)?",
        RegexOption.IGNORE_CASE
    )

    /** <meta property="og:video…" content="…"> in either attribute order. */
    private val META_TAG = Regex(
        "<meta[^>]+(?:property|name)\\s*=\\s*[\"'](og:video[^\"']*|twitter:player:stream|twitter:player|og:audio[^\"']*)[\"'][^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val META_CONTENT = Regex("content\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val META_PROP = Regex(
        "(?:property|name)\\s*=\\s*[\"'](og:video[^\"']*|twitter:player:stream|twitter:player|og:audio[^\"']*)[\"']",
        RegexOption.IGNORE_CASE
    )

    /** <video src>, <source src>, <embed src>, <iframe src>, data-src variants. */
    private val TAG_SRC = Regex(
        "<(?:video|source|audio|embed|iframe)[^>]+(?:src|data-src|data-video-src)\\s*=\\s*[\"']([^\"']+)[\"']",
        RegexOption.IGNORE_CASE
    )

    /** "hlsManifestUrl": "…" style keys inside embedded JSON/JS. */
    private val JSON_KEY = Regex(
        "\"(hlsManifestUrl|dashManifestUrl|hlsManifest|dashManifest|contentUrl|video_url|videoUrl|playbackUrl|manifestUrl|streamUrl|mediaUrl|fileUrl|file|source|src|url)\"\\s*:\\s*\"([^\"]{6,})\"",
        RegexOption.IGNORE_CASE
    )

    /** Keys that only count when the value really looks like a media file. */
    private val GENERIC_KEYS = setOf("file", "source", "src", "url")

    private val TITLE = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val YOUTUBE_ID = Regex(
        "(?:youtube\\.com/(?:watch\\?(?:[^\"'#\\s]*&)?v=|embed/|shorts/|live/|v/)|youtu\\.be/)([A-Za-z0-9_\\-]{11})"
    )

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** All downloadable media URLs of a page, de-duplicated, in page order. */
    fun extract(html: String, pageUrl: String): List<Found> {
        if (html.isBlank()) return emptyList()
        val text = unescape(html)
        val out = LinkedHashMap<String, Found>()

        fun put(rawUrl: String, from: String) {
            if (out.size >= MAX_RESULTS) return
            val abs = absolutize(pageUrl, rawUrl)
            if (!abs.startsWith("http")) return
            if (isJunk(abs)) return
            if (out.containsKey(abs)) return
            val ext = MediaUrlUtils.extOf(abs).lowercase()
            val kind = when (ext) {
                "m3u8" -> "hls"
                "mpd" -> "dash"
                "ts", "m4s" -> "chunk"
                "mp3", "m4a", "aac", "opus", "ogg" -> "audio"
                else -> "video"
            }
            out[abs] = Found(abs, labelFor(abs, kind, from), kind, from)
        }

        // 1) bare media URLs anywhere in the text (HTML, JSON, JS strings)
        for (m in DIRECT_URL.findAll(text)) put(m.value, "html")

        // 2) meta tags (og:video / twitter player)
        for (m in META_TAG.findAll(text)) {
            val tag = m.value
            val prop = META_PROP.find(tag)?.groupValues?.get(1) ?: "og:video"
            val content = META_CONTENT.find(tag)?.groupValues?.get(1) ?: continue
            if (prop.startsWith("og:audio")) continue
            put(content, "og:video")
        }

        // 3) <video>/<source>/<iframe> src attributes (also protocol-relative)
        for (m in TAG_SRC.findAll(text)) put(m.groupValues[1], "html")

        // 4) JSON keys (hlsManifestUrl, contentUrl, "url": …, "file": …)
        for (m in JSON_KEY.findAll(text)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2]
            if (key in GENERIC_KEYS && !looksLikeMediaFile(value)) continue
            put(value, "json")
        }

        return out.values.toList()
    }

    /** Page title (`<title>…</title>`), unescaped and trimmed. */
    fun titleOf(html: String): String {
        val raw = TITLE.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        return unescape(raw).replace(Regex("\\s+"), " ").trim()
    }

    /** First YouTube video ids found inside the page (embedded players, links). */
    fun findYouTubeIds(html: String): List<String> =
        YOUTUBE_ID.findAll(html).map { it.groupValues[1] }.distinct().take(5).toList()

    // ------------------------------------------------------------------
    // Helpers (also used by MediaResolver)
    // ------------------------------------------------------------------

    /** Turns `https:\/\/…`, `\u0026`, `&amp;` … back into normal URLs. */
    fun unescape(s: String): String {
        var out = s.replace("\\/", "/")
        out = out.replace("\\u0026", "&", true)
            .replace("\\u002f", "/", true)
            .replace("\\u003d", "=", true)
            .replace("\\u003f", "?", true)
        return out.replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&#x26;", "&", true)
    }

    /** Resolves a (possibly relative) reference against [base]. */
    fun absolutize(base: String, ref: String): String {
        val r = ref.trim().replace("\\u0026", "&").replace("&amp;", "&")
        if (r.isBlank()) return ""
        if (r.startsWith("http://") || r.startsWith("https://")) return r
        val baseUri = runCatching { java.net.URI(base) }.getOrNull() ?: return r
        val scheme = baseUri.scheme ?: "https"
        if (r.startsWith("//")) return "$scheme:$r"
        val authority = baseUri.rawAuthority ?: return r
        if (r.startsWith("/")) return "$scheme://$authority$r"
        val path = baseUri.rawPath ?: "/"
        val dir = path.substringBeforeLast('/', "")
        return "$scheme://$authority$dir/$r"
    }

    /** Rejects images, scripts, stylesheets, fonts — links that only look like media. */
    private fun isJunk(url: String): Boolean {
        val lower = url.substringBefore('#').substringBefore('?').lowercase()
        return listOf(
            ".gif", ".svg", ".css", ".js", ".png", ".jpg", ".jpeg",
            ".webp", ".ico", ".woff", ".woff2", ".ttf", ".otf", ".json"
        ).any { lower.endsWith(it) }
    }

    private fun looksLikeMediaFile(url: String): Boolean {
        val lower = url.lowercase()
        return Regex("\\.$EXT(?:[?#]|$)").containsMatchIn(lower) ||
            lower.contains(".m3u8") || lower.contains(".mpd") ||
            lower.contains("/hls/") || lower.contains("/dash/") ||
            lower.contains("videoplayback") || lower.contains("mime=video") ||
            lower.contains("mime=audio") || lower.contains("manifest")
    }

    private fun labelFor(url: String, kind: String, from: String): String {
        val host = MediaUrlUtils.hostOf(url)
        val base = when (kind) {
            "hls" -> "HLS playlist"
            "dash" -> "DASH manifest"
            "audio" -> "Audio"
            "chunk" -> "Video chunk"
            else -> MediaUrlUtils.guessDisplayName(url, host).ifBlank { "Video file" }
        }
        return "$base • $host"
    }
}
