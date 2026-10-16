package com.ultra.sniffer.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.ultra.sniffer.R
import com.ultra.sniffer.ui.MainActivity

/** Notification channels + builders. */
object Notifications {

    const val CHANNEL_VPN = "channel_vpn"
    const val CHANNEL_OVERLAY = "channel_overlay"
    const val CHANNEL_DOWNLOADS = "channel_downloads"

    const val ID_VPN = 101
    const val ID_OVERLAY = 102
    const val ID_DOWNLOAD_BASE = 200

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VPN, "Sniffer VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "VPN / sniffer status"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OVERLAY, "Floating button", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Floating download button"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Video download progress"
            }
        )
    }

    private fun appIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun snifferNotification(
        context: Context,
        text: String = "Traffic monitor चालू है — video URLs अपने-आप detect होंगे"
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_stat_sniffer)
            .setContentTitle("Ultra Sniffer active")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(appIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun overlayNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_stat_sniffer)
            .setContentTitle("Floating button ready")
            .setContentText("Video मिलते ही \"Download This Video\" popup दिखेगा")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(appIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun downloadNotification(
        context: Context,
        id: Int,
        title: String,
        text: String,
        progress: Int? = null,
        contentUri: Uri? = null,
        mime: String = "video/mp4",
        ongoing: Boolean = progress != null
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_sniffer)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        if (progress != null) {
            builder.setProgress(100, progress.coerceIn(0, 100), progress < 0)
        }

        val contentIntent = if (contentUri != null) {
            PendingIntent.getActivity(
                context,
                id,
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(contentUri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            appIntent(context)
        }
        builder.setContentIntent(contentIntent)
        return builder.build()
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }
}
