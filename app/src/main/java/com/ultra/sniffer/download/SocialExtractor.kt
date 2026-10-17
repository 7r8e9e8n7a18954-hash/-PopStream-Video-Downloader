package com.ultra.sniffer.download

import com.ultra.sniffer.core.MediaKind
import com.ultra.sniffer.util.MediaUrlUtils
import com.ultra.sniffer.util.Net

/**
 * Best-effort extractor for **normal (public, non-DRM)** Instagram and
 * Facebook videos: Reels, posts, watch/permalink videos and fb.watch links.
 *
 * Nothing here touches DRM-protected content — it only reads the public
 * page/embed data that these platforms serve for regular videos and picks
 * out the playable MP4 URLs (HD preferred). Login-walled posts simply return
 * no streams and the app falls back to the in-app browser.
 */
object SocialExtractor {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val IG_HEADERS = mapOf(
        "User-Agent" to BROWSER_UA,
        "Referer" to "https://www.instagram.com/"
    )
    private val FB_HEADERS = mapOf(
        "User-Agent" to BROWSER_UA,
        "Referer" to "https://www.facebook.com/"
    )

    /** Facebook JSON keys carrying the playable file, best quality first. */
    private val FB_KEYS = listOf(
        "hd_src",
        "browser_native_hd_url",
        "playable_url_quality_hd",
        "playable_url",
        "sd_src",
        "browser_native_sd_url"
    )

    private val MP4_URL = Regex(
        "https?://[^\"'\\s\\\\<>]+?\\.mp4(?:[^\"'\\s\\\\<>]*)?",
        RegexOption.IGNORE_CASE
    )

    private val IG_SHORTCODE = Regex("instagram\\.com/(?:p|reel|reels|tv|share)/([A-Za-z0-9_\\-]+)")

    fun isInstagram(url: String): Boolean {
        val host = MediaUrlUtils.hostOf(url).lowercase()
        return host.contains("instagram.com") || host.contains("instagr.am")
    }

    fun isFacebook(url: String): Boolean {
        val host = MediaUrlUtils.hostOf(url).lowercase()
        return host.contains("facebook.com") || host.contains("fb.watch") || host.contains("fb.gg")
    }

    /** Post/reel shortcode of an Instagram link, or null. */
    fun instagramShortcode(url: String): String? =
        IG_SHORTCODE.find(url)?.groupValues?.get(1)

    /** Numeric video id of a Facebook link (watch?v=, /videos/, /reel/), or null. */
    fun facebookVideoId(url: String): String? {
        Regex("[?&]v=(\\d{6,})").find(url)?.let { return it.groupValues[1] }
        Regex("/videos/(?:[^/]+/)?(\\d{6,})").find(url)?.let { return it.groupValues[1] }
        Regex("/reel/(\\d{6,})").find(url)?.let { return it.groupValues[1] }
        Regex("/video(s)?/?(\\d{6,})").find(url)?.let { return it.groupValues[2] }
        return null
    }

    // ------------------------------------------------------------------
    // Extraction
    // ------------------------------------------------------------------

    suspend fun instagram(url: String): List<ResolvedStream> {
        val shortcode = instagramShortcode(url) ?: return emptyList()
        val candidates = listOf(
            "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis",
            "https://www.instagram.com/p/$shortcode/",
            "https://www.instagram.com/reel/$shortcode/",
            "https://www.instagram.com/reels/$shortcode/"
        )
        for (candidate in candidates) {
            val text = runCatching { Net.getTextCapped(candidate, 2 * 1024 * 1024, IG_HEADERS) }
                .getOrNull() ?: continue
            val streams = parse(text, "instagram")
            if (streams.isNotEmpty()) return streams
        }
        return emptyList()
    }

    suspend fun facebook(url: String): List<ResolvedStream> {
        val candidates = ArrayList<String>(3)
        facebookVideoId(url)?.let { candidates.add("https://www.facebook.com/video/embed?video_id=$it") }
        candidates.add(url)
        if (url.contains("fb.watch", true)) {
            // fb.watch redirects to the real video page; follow it and retry.
            runCatching { Net.probe(url, FB_HEADERS).finalUrl }
                .getOrNull()
                ?.takeIf { it.startsWith("http") && it != url }
                ?.let { final ->
                    facebookVideoId(final)?.let {
                        candidates.add("https://www.facebook.com/video/embed?video_id=$it")
                    }
                    candidates.add(final)
                }
        }
        var i = 0
        while (i < candidates.size && i < 5) {
            val candidate = candidates[i]
            i++
            val text = runCatching { Net.getTextCapped(candidate, 2 * 1024 * 1024, FB_HEADERS) }
                .getOrNull() ?: continue
            val streams = parse(text, "facebook")
            if (streams.isNotEmpty()) return streams
        }
        return emptyList()
    }

    /**
     * Pulls playable MP4 URLs out of Instagram/Facebook JSON or HTML.
     * Pure function → unit-tested ([com.ultra.sniffer.download.ExtractorTest]).
     */
    internal fun parse(text: String, platform: String): List<ResolvedStream> {
        if (text.isBlank()) return emptyList()
        val clean = PageMediaExtractor.unescape(text)
        val found = LinkedHashMap<String, Pair<String, Int>>() // url -> (tag, quality)

        if (platform == "facebook") {
            FB_KEYS.forEachIndexed { rank, key ->
                Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").findAll(clean).forEach { match ->
                    val link = match.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
                    if (link.startsWith("http") && !found.containsKey(link)) {
                        val tag = when {
                            key.contains("hd", true) -> "HD"
                            key.contains("sd", true) -> "SD"
                            else -> "video"
                        }
                        found[link] = tag to (100 - rank)
                    }
                }
            }
        }

        // Any directly playable .mp4 URL (video_versions, og:video, scontent CDN…)
        MP4_URL.findAll(clean).forEach { match ->
            val link = match.value.replace("&amp;", "&")
            if (!found.containsKey(link)) found[link] = "mp4" to 50
        }

        val platformLabel = if (platform == "instagram") "Instagram" else "Facebook"
        return found.entries.take(6)
            .map { (link, meta) ->
                ResolvedStream(
                    url = link,
                    kind = MediaKind.MP4,
                    label = "$platformLabel ${meta.first} • ${MediaUrlUtils.hostOf(link)}",
                    quality = meta.second,
                    headers = if (platform == "instagram") IG_HEADERS else FB_HEADERS,
                    tag = if (platform == "instagram") "insta" else "fb"
                )
            }
            .sortedByDescending { it.quality }
    }
}
