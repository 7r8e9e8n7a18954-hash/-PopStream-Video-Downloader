package com.ultra.sniffer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the "any link → real video" engine:
 * page scanning, YouTube URL matching and YouTube quality pairing.
 */
class ExtractorTest {

    // ------------------------------------------------------------------
    // PageMediaExtractor
    // ------------------------------------------------------------------

    @Test
    fun `finds direct media urls in html`() {
        val html = """
            <html><body>
            <video src="https://cdn.example.com/movies/clip.mp4"></video>
            <script>var hls = "https:\/\/cdn.example.com\/live\/master.m3u8?token=abc";</script>
            <a href="https://cdn.example.com/audio/song.m4a">song</a>
            </body></html>
        """.trimIndent()
        val urls = PageMediaExtractor.extract(html, "https://site.example.com/watch").map { it.url }
        assertTrue(urls.contains("https://cdn.example.com/movies/clip.mp4"))
        assertTrue(urls.contains("https://cdn.example.com/live/master.m3u8?token=abc"))
        assertTrue(urls.contains("https://cdn.example.com/audio/song.m4a"))
    }

    @Test
    fun `reads og video meta and json manifests`() {
        val html = """
            <head>
              <meta property="og:video" content="https://v.example.com/og.mp4">
              <meta property="og:video:secure_url" content="//v.example.com/secure.mp4">
            </head>
            <script>window.__DATA__ = {"hlsManifestUrl":"https://v.example.com/stream/master.m3u8","dashManifestUrl":"https:\/\/v.example.com\/stream\/manifest.mpd"};</script>
        """.trimIndent()
        val urls = PageMediaExtractor.extract(html, "https://www.example.com/page").map { it.url }
        assertTrue(urls.contains("https://v.example.com/og.mp4"))
        assertTrue(urls.contains("https://v.example.com/secure.mp4"))
        assertTrue(urls.contains("https://v.example.com/stream/master.m3u8"))
        assertTrue(urls.contains("https://v.example.com/stream/manifest.mpd"))
    }

    @Test
    fun `parses json contentUrl and file keys`() {
        val html = """<script>var player = {"contentUrl":"https://cdn.example.com/show/ep1.mp4",
            "file":"https://cdn.example.com/show/ep2.mp4","url":"https://example.com/api/track?id=5"};</script>"""
        val urls = PageMediaExtractor.extract(html, "https://example.org/").map { it.url }
        assertTrue(urls.contains("https://cdn.example.com/show/ep1.mp4"))
        assertTrue(urls.contains("https://cdn.example.com/show/ep2.mp4"))
        // generic "url" key is skipped when the value is not media
        assertTrue(urls.none { it.contains("/api/track") })
    }

    @Test
    fun `ignores images stylesheets scripts and fonts`() {
        val html = """
            <img src="https://cdn.example.com/logo.png">
            <link href="https://cdn.example.com/app.css">
            <script src="https://cdn.example.com/app.js"></script>
            <div data-bg="https://cdn.example.com/pic.webp"></div>
            <style>body{background:url(https://cdn.example.com/bg.jpg)}</style>
        """.trimIndent()
        assertEquals(emptyList<String>(), PageMediaExtractor.extract(html, "https://x.example/").map { it.url })
    }

    @Test
    fun `reads page title and embedded youtube ids`() {
        val html = """<title>My  Funny   Video</title>
            <iframe src="https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1"></iframe>"""
        assertEquals("My Funny Video", PageMediaExtractor.titleOf(html))
        assertEquals(listOf("dQw4w9WgXcQ"), PageMediaExtractor.findYouTubeIds(html))
    }

    @Test
    fun `absolutize resolves relative and protocol relative urls`() {
        val base = "https://site.example.com/a/b/page.html"
        assertEquals("https://cdn.example.com/x.mp4", PageMediaExtractor.absolutize(base, "https://cdn.example.com/x.mp4"))
        assertEquals("https://cdn.example.com/x.mp4", PageMediaExtractor.absolutize(base, "//cdn.example.com/x.mp4"))
        assertEquals("https://site.example.com/x.mp4", PageMediaExtractor.absolutize(base, "/x.mp4"))
        assertEquals("https://site.example.com/a/b/x.mp4", PageMediaExtractor.absolutize(base, "x.mp4"))
    }

