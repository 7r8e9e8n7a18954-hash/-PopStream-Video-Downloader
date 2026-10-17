package com.ultra.sniffer.download

import com.ultra.sniffer.core.MediaKind
import com.ultra.sniffer.core.MediaSource
import com.ultra.sniffer.core.SniffedMedia
import com.ultra.sniffer.util.MediaUrlUtils
import com.ultra.sniffer.util.Net

/** What a user-provided link resolved to. */
sealed interface ResolveResult {
    /** A normal, directly downloadable media URL. */
    data class Direct(val media: SniffedMedia) : ResolveResult

    /** Several download options (qualities / manifests) — let the user choose. */
    data class Options(val title: String, val streams: List<ResolvedStream>) : ResolveResult

    /** Could not resolve; [message] explains why in user language. */
    data class Failed(val message: String, val url: String) : ResolveResult
}

/**
 * Turns *any* link into download options:
 *
 *   YouTube link            -> InnerTube extraction (video + audio pairs)
 *   direct media URL        -> Direct (existing downloaders)
 *   HLS/DASH/HTML playlist  -> Direct (existing downloaders)
 *   other web page          -> scan HTML for media URLs / embedded players
 *
 * It never refuses a link — when nothing can be resolved the caller offers a
 * "download anyway" fallback, so the app is not the one putting limits.
 */
object MediaResolver {

    private val MPD_TAG = Regex("<MPD[\\s>]", RegexOption.IGNORE_CASE)
    private val TS_LINE = Regex("#EXTINF", RegexOption.IGNORE_CASE)

