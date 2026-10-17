package com.ultra.sniffer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL matching + parsing rules of the Instagram / Facebook extractor. */
class SocialExtractorTest {

    @Test
    fun `matches instagram and facebook url shapes`() {
        assertTrue(SocialExtractor.isInstagram("https://www.instagram.com/p/CxYz123AbC/"))
        assertTrue(SocialExtractor.isInstagram("https://instagram.com/reel/AbC123xyz/"))
        assertTrue(SocialExtractor.isFacebook("https://www.facebook.com/watch/?v=1234567890"))
        assertTrue(SocialExtractor.isFacebook("https://fb.watch/abcDEF123/"))
        assertEquals("CxYz123AbC", SocialExtractor.instagramShortcode("https://www.instagram.com/p/CxYz123AbC/?igsh=1"))
        assertEquals("AbC123xyz", SocialExtractor.instagramShortcode("https://www.instagram.com/reels/AbC123xyz/"))
        assertNull(SocialExtractor.instagramShortcode("https://www.instagram.com/"))
    }

    @Test
    fun `reads facebook video ids`() {
        assertEquals("1234567890", SocialExtractor.facebookVideoId("https://www.facebook.com/watch/?v=1234567890"))
        assertEquals("9876543210", SocialExtractor.facebookVideoId("https://www.facebook.com/user/videos/9876543210/"))
        assertEquals("5555555555", SocialExtractor.facebookVideoId("https://www.facebook.com/reel/5555555555"))
    }

    @Test
    fun `parses instagram video_versions json`() {
        val json = """
            {"items":[{"video_versions":[
              {"type":101,"width":640,"url":"https:\/\/scontent.cdninstagram.com\/v\/t50.2886-16\/111_n.mp4?_nc_cat=1&oe=ABC"},
              {"type":102,"width":480,"url":"https:\/\/scontent.cdninstagram.com\/v\/t50.2886-16\/222_n.mp4?_nc_cat=1&oe=ABC"}
            ]}]}
        """.trimIndent()
        val streams = SocialExtractor.parse(json, "instagram")
        assertEquals(2, streams.size)
        assertTrue(streams.all { it.url.startsWith("https://scontent.cdninstagram.com/") })
        assertTrue(streams.all { it.url.endsWith("oe=ABC") })
    }

    @Test
    fun `parses facebook embed hd and sd sources with hd first`() {
        val html = """
            <script>{"videoData":[{
              "sd_src":"https:\/\/video.xx.fbcdn.net\/v\/t42\/sd.mp4?_nc_cat=2",
              "hd_src":"https:\/\/video.xx.fbcdn.net\/v\/t42\/hd.mp4?_nc_cat=2"
            }]}</script>
        """.trimIndent()
        val streams = SocialExtractor.parse(html, "facebook")
        assertEquals(2, streams.size)
        assertEquals("hd.mp4?_nc_cat=2", streams.first().url.substringAfterLast('/'))
        assertTrue(streams.first().label.contains("HD"))
        assertTrue(streams.any { it.label.contains("SD") })
    }

    @Test
    fun `parses og video mp4 from page html`() {
        val html = """
            <meta property="og:video" content="https://scontent.cdninstagram.com/v/abc/333.mp4?_nc=1">
            <meta property="og:video:secure_url" content="https://scontent.cdninstagram.com/v/abc/333.mp4?_nc=1">
        """.trimIndent()
        val streams = SocialExtractor.parse(html, "instagram")
        assertEquals(1, streams.size)
        assertTrue(streams.first().url.contains("333.mp4"))
    }

    @Test
    fun `login wall page yields nothing`() {
        val html = "<html><body>Log in to Instagram to see this</body></html>"
        assertTrue(SocialExtractor.parse(html, "instagram").isEmpty())
    }
}
