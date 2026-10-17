package com.ultra.sniffer.download

import com.ultra.sniffer.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads a DASH (MPD) stream: best video representation + best audio
 * representation, then muxes them into ONE MP4.
 *
 * With FFmpeg present the two downloaded tracks are muxed in a single
 * stream-copy pass (no quality loss). Without FFmpeg the pure-Android
 * MediaMuxer engine does the same job step by step.
 */
class DashDownloader(private val workDir: File) {

    suspend fun download(url: String, onProgress: (Int, String) -> Unit): File =
        withContext(Dispatchers.IO) {
            onProgress(0, "DASH manifest पढ़ा जा रहा है…")
            val xml = Net.getText(url)
            val reps = MpdParser.parse(xml, url)
            if (reps.isEmpty()) throw IOException("DASH manifest में कोई representation नहीं मिली")

            val video = reps.filter { it.mime.startsWith("video/") }.maxByOrNull { it.bandwidth }
            val audio = reps.filter { it.mime.startsWith("audio/") }.maxByOrNull { it.bandwidth }

            // Single-file representation (SegmentBase) — just download it.
            if (video == null || video.segmentUrls.isEmpty()) {
                val single = video?.singleFileUrl ?: reps.firstOrNull { it.singleFileUrl != null }?.singleFileUrl
                    ?: throw IOException("DASH: video track नहीं मिला")
                onProgress(0, "Single DASH file download हो रही है…")
                val out = File(workDir, "dash_single.mp4")
                Net.download(single, out) { done, total ->
                    if (total > 0) onProgress((done * 100 / total).toInt(), "Download हो रहा है…")
                    else onProgress(-1, "Download हो रहा है…")
                }
                val ext = out.extension.lowercase()
                return@withContext when (ext) {
                    "ts", "m2ts" -> runCatching {
                        Merger.remux(out, File(workDir, "final.mp4"), TrackMode.ALL)
                    }.getOrElse { out }
                    else -> out
                }
            }

            if (audio == null) {
                val videoTrack = downloadTrack(video, File(workDir, "dash_video"), "video", 0, 100, onProgress)
                onProgress(-1, "Video track को MP4 में remux किया जा रहा है…")
                return@withContext Merger.remux(videoTrack, File(workDir, "final.mp4"), TrackMode.ALL)
            }

            // Video + audio representations in parallel (2x faster on DASH)
            val videoPct = AtomicInteger(0)
            val audioPct = AtomicInteger(0)
            val (videoTrack, audioTrack) = coroutineScope {
                val videoJob = async(Dispatchers.IO) {
                    downloadTrack(video, File(workDir, "dash_video"), "video", 0, 100) { pct, message ->
                        if (pct in 0..100) videoPct.set(pct)
                        onProgress((videoPct.get() * 55 + audioPct.get() * 45) / 100, message)
                    }
                }
                val audioJob = async(Dispatchers.IO) {
                    downloadTrack(audio, File(workDir, "dash_audio"), "audio", 0, 100) { pct, message ->
                        if (pct in 0..100) audioPct.set(pct)
                        onProgress((videoPct.get() * 55 + audioPct.get() * 45) / 100, message)
                    }
                }
                videoJob.await() to audioJob.await()
            }

            onProgress(-1, "FFmpeg से Video + Audio merge हो रहे हैं…")
            // One pass, both tracks, stream copy — the sweet spot.
            if (FfmpegMerger.available) {
                val finalFile = File(workDir, "final.mp4")
                if (FfmpegMerger.mux(videoTrack, audioTrack, finalFile) &&
                    finalFile.exists() && finalFile.length() > 0
                ) {
                    return@withContext finalFile
                }
                runCatching { finalFile.delete() }
            }

            // Fallback: remux each track, then mux with MediaMuxer.
            onProgress(-1, "Video + Audio merge (fallback engine)…")
            val videoOnly = Merger.remux(videoTrack, File(workDir, "video_only.mp4"), TrackMode.VIDEO_ONLY)
            val audioOnly = Merger.remux(audioTrack, File(workDir, "audio_only.mp4"), TrackMode.AUDIO_ONLY)
            Merger.mux(videoOnly, audioOnly, File(workDir, "final.mp4"))
        }

    /**
     * Downloads one representation (init segment + every media segment) and
     * concatenates it into a single playable track file.
     */
    private suspend fun downloadTrack(
        rep: DashRep,
        dir: File,
        label: String,
        startPct: Int,
        endPct: Int,
        onProgress: (Int, String) -> Unit
    ): File {
        dir.mkdirs()
        val parts = mutableListOf<File>()

        rep.initUrl?.let { initUrl ->
            val initFile = File(dir, "init_00000.mp4")
            if (!initFile.exists() || initFile.length() == 0L) Net.download(initUrl, initFile)
            parts.add(initFile)
        }

        val urls = rep.segmentUrls
        if (urls.isEmpty()) {
            val single = rep.singleFileUrl ?: throw IOException("DASH $label track खाली है")
            val file = File(dir, "single.mp4")
            Net.download(single, file)
            return file
        }

        val semaphore = Semaphore(Net.SEMAPHORE_PERMITS)
        val done = AtomicInteger(0)
        val total = urls.size
        val files = coroutineScope {
            urls.mapIndexed { index, u ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val file = File(dir, "seg_${index.toString().padStart(5, '0')}.m4s")
                        if (!file.exists() || file.length() == 0L) Net.download(u, file)
                        val completed = done.incrementAndGet()
                        onProgress(startPct + (endPct - startPct) * completed / total, "$label chunks: $completed/$total")
                        file
                    }
                }
            }.awaitAll()
        }
        parts.addAll(files)

        val merged = File(dir, "merged.mp4")
        merged.outputStream().buffered(256 * 1024).use { output ->
            for (part in parts) {
                part.inputStream().buffered(256 * 1024).use { input ->
                    input.copyTo(output, 256 * 1024)
                }
            }
        }
        return merged
    }
}
