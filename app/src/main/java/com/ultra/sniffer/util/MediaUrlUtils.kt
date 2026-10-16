package com.ultra.sniffer.util

import com.ultra.sniffer.core.MediaKind
import java.net.URI
import java.net.URLDecoder

/** URL classification helpers used by the sniffer, overlay and downloader. */
object MediaUrlUtils {

    private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "3gp", "mpg", "mpeg", "ogv")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "amr")

    /** Path part of a URL (no scheme/host/query/fragment). */
    fun pathOf(url: String): String {
        val noFrag = url.substringBefore('#')
        val q = noFrag.indexOf('?')
        val noQuery = if (q >= 0) noFrag.substring(0, q) else noFrag
        val schemeIdx = noQuery.indexOf("://")
        val rest = if (schemeIdx >= 0) noQuery.substring(schemeIdx + 3) else noQuery
        val slash = rest.indexOf('/')
        return if (slash >= 0) rest.substring(slash) else "/"
    }

    /** Lower-case file extension of the URL path, or "" when there is none. */
    fun extOf(url: String): String {
        val name = pathOf(url).substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        val ext = name.substring(dot + 1).lowercase()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else ""
    }

    fun kindOf(url: String): MediaKind {
        val ext = extOf(url)
        when (ext) {
            "m3u8" -> return MediaKind.HLS
            "mpd" -> return MediaKind.DASH
            "ts", "m2ts" -> return MediaKind.TS
            in VIDEO_EXT -> return MediaKind.MP4
            in AUDIO_EXT -> return MediaKind.AUDIO
        }
        // extension hidden behind a query string / CDN path
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> MediaKind.HLS
            lower.contains(".mpd") -> MediaKind.DASH
            lower.contains(".mp4") || lower.contains(".m4v") -> MediaKind.MP4
            lower.contains("videoplayback") || lower.contains("mime=video") -> MediaKind.MP4
            lower.contains(".m4a") || lower.contains(".mp3") || lower.contains("mime=audio") -> MediaKind.AUDIO
            else -> MediaKind.UNKNOWN
        }
    }

    fun isLikelyMedia(url: String): Boolean {
        if (kindOf(url) != MediaKind.UNKNOWN) return true
        val lower = url.lowercase()
        return lower.contains("/hls/") ||
            lower.contains("/dash/") ||
            lower.contains("manifest.googlevideo") ||
            lower.contains("/playlist") ||
            lower.contains("/master.m3u8") ||
            lower.contains("dash.mpd") ||
            lower.contains("format=mpd")
    }

    /** Map an HTTP Content-Type header to a media kind, or null when not media. */
    fun kindFromContentType(contentType: String?): MediaKind? {
        if (contentType.isNullOrBlank()) return null
        val ct = contentType.lowercase()
        return when {
            ct.contains("mpegurl") -> MediaKind.HLS
            ct.contains("dash+xml") -> MediaKind.DASH
            ct.contains("mp2t") -> MediaKind.TS
            ct.startsWith("video/") -> MediaKind.MP4
            ct.startsWith("audio/") -> MediaKind.AUDIO
            else -> null
        }
    }

    /** Video platforms whose *host hint* is worth a floating "open browser" bubble. */
    private val VIDEO_PLATFORM_HOSTS = listOf(
        "youtube", "googlevideo", "ytimg", "youtu.be",
        "facebook", "fbcdn", "instagram", "cdninstagram",
        "tiktok", "byteoversea", "twitter", "twimg", "x.com",
        "vimeo", "dailymotion", "dmcdn", "twitch", "ttvnw",
        "hotstar", "jio", "sonyliv", "zee5", "primevideo",
        "aajtak", "ndtv", "news18", "abplive", "zeenews",
        "sharechat", "moj", "mxplayer", "altbalaji", "voot"
    )

    fun isVideoPlatformHost(host: String): Boolean {
        val h = host.lowercase()
        return VIDEO_PLATFORM_HOSTS.any { h.contains(it) }
    }

    /** Best landing page for a host hint (googlevideo/ytimg → mobile YouTube). */
    fun landingPageFor(host: String): String {
        val h = host.lowercase()
        return when {
            h.contains("youtube") || h.contains("googlevideo") || h.contains("ytimg") ||
                h.contains("youtu.be") -> "https://m.youtube.com/"
            h.contains("facebook") || h.contains("fbcdn") -> "https://m.facebook.com/"
            h.contains("instagram") || h.contains("cdninstagram") -> "https://www.instagram.com/"
            h.contains("tiktok") || h.contains("byteoversea") -> "https://www.tiktok.com/"
            h.contains("twitter") || h.contains("twimg") || h.contains("x.com") -> "https://x.com/"
            h.contains("vimeo") -> "https://vimeo.com/"
            h.contains("dailymotion") || h.contains("dmcdn") -> "https://www.dailymotion.com/"
            else -> "https://$host"
        }
    }

    fun hostOf(url: String): String {
        runCatching { URI(url).host }.getOrNull()?.let { if (it.isNotBlank()) return it }
        val schemeIdx = url.indexOf("://")
        val rest = if (schemeIdx >= 0) url.substring(schemeIdx + 3) else url
        return rest.substringBefore('/').substringBefore(':')
    }

    /** Human readable name for the overlay / list. */
    fun guessDisplayName(url: String, host: String): String {
        val decoded = runCatching { URLDecoder.decode(pathOf(url).substringAfterLast('/'), "UTF-8") }
            .getOrDefault(pathOf(url).substringAfterLast('/'))
        val name = decoded.substringBefore('?')
        return when {
            name.isNotBlank() && name.contains('.') -> sanitize(name)
            host.isNotBlank() -> host
            else -> "Video stream"
        }
    }

    /** Safe output file name for the downloader. */
    fun guessFileName(url: String, fallbackExt: String = "mp4"): String {
        val decoded = runCatching { URLDecoder.decode(pathOf(url).substringAfterLast('/'), "UTF-8") }
            .getOrDefault(pathOf(url).substringAfterLast('/'))
        var name = sanitize(decoded)
        if (name.isBlank() || !name.contains('.')) {
            name = "ultra_video_${System.currentTimeMillis() / 1000}.$fallbackExt"
        }
        return name
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").trim('_').take(120)
}
