package com.ultra.sniffer.download

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ultra.sniffer.core.DownloadLog
import com.ultra.sniffer.core.MediaKind
import com.ultra.sniffer.core.MediaSource
import com.ultra.sniffer.core.SniffedMedia
import com.ultra.sniffer.util.MediaUrlUtils
import com.ultra.sniffer.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that processes the download queue sequentially:
 * download -> merge -> save into gallery -> notification.
 */
class DownloadService : Service() {

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_KIND = "extra_kind"
        private const val EXTRA_LABEL = "extra_label"
        private const val EXTRA_SOURCE = "extra_source"
        private const val EXTRA_AUDIO_URL = "extra_audio_url"
        private const val EXTRA_HEADERS = "extra_headers"
        private val nextId = AtomicInteger(Notifications.ID_DOWNLOAD_BASE + 1)

        /**
         * @param audioUrl separate audio track to mux into the MP4 (optional)
         * @param headers  extra HTTP headers (Referer / UA) needed by some CDNs
         */
        fun enqueue(
            context: Context,
            media: SniffedMedia,
            audioUrl: String? = null,
            headers: Map<String, String> = emptyMap()
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, media.url)
                putExtra(EXTRA_KIND, media.kind.name)
                putExtra(EXTRA_LABEL, media.label)
                putExtra(EXTRA_SOURCE, media.source.name)
                if (audioUrl != null) putExtra(EXTRA_AUDIO_URL, audioUrl)
                if (headers.isNotEmpty()) {
                    putStringArrayListExtra(
                        EXTRA_HEADERS,
                        ArrayList(headers.map { "${it.key}\u0000${it.value}" })
                    )
                }
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    /** One queued download. */
    private data class Task(
        val media: SniffedMedia,
        val audioUrl: String?,
        val headers: Map<String, String>
    )

    private val queue = ConcurrentLinkedQueue<Task>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_URL)?.let { url ->
            val media = SniffedMedia(
                url = url,
                host = MediaUrlUtils.hostOf(url),
                kind = runCatching { MediaKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "UNKNOWN") }
                    .getOrDefault(MediaKind.UNKNOWN),
                source = runCatching { MediaSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: "MANUAL") }
                    .getOrDefault(MediaSource.MANUAL),
                label = intent.getStringExtra(EXTRA_LABEL)
                    ?: MediaUrlUtils.guessDisplayName(url, MediaUrlUtils.hostOf(url))
            )
            val headers = intent.getStringArrayListExtra(EXTRA_HEADERS)
                ?.mapNotNull { entry ->
                    val split = entry.indexOf('\u0000')
                    if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
                }?.toMap().orEmpty()
            queue.add(Task(media, intent.getStringExtra(EXTRA_AUDIO_URL), headers))
            DownloadLog.update(url, DownloadLog.Phase.QUEUED, 0, "Queue में…")
        }

        ServiceCompat.startForeground(
            this,
            Notifications.ID_DOWNLOAD_BASE,
            Notifications.downloadNotification(
                this, Notifications.ID_DOWNLOAD_BASE, "Ultra Sniffer", "Downloads चालू हैं…", 0
            ),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        processQueue()
        return START_NOT_STICKY
    }

    private fun processQueue() {
        if (job?.isActive == true) return
        job = scope.launch {
            var processed = 0
            while (true) {
                val task = queue.poll() ?: break
                runDownload(task)
                processed++
            }
            if (processed == 0) delay(1200)
            withContext(Dispatchers.Main) { stopSelf() }
        }
    }

    private suspend fun runDownload(task: Task) {
        val media = task.media
        val id = nextId.getAndIncrement()
        val label = media.label
        DownloadLog.update(media.url, DownloadLog.Phase.DOWNLOADING, 0, "शुरू…")
        notify(id, label, 0, "डाउनलोड शुरू हो रहा है…")

        try {
            val engine = DownloadEngine(this)
            val file = engine.download(media, task.audioUrl, task.headers) { percent, stage ->
                val phase = if (stage.contains("merge", true) || stage.contains("MP4") || stage.contains("तैयार")) {
                    DownloadLog.Phase.MERGING
                } else {
                    DownloadLog.Phase.DOWNLOADING
                }
                DownloadLog.update(media.url, phase, percent.coerceAtLeast(0), stage)
                notify(id, label, percent, stage)
            }

            DownloadLog.update(media.url, DownloadLog.Phase.SAVING, 100, "Gallery में सेव हो रहा है…")
            notify(id, label, 100, "Gallery में सेव हो रहा है…")

            val mime = mimeFor(file)
            val baseName = MediaUrlUtils.sanitize(media.label.substringBeforeLast('.')).ifBlank { "ultra_video" }
            val displayName = "$baseName.${file.extension.ifBlank { "mp4" }}"
            val uri = MediaStoreSaver.save(this, file, displayName, mime)
            runCatching { file.parentFile?.deleteRecursively() }

            DownloadLog.update(media.url, DownloadLog.Phase.DONE, 100, "सेव हो गया: $displayName", uri.toString())
            notifyDone(label, displayName, uri, mime)
        } catch (t: Throwable) {
            val reason = DownloadErrors.friendly(t)
            DownloadLog.update(media.url, DownloadLog.Phase.ERROR, 0, reason)
            notify(id, label, -1, "❌ $reason", ongoing = false)
        }
    }

    private fun notify(id: Int, label: String, percent: Int, stage: String, ongoing: Boolean = true) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(id, Notifications.downloadNotification(this, id, label, stage, percent, ongoing = ongoing))
    }

    private fun notifyDone(label: String, displayName: String, uri: Uri, mime: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val id = Notifications.ID_DOWNLOAD_BASE + 900 + (displayName.hashCode() and 0xFF)
        nm.notify(
            id,
            Notifications.downloadNotification(
                this, id, "Download पूरा ✅", displayName, null, uri, mime, ongoing = false
            )
        )
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
