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

    suspend fun download(media: SniffedMedia, onProgress: (Int, String) -> Unit): File {
        val workDir = File(context.cacheDir, "dl_${System.currentTimeMillis()}").apply { mkdirs() }
        return try {
            when (media.kind) {
                MediaKind.HLS -> HlsDownloader(workDir).download(media.url, onProgress)
                MediaKind.DASH -> DashDownloader(workDir).download(media.url, onProgress)
                else -> progressive(media, workDir, onProgress)
            }
        } catch (t: Throwable) {
            runCatching { workDir.deleteRecursively() }
            throw t
        }
    }

    private suspend fun progressive(
        media: SniffedMedia,
        workDir: File,
        onProgress: (Int, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val out = File(workDir, MediaUrlUtils.guessFileName(media.url, "mp4"))
        onProgress(0, "डाउनलोड शुरू हो रहा है…")

        val contentType = Net.download(media.url, out) { done, total ->
            if (total > 0) {
                onProgress((done * 100 / total).toInt(), "Download: ${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB")
            } else {
                onProgress(-1, "Download: ${done / 1024 / 1024}MB")
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
}
