package com.ultra.sniffer.download

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** One DASH representation (video or audio track). */
data class DashRep(
    val id: String,
    val mime: String,
    val bandwidth: Long,
    val segmentUrls: List<String>,
    val initUrl: String?,
    val singleFileUrl: String?
)

/**
 * Pragmatic MPD parser (DASH). Supports the common layouts:
 *   - SegmentTemplate with $Number$ / $Time$ (+ optional SegmentTimeline)
 *   - SegmentList
 *   - single-file representations (BaseURL only)
 */
object MpdParser {

    fun parse(xml: String, pageUrl: String): List<DashRep> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val reps = mutableListOf<DashRep>()
        val baseStack = ArrayList<String>()
        var mpdDurationSec = 0.0

        var adMime = ""
        var adBase = ""
        var adTemplateMedia: String? = null
        var adTemplateInit: String? = null
        var adStartNumber = 1
        var adDuration = 0L
        var adTimescale = 1L

        var rep: RepBuilder? = null
        var templateMedia: String? = null
        var templateInit: String? = null
        var templateStart = 1
        var templateDuration = 0L
        var templateTimescale = 1L
        var inTimeline = false
        var timelineTimes = mutableListOf<Long>()   // absolute start time (in timescale units)
        var timelineCount = 0
        var timelineCursor = 0L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "MPD" -> {
                            val dur = parser.getAttributeValue(null, "mediaPresentationDuration")
                                ?: parser.getAttributeValue(null, "maxSegmentDuration")
                            dur?.let { mpdDurationSec = parseIsoDuration(it) }
                        }
                        "Period" -> {
                            if (baseStack.isEmpty()) baseStack.add(pageUrl) else baseStack.add(baseStack.last())
                        }
                        "BaseURL" -> {
                            val text = parser.nextText().trim()
                            val base = if (baseStack.isEmpty()) pageUrl else baseStack.last()
                            val resolved = M3u8Parser.resolve(base, text)
                            if (baseStack.isEmpty()) baseStack.add(resolved) else baseStack[baseStack.size - 1] = resolved
                            rep?.base = resolved
                        }
                        "AdaptationSet" -> {
                            adMime = parser.getAttributeValue(null, "mimeType") ?: ""
                            adBase = ""
                            adTemplateMedia = null
                            adTemplateInit = null
                            adStartNumber = 1
                            adDuration = 0L
                            adTimescale = 1L
                            baseStack.add(pageUrl)
                        }
                        "Representation" -> {
                            rep = RepBuilder(
                                id = parser.getAttributeValue(null, "id") ?: "0",
                                mime = parser.getAttributeValue(null, "mimeType") ?: adMime,
                                bandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L,
                                base = baseStack.lastOrNull() ?: pageUrl
                            )
                            templateMedia = adTemplateMedia
                            templateInit = adTemplateInit
                            templateStart = adStartNumber
                            templateDuration = adDuration
                            templateTimescale = adTimescale
                            baseStack.add(baseStack.lastOrNull() ?: pageUrl)
                        }
                        "SegmentTemplate" -> {
                            val media = parser.getAttributeValue(null, "media")
                            val init = parser.getAttributeValue(null, "initialization")
                            val start = parser.getAttributeValue(null, "startNumber")?.toIntOrNull() ?: 1
                            val duration = parser.getAttributeValue(null, "duration")?.toLongOrNull() ?: 0L
                            val timescale = parser.getAttributeValue(null, "timescale")?.toLongOrNull() ?: 1L
                            if (rep != null) {
                                templateMedia = media ?: templateMedia
                                templateInit = init ?: templateInit
                                templateStart = start
                                templateDuration = duration
                                templateTimescale = timescale
                            } else {
                                adTemplateMedia = media
                                adTemplateInit = init
                                adStartNumber = start
                                adDuration = duration
                                adTimescale = timescale
                            }
                        }
                        "SegmentTimeline" -> {
                            inTimeline = true
                            timelineTimes = mutableListOf()
                            timelineCount = 0
                            timelineCursor = 0L
                        }
                        "S" -> if (inTimeline) {
                            val t = parser.getAttributeValue(null, "t")?.toLongOrNull()
                            if (t != null) timelineCursor = t
                            val d = parser.getAttributeValue(null, "d")?.toLongOrNull() ?: 0L
                            val r = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: 0
                            val repeats = if (r < 0) 0 else r
                            repeat(repeats + 1) {
                                timelineTimes.add(timelineCursor)
                                timelineCursor += d
                                timelineCount++
                            }
                        }
                        "SegmentList" -> {
                            // handled via SegmentURL children
                        }
                        "SegmentURL" -> {
                            val media = parser.getAttributeValue(null, "media") ?: ""
                            if (media.isNotBlank() && rep != null) {
                                rep.segmentList.add(M3u8Parser.resolve(rep.base, media))
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "MPD" -> Unit
                    "Period" -> if (baseStack.isNotEmpty()) baseStack.removeAt(baseStack.size - 1)
                    "AdaptationSet" -> if (baseStack.isNotEmpty()) baseStack.removeAt(baseStack.size - 1)
                    "Representation" -> {
                        rep?.let { r ->
                            val urls = buildSegmentUrls(
                                r, templateMedia, templateInit, templateStart,
                                templateDuration, templateTimescale, timelineCount,
                                timelineTimes.map { it }, mpdDurationSec
                            )
                            reps.add(
                                DashRep(
                                    id = r.id,
                                    mime = r.mime,
                                    bandwidth = r.bandwidth,
                                    segmentUrls = urls.first,
                                    initUrl = urls.second,
                                    singleFileUrl = if (urls.first.isEmpty()) r.base else null
                                )
                            )
                        }
                        rep = null
                        if (baseStack.isNotEmpty()) baseStack.removeAt(baseStack.size - 1)
                    }
                    "SegmentTimeline" -> inTimeline = false
                }
            }
            event = parser.next()
        }
        return reps
    }

    // ------------------------------------------------------------------

    private class RepBuilder(
        val id: String, val mime: String, val bandwidth: Long, var base: String
    ) {
        val segmentList = mutableListOf<String>()
    }

    private fun buildSegmentUrls(
        rep: RepBuilder,
        media: String?,
        init: String?,
        startNumber: Int,
        duration: Long,
        timescale: Long,
        timelineCount: Int,
        timelineTimes: List<Long>,
        mpdDurationSec: Double
    ): Pair<List<String>, String?> {
        // SegmentList style
        if (rep.segmentList.isNotEmpty()) {
            val initUrl = init?.let { M3u8Parser.resolve(rep.base, it) }
            return rep.segmentList to initUrl
        }
        val mediaPattern = media ?: return emptyList<String>() to null
        val initUrl = init?.let { M3u8Parser.resolve(rep.base, it) }

        fun substitute(pattern: String, number: Int, time: Long): String {
            var p = pattern
            p = p.replace("\$RepresentationID\$", rep.id)
            p = p.replace("\$Bandwidth\$", rep.bandwidth.toString())
            p = p.replace("\$Number\$", number.toString())
            p = p.replace("\$Time\$", time.toString())
            p = p.replace("\$Timescale\$", timescale.toString())
            return M3u8Parser.resolve(rep.base, p)
        }

        val usesTime = mediaPattern.contains("\$Time\$")
        val urls = mutableListOf<String>()

        if (usesTime && timelineTimes.isNotEmpty()) {
            // timelineTimes holds the absolute start time of every segment
            timelineTimes.forEachIndexed { index, start ->
                urls.add(substitute(mediaPattern, startNumber + index, start))
            }
            return urls to initUrl
        }

        var count = timelineCount
        if (count <= 0 && duration > 0 && mpdDurationSec > 0) {
            val segSec = duration.toDouble() / timescale.toDouble()
            count = if (segSec > 0) kotlin.math.ceil(mpdDurationSec / segSec).toInt() else 0
        }
        if (count <= 0) count = 1
        for (i in 0 until count) {
            // $Time$ without SegmentTimeline: approximate regular intervals
            val time = if (usesTime) duration * i else (timelineTimes.getOrNull(i) ?: 0L)
            urls.add(substitute(mediaPattern, startNumber + i, time))
        }
        return urls to initUrl
    }

    /** Parses ISO-8601 durations like PT1H2M3.5S into seconds. */
    fun parseIsoDuration(value: String): Double {
        val m = Regex("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?").find(value) ?: return 0.0
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return h * 3600 + min * 60 + s
    }
}
