package com.ultra.sniffer.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ultra.sniffer.R
import com.ultra.sniffer.core.DownloadLog
import com.ultra.sniffer.core.Prefs
import com.ultra.sniffer.core.SniffedMedia
import com.ultra.sniffer.core.SnifferEvent
import com.ultra.sniffer.core.SnifferRepository
import com.ultra.sniffer.download.DownloadService
import com.ultra.sniffer.util.MediaUrlUtils
import com.ultra.sniffer.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground service that shows the floating "Download This Video" bubble
 * (TYPE_APPLICATION_OVERLAY) whenever the sniffer detects a downloadable URL.
 * Draggable, auto-hides, and starts the download with one tap.
 */
class FloatingButtonService : Service() {

    companion object {
        private const val ACTION_START = "com.ultra.sniffer.overlay.START"
        private const val ACTION_STOP = "com.ultra.sniffer.overlay.STOP"

        fun start(context: Context) {
            val intent = Intent(context, FloatingButtonService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, FloatingButtonService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var current: SniffedMedia? = null
    private var hideJob: Job? = null
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ID_OVERLAY,
            Notifications.overlayNotification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        windowManager = getSystemService(WindowManager::class.java)
        observerJob = scope.launch {
            SnifferRepository.events.collect { event ->
                if (event is SnifferEvent.Detected) {
                    val media = event.media
                    val worthHint = !media.downloadable &&
                        MediaUrlUtils.isVideoPlatformHost(media.host)
                    if (media.downloadable || worthHint) showBubble(media)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        SnifferRepository.latestDownloadable()?.let { showBubble(it) }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------

    private fun createBubble() {
        val wm = windowManager ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 28
            y = 380
        }
        attachTouch(view, lp)
        runCatching {
            wm.addView(view, lp)
            bubble = view
        }.onFailure {
            Toast.makeText(this, "Overlay दिखाने की अनुमति नहीं है", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun showBubble(media: SniffedMedia) {
        current = media
        if (bubble == null) createBubble()
        bubble?.let { view ->
            val hint = !media.downloadable
            view.findViewById<TextView>(R.id.bubbleTitle)?.text = getString(
                if (hint) R.string.bubble_open_browser else R.string.download_this_video
            )
            view.findViewById<TextView>(R.id.bubbleSubtitle)?.text =
                if (hint) "${media.host} • video भीतर browser में" else "${media.host} • ${media.kind.label}"
            view.alpha = 0f
            view.scaleX = 0.7f
            view.scaleY = 0.7f
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        }
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(Prefs.autoHideSeconds * 1000L)
            hideBubble()
        }
    }

    private fun hideBubble() {
        val view = bubble ?: return
        bubble = null
        view.animate()
            .alpha(0f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(160)
            .withEndAction { runCatching { windowManager?.removeView(view) } }
            .start()
    }

    private fun attachTouch(view: View, lp: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > 12 || abs(dy) > 12) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        windowManager?.updateViewLayout(view, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) v.performClick()
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener { startDownload() }
        view.setOnLongClickListener {
            hideBubble()
            true
        }
    }

    private fun startDownload() {
        val media = current ?: return
        if (!media.downloadable) {
            // TLS host hint (YouTube/FB/IG…): open the site inside our browser,
            // where the actual video link becomes visible.
            val intent = Intent(this, com.ultra.sniffer.ui.BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("extra_url", MediaUrlUtils.landingPageFor(media.host))
            runCatching { startActivity(intent) }
                .onFailure {
                    Toast.makeText(this, R.string.bubble_open_failed, Toast.LENGTH_SHORT).show()
                }
            Toast.makeText(this, R.string.browser_guide_toast, Toast.LENGTH_LONG).show()
            hideBubble()
            return
        }
        DownloadLog.update(media.url, DownloadLog.Phase.QUEUED, 0, "Queue में…")
        DownloadService.enqueue(this, media)
        Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show()
        hideBubble()
    }

    override fun onDestroy() {
        hideJob?.cancel()
        observerJob?.cancel()
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        bubble = null
        scope.cancel()
        super.onDestroy()
    }
}
