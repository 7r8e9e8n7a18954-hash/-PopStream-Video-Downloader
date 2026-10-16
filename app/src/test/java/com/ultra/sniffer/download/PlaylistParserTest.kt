package com.ultra.sniffer.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the playlist parsers (no Android device needed):
 *   ./gradlew testDebugUnitTest
 */
class PlaylistParserTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Hindi",DEFAULT=YES,URI="audio/hin.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aac"
        video/360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,AUDIO="aac"
        video/720p/index.m3u8
    """.trimIndent()

    @Test
    fun `master playlist finds best variant and audio rendition`() {
        assertTrue(M3u8Parser.isMaster(master))

        val variants = M3u8Parser.parseMaster(master, "https://cdn.example.com/live/master.m3u8")
        assertEquals(2, variants.size)
        val best = variants.maxByOrNull { it.bandwidth }!!
        assertEquals("https://cdn.example.com/live/video/720p/index.m3u8", best.url)
        assertEquals("aac", best.audioGroup)

        val audio = M3u8Parser.parseAudioRenditions(master, "https://cdn.example.com/live/master.m3u8")
        assertEquals(1, audio.size)
        assertTrue(audio.first().isDefault)
        assertEquals("https://cdn.example.com/live/audio/hin.m3u8", audio.first().url)
    }

    @Test
    fun `media playlist keeps order, durations and init segment`() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:5.005,
            seg-1.m4s
            #EXTINF:5.005,
            https://other.cdn/seg-2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = M3u8Parser.parseMedia(media, "https://cdn.example.com/v/index.m3u8")
        assertEquals("https://cdn.example.com/v/init.mp4", parsed.initUrl)
        assertEquals(2, parsed.segmentUrls.size)
        assertEquals("https://cdn.example.com/v/seg-1.m4s", parsed.segmentUrls[0])
        assertEquals("https://other.cdn/seg-2.m4s", parsed.segmentUrls[1])
        assertEquals(5.005, parsed.durations[0], 0.0001)
    }

    @Test
    fun `dash segment template with timeline builds all segment urls`() {
        val mpd = """
            <MPD mediaPresentationDuration="PT30S" profiles="urn:mpeg:dash:profile:isoff-live:2011">
              <Period>
                <AdaptationSet mimeType="video/mp4" segmentAlignment="true">
                  <SegmentTemplate timescale="1000" initialization="init-${'$'}RepresentationID${'$'}.mp4"
                                   media="chunk-${'$'}RepresentationID${'$'}-${'$'}Time${'$'}.m4s" startNumber="1">
                    <SegmentTimeline>
                      <S t="0" d="5000" r="2"/>
                    </SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="v720" bandwidth="2400000" width="1280" height="720"/>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4">
                  <SegmentTemplate timescale="48000" initialization="a-init.mp4"
                                   media="a-${'$'}Number${'$'}.m4s" startNumber="1" duration="96000"/>
                  <Representation id="a1" bandwidth="128000"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val reps = MpdParser.parse(mpd, "https://dash.example.com/movie/manifest.mpd") { org.kxml2.io.KXmlParser() }
        assertEquals(2, reps.size)

        val video = reps.first { it.mime.startsWith("video/") }
        assertEquals("https://dash.example.com/movie/init-v720.mp4", video.initUrl)
        assertEquals(
            listOf(
                "https://dash.example.com/movie/chunk-v720-0.m4s",
                "https://dash.example.com/movie/chunk-v720-5000.m4s",
                "https://dash.example.com/movie/chunk-v720-10000.m4s"
            ),
            video.segmentUrls
        )

        val audio = reps.first { it.mime.startsWith("audio/") }
        assertEquals("https://dash.example.com/movie/a-init.mp4", audio.initUrl)
        assertEquals(15, audio.segmentUrls.size) // 30s / 2s per segment
        assertEquals("https://dash.example.com/movie/a-1.m4s", audio.segmentUrls.first())
    }

    @Test
    fun `iso duration parsing`() {
        assertEquals(3723.5, MpdParser.parseIsoDuration("PT1H2M3.5S"), 0.001)
        assertEquals(0.0, MpdParser.parseIsoDuration("nonsense"), 0.001)
    }

    @Test
    fun `attribute parser handles quoted values with commas`() {
        val variants = M3u8Parser.parseMaster(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"avc1.4d401f,mp4a.40.2\",AUDIO=\"grp\"\na.m3u8",
            "https://h/x.m3u8"
        )
        assertEquals(1, variants.size)
        assertEquals("grp", variants[0].audioGroup)
        assertEquals("https://h/a.m3u8", variants[0].url)
        assertNull(variants[0].resolution)
    }
}