    /** Adds a scheme when missing and trims common wrappers. */
    fun normalize(raw: String): String {
        var url = raw.trim().trim('"', '\'', '<', '>', ' ')
        // strip markdown-style [text](url)
        Regex("\\((https?://[^)\\s]+)\\)").find(url)?.let { url = it.groupValues[1] }
        if (url.isBlank()) return url
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
        }
        return url
    }

    /** Resolves a link into something downloadable. Never throws. */
    suspend fun resolve(rawUrl: String): ResolveResult {
        val url = normalize(rawUrl)
        if (!url.startsWith("http")) {
            return ResolveResult.Failed("सही http(s) link डालें", url)
        }

        // 1) YouTube — biggest request, handled by the dedicated extractor
        YouTubeExtractor.videoIdOf(url)?.let { videoId ->
            return try {
                val ex = YouTubeExtractor.extract(videoId)
                if (ex.ok) {
                    ResolveResult.Options(ex.title.ifBlank { "YouTube video" }, ex.streams)
                } else {
                    ResolveResult.Failed(ex.error.ifBlank { "YouTube extraction failed" }, url)
                }
            } catch (t: Throwable) {
                ResolveResult.Failed(t.message ?: "YouTube extraction failed", url)
            }
        }

        // 2) obviously a media URL — download it directly
        if (MediaUrlUtils.kindOf(url) != MediaKind.UNKNOWN || MediaUrlUtils.isLikelyMedia(url)) {
            return ResolveResult.Direct(SniffedMedia.fromUrl(url, MediaSource.MANUAL))
        }

        // 3) ask the server what this URL really is
        val probe = runCatching { Net.probe(url) }.getOrNull()
        probe?.let { p ->
            MediaUrlUtils.kindFromContentType(p.contentType)?.let { kind ->
                val label = p.fileName?.takeIf { it.isNotBlank() }
                    ?: MediaUrlUtils.guessDisplayName(p.finalUrl, MediaUrlUtils.hostOf(p.finalUrl))
                return ResolveResult.Direct(
                    SniffedMedia.fromUrl(p.finalUrl, MediaSource.MANUAL).copy(kind = kind, label = label)
                )
            }
        }

        // 4) treat it as a web page and look inside
        return resolvePage(url, probe?.finalUrl ?: url)
    }

    /**
     * Scans a page (HTML / JSON / playlist) for media URLs.
     * Used both by [resolve] and directly by the in-app browser.
     */
    suspend fun resolvePage(pageUrl: String, fetchedUrl: String = pageUrl): ResolveResult {
        val html = try {
            Net.getTextCapped(fetchedUrl)
        } catch (t: Throwable) {
            return ResolveResult.Failed("Page खुल नहीं पाया: ${t.message}", pageUrl)
        }

        // a playlist that pretended to be a page
        val head = html.trimStart().take(64)
        if (head.startsWith("#EXTM3U")) {
            val title = "HLS playlist"
            return ResolveResult.Options(
                title,
                listOf(
                    ResolvedStream(
                        url = fetchedUrl,
                        kind = MediaKind.HLS,
                        label = "HLS playlist • ${MediaUrlUtils.hostOf(fetchedUrl)}",
                        tag = "hls"
                    )
                )
            )
        }
        if (MPD_TAG.containsMatchIn(html) && html.trimStart().startsWith("<")) {
            return ResolveResult.Options(
                "DASH manifest",
                listOf(
                    ResolvedStream(
                        url = fetchedUrl,
                        kind = MediaKind.DASH,
                        label = "DASH manifest • ${MediaUrlUtils.hostOf(fetchedUrl)}",
                        tag = "dash"
                    )
                )
            )
        }

        val found = PageMediaExtractor.extract(html, fetchedUrl)
        if (found.isNotEmpty()) {
            val title = PageMediaExtractor.titleOf(html).ifBlank { MediaUrlUtils.hostOf(pageUrl) }
            return ResolveResult.Options(title, found.map { it.toResolved() })
        }

        // 5) page embeds a YouTube player? follow it
        for (id in PageMediaExtractor.findYouTubeIds(html)) {
            val ex = runCatching { YouTubeExtractor.extract(id) }.getOrNull() ?: continue
            if (ex.ok) return ResolveResult.Options(ex.title.ifBlank { "YouTube video" }, ex.streams)
        }

        // page had #EXTINF but no #EXTM3U header (edge case)
        if (TS_LINE.containsMatchIn(html)) {
            return ResolveResult.Options(
                "Video playlist",
                listOf(
                    ResolvedStream(
                        url = fetchedUrl,
                        kind = MediaKind.HLS,
                        label = "Video playlist • ${MediaUrlUtils.hostOf(fetchedUrl)}",
                        tag = "hls"
                    )
                )
            )
        }

        // Honest, upfront answer for DRM platforms instead of a confusing failure:
        // their paid content is legally and technically not downloadable here.
        val host = MediaUrlUtils.hostOf(pageUrl).lowercase()
        if (DRM_PLATFORMS.any { host.contains(it) }) {
            return ResolveResult.Failed(
                "यह DRM-protected platform है ($host) — यहाँ का paid/premium content download नहीं हो सकता। " +
                    "Non-DRM videos (news, open/free content, अपनी videos) पूरी तरह download होती हैं।",
                pageUrl
            )
        }

        return ResolveResult.Failed(
            "इस page में कोई सीधा video link नहीं दिखा। Page को in-app browser में खोलें → video चलाएँ → वहाँ से link पकड़ेगा।",
            pageUrl
        )
    }

    /** Streaming platforms whose catalog is Widevine/DRM protected. */
    private val DRM_PLATFORMS = listOf(
        "netflix", "primevideo", "amazonvideo", "hotstar", "sonyliv", "zee5",
        "jiocinema", "voot", "disneyplus", "hulu", "hbomax", "max.com",
        "spotify", "gaana", "wynk", "applemusic", "music.apple"
    )
}

/** Page result -> download option. */
private fun PageMediaExtractor.Found.toResolved(): ResolvedStream {
    val ext = MediaUrlUtils.extOf(url).lowercase()
    val kind = when {
        kindHint == "hls" || ext == "m3u8" -> MediaKind.HLS
        kindHint == "dash" || ext == "mpd" -> MediaKind.DASH
        kindHint == "audio" || ext in setOf("mp3", "m4a", "aac", "opus", "ogg") -> MediaKind.AUDIO
        else -> MediaKind.MP4
    }
    val tag = when (kind) {
        MediaKind.HLS -> "hls"
        MediaKind.DASH -> "dash"
        MediaKind.AUDIO -> "audio"
        else -> ext.ifBlank { "mp4" }
    }
    return ResolvedStream(url = url, kind = kind, label = label, tag = tag)
}
