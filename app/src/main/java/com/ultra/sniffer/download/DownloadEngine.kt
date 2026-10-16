package com.ultra.sniffer.download

import android.content.Context
import com.ultra.sniffer.core.MediaKind
import com.ultra.sniffer.core.SniffedMedia
import com.ultra.sniffer.util.MediaUrlUtils
import com.ultra.sniffer.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dispatches a detected media URL to the right downloader and returns the
 * finished file (inside the app cache directory; caller moves it to gallery).
 */
class DownloadEngine(private val context: Context) {

    /**
     * @param media     what to download
     * @param audioUrl  optional separate audio track (YouTube / DASH pairs) —
     *                  both tracks are downloaded and muxed into one MP4
     * @param headers   extra HTTP headers required by the CDN (Referer, UA…)
     */
    suspend fun download(
        media: SniffedMedia,
        audioUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Int, String) -> Unit
    ): File {
        val workDir = File(context.cacheDir, "dl_${System.currentTimeMillis()}").apply { mkdirs() }
        return try {
            if (audioUrl != null) {
                pair(media, audioUrl, headers, workDir, onProgress)
            } else {
                when (media.kind) {
                    MediaKind.HLS -> HlsDownloader(workDir).download(media.url, onProgress)
                    MediaKind.DASH -> DashDownloader(workDir).download(media.url, onProgress)
                    else -> progressive(media, workDir, headers, onProgress)
                }
            }
        } catch (t: Throwable) {
            runCatching { workDir.deleteRecursively() }
            throw t
        }
    }

    // ------------------------------------------------------------------
    // separate video + audio tracks -> one muxed MP4 (YouTube, DASH pairs)
    // ------------------------------------------------------------------

    private suspend fun pair(
        media: SniffedMedia,
        audioUrl: String,
        headers: Map<String, String>,
        workDir: File,
        onProgress: (Int, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val videoFile = File(workDir, "video_track.mp4")
        val audioFile = File(workDir, "audio_track.m4a")

        onProgress(0, "Video track डाउनलोड…")
        Net.download(media.url, videoFile, headers) { done, total ->
            val percent = if (total > 0) (done * 55 / total).toInt() else -1
            onProgress(percent, "Video: ${mb(done)}${if (total > 0) " / ${mb(total)}" else ""}")
        }

        onProgress(55, "Audio track डाउनलोड…")
        Net.download(audioUrl, audioFile, headers) { done, total ->
            val percent = if (total > 0) 55 + (done * 33 / total).toInt() else -1
            onProgress(percent, "Audio: ${mb(done)}${if (total > 0) " / ${mb(total)}" else ""}")
        }

        onProgress(90, "Video + Audio merge हो रहा है…")
        val name = MediaUrlUtils.guessFileName(media.url, "mp4")
            .let { if (it.contains('.')) it else "${MediaUrlUtils.sanitize(media.label.substringBeforeLast('.'))}.mp4" }
            .let { if (it.endsWith(".mp4") || it.endsWith(".m4v")) it else it.substringBeforeLast('.') + ".mp4" }
        val out = File(workDir, name)
        Merger.mux(videoFile, audioFile, out)
    }

    // ------------------------------------------------------------------
    // single-file download
    // ------------------------------------------------------------------

    private suspend fun progressive(
        media: SniffedMedia,
        workDir: File,
        headers: Map<String, String>,
        onProgress: (Int, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val fallbackExt = when (media.kind) {
            MediaKind.AUDIO -> "m4a"
            MediaKind.TS -> "ts"
            else -> "mp4"
        }
        val out = File(workDir, MediaUrlUtils.guessFileName(media.url, fallbackExt))
        onProgress(0, "डाउनलोड शुरू हो रहा है…")

        val contentType = Net.download(media.url, out, headers) { done, total ->
            if (total > 0) {
                onProgress((done * 100 / total).toInt(), "Download: ${mb(done)} / ${mb(total)}")
            } else {
                onProgress(-1, "Download: ${mb(done)}")
            }
        }

        // the URL lied about its type: it is actually a playlist
        when (MediaUrlUtils.kindFromContentType(contentType)) {
            MediaKind.HLS -> {
                out.delete()
                return@withContext HlsDownloader(workDir).download(media.url, onProgress)
            }
            MediaKind.DASH -> {
                out.delete()
                return@withContext DashDownloader(workDir).download(media.url, onProgress)
            }
            else -> Unit
        }

        val ext = out.extension.lowercase()
        if (ext == "ts" || ext == "m2ts") {
            onProgress(-1, "MP4 में remux हो रहा है…")
            val mp4 = File(workDir, out.nameWithoutExtension + ".mp4")
            return@withContext runCatching { Merger.remux(out, mp4, TrackMode.ALL) }.getOrElse { out }
        }
        out
    }

    private fun mb(bytes: Long): String = "${bytes / 1048576}MB"
}