    // ------------------------------------------------------------------
    // YouTubeExtractor
    // ------------------------------------------------------------------

    @Test
    fun `matches youtube url shapes`() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoIdOf("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=30s"))
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoIdOf("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("abcDEF12345", YouTubeExtractor.videoIdOf("https://www.youtube.com/shorts/abcDEF12345"))
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoIdOf("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertNull(YouTubeExtractor.videoIdOf("https://example.com/video.mp4"))
        assertNull(YouTubeExtractor.videoIdOf("https://notyoutube.com/watch?v=1"))
    }

    @Test
    fun `pairs best video with best audio and prefers h264`() {
        val formats = listOf(
            YouTubeExtractor.YtFormat(337, "https://gv/av1-1080", "video/mp4; codecs=\"av01\"", 4_500_000, 1080, 33_000_000, muxed = false),
            YouTubeExtractor.YtFormat(137, "https://gv/h264-1080", "video/mp4; codecs=\"avc1\"", 4_000_000, 1080, 30_000_000, muxed = false),
            YouTubeExtractor.YtFormat(136, "https://gv/h264-720", "video/mp4; codecs=\"avc1\"", 2_000_000, 720, 15_000_000, muxed = false),
            YouTubeExtractor.YtFormat(140, "https://gv/aac-128", "audio/mp4; codecs=\"mp4a\"", 128_000, 0, 1_000_000, muxed = false),
            YouTubeExtractor.YtFormat(251, "https://gv/opus-160", "audio/webm; codecs=\"opus\"", 160_000, 0, 1_200_000, muxed = false),
            YouTubeExtractor.YtFormat(18, "https://gv/360-muxed", "video/mp4; codecs=\"avc1, mp4a\"", 500_000, 360, 4_000_000, muxed = true)
        )
        val streams = YouTubeExtractor.buildStreams(formats, durationSec = 60)
        assertTrue(streams.isNotEmpty())

        val best = streams.first()
        assertEquals(1080, best.quality)
        assertEquals("https://gv/h264-1080", best.url)      // h264 wins over AV1
        assertEquals("https://gv/aac-128", best.audioUrl)   // mp4a wins over opus
        assertTrue(best.isPair)

        val muxed = streams.firstOrNull { it.url == "https://gv/360-muxed" }
        assertTrue(muxed != null && !muxed.isPair)

        // merge options must come before the ready file of the same height
        val merge720 = streams.indexOfFirst { it.url == "https://gv/h264-720" }
        val ready360 = streams.indexOfFirst { it.url == "https://gv/360-muxed" }
        assertTrue(merge720 < ready360)
    }

    @Test
    fun `audio only fallback when no video formats exist`() {
        val formats = listOf(
            YouTubeExtractor.YtFormat(140, "https://gv/audio", "audio/mp4; codecs=\"mp4a\"", 128_000, 0, 900_000, muxed = false)
        )
        val streams = YouTubeExtractor.buildStreams(formats)
        assertEquals(1, streams.size)
        assertTrue(streams.first().kind == com.ultra.sniffer.core.MediaKind.AUDIO)
    }

    // ------------------------------------------------------------------
    // MediaResolver.normalize
    // ------------------------------------------------------------------

    @Test
    fun `normalize adds scheme and unwraps markdown`() {
        assertEquals("https://example.com/x.mp4", MediaResolver.normalize("example.com/x.mp4"))
        assertEquals("https://example.com/x.mp4", MediaResolver.normalize("  https://example.com/x.mp4  "))
        assertEquals("https://youtu.be/abc12345678", MediaResolver.normalize("watch [here](https://youtu.be/abc12345678)"))
    }
}
