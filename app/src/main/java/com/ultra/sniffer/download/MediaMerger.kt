package com.ultra.sniffer.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

enum class TrackMode { ALL, VIDEO_ONLY, AUDIO_ONLY }

/**
 * Native (no external binary) remux / mux engine built on MediaExtractor +
 * MediaMuxer. Handles the common cases produced by HLS/DASH sniffing:
 *
 *  - concatenated MPEG-TS segments  -> single MP4
 *  - concatenated fMP4 (DASH init + segments) -> single MP4
 *  - separate video and audio tracks -> muxed single MP4
 *
 * Codecs that MediaMuxer cannot write (e.g. AV1 in TS) throw, and the caller
 * falls back to saving the raw file / using FFmpeg when present.
 */
object MediaMerger {

    private const val SAMPLE_BUFFER = 2 * 1024 * 1024

    fun remux(input: File, output: File, mode: TrackMode = TrackMode.ALL): File {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val classIndices = selectTracks(extractor, mode)
            if (classIndices.isEmpty()) throw IllegalArgumentException("No usable track in ${input.name}")

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var orientation = 0
            val sources = mutableListOf<TrackSource>()
            for (idx in classIndices) {
                val format = extractor.getTrackFormat(idx)
                val dst = muxer.addTrack(format)
                sources.add(TrackSource(extractor, idx, dst))
                if (mode != TrackMode.AUDIO_ONLY) {
                    orientation = runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }
                        .getOrDefault(orientation)
                }
            }
            if (orientation != 0) muxer.setOrientationHint(orientation)

            muxer.start()
            interleavedCopy(sources, muxer)
            muxer.stop()
            return output
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    fun mux(videoFile: File, audioFile: File, output: File): File {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val vTrack = selectTracks(videoExtractor, TrackMode.VIDEO_ONLY).firstOrNull()
                ?: throw IllegalArgumentException("No video track in ${videoFile.name}")
            val aTrack = selectTracks(audioExtractor, TrackMode.AUDIO_ONLY).firstOrNull()
                ?: throw IllegalArgumentException("No audio track in ${audioFile.name}")

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vFormat = videoExtractor.getTrackFormat(vTrack)
            val aFormat = audioExtractor.getTrackFormat(aTrack)
            val vDst = muxer.addTrack(vFormat)
            val aDst = muxer.addTrack(aFormat)
            val orientation = runCatching { vFormat.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
            if (orientation != 0) muxer.setOrientationHint(orientation)

            muxer.start()
            interleavedCopy(
                listOf(
                    TrackSource(videoExtractor, vTrack, vDst),
                    TrackSource(audioExtractor, aTrack, aDst)
                ),
                muxer
            )
            muxer.stop()
            return output
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    // ------------------------------------------------------------------

    private fun selectTracks(extractor: MediaExtractor, mode: TrackMode): List<Int> {
        val video = mutableListOf<Int>()
        val audio = mutableListOf<Int>()
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") -> video.add(i)
                mime.startsWith("audio/") -> audio.add(i)
            }
        }
        return when (mode) {
            TrackMode.VIDEO_ONLY -> video.take(1)
            TrackMode.AUDIO_ONLY -> audio.sortedByDescending { mimeOf(extractor, it) == "audio/mp4a-latm" }.take(1)
            TrackMode.ALL -> buildList {
                video.take(1).forEach { add(it) }
                audio.sortedByDescending { mimeOf(extractor, it) == "audio/mp4a-latm" }.take(1).forEach { add(it) }
            }
        }
    }

    private fun mimeOf(extractor: MediaExtractor, track: Int): String =
        runCatching { extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) ?: "" }.getOrDefault("")

    /** Reads samples from all sources and writes them to the muxer in PTS order. */
    private fun interleavedCopy(sources: List<TrackSource>, muxer: MediaMuxer) {
        while (true) {
            var best: TrackSource? = null
            var bestPts = Long.MAX_VALUE
            for (s in sources) {
                val pts = s.peekPts()
                if (pts < bestPts) {
                    bestPts = pts
                    best = s
                }
            }
            val source = best ?: break
            source.writeNext(muxer)
        }
    }

    /** Prefetch-capable sample source for one track. */
    private class TrackSource(
        private val extractor: MediaExtractor,
        srcTrack: Int,
        val muxerTrack: Int
    ) {
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER)
        private val info = MediaCodec.BufferInfo()
        private var haveSample = false
        private var eos = false
        private var firstPts = -1L

        init {
            extractor.selectTrack(srcTrack)
        }

        fun peekPts(): Long {
            if (!haveSample && !eos) fetch()
            return if (haveSample) info.presentationTimeUs else Long.MAX_VALUE
        }

        fun writeNext(muxer: MediaMuxer) {
            if (!haveSample) return
            if (firstPts < 0) firstPts = info.presentationTimeUs
            info.presentationTimeUs -= firstPts
            if (info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                muxer.writeSampleData(muxerTrack, buffer, info)
            }
            haveSample = false
            extractor.advance()
        }

        private fun fetch() {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                eos = true
                haveSample = false
                return
            }
            val flags = extractor.sampleFlags
            info.set(
                0,
                size,
                extractor.sampleTime,
                if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            )
            haveSample = true
        }
    }
}
