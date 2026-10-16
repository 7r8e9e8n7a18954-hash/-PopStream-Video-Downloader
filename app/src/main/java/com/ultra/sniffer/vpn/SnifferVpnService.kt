package com.ultra.sniffer.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ultra.sniffer.core.Prefs
import com.ultra.sniffer.core.SnifferRepository
import com.ultra.sniffer.overlay.FloatingButtonService
import com.ultra.sniffer.util.Notifications

/**
 * The heart of the app: a global VpnService that routes every app's traffic
 * through a userspace relay (TcpEngine / UdpEngine) while sniffing it for
 * video URLs.
 *
 * The VPN is IPv4-only by design: IPv6 traffic bypasses the tunnel on
 * purpose, which keeps the packet handling simple and robust.
 */
class SnifferVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ultra.sniffer.action.START"
        const val ACTION_STOP = "com.ultra.sniffer.action.STOP"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, SnifferVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, SnifferVpnService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var relay: IpRelay? = null
    private var readerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SnifferRepository.notifyVpnState(false)
            stopSelf()
            return START_NOT_STICKY
        }
        startSniffer()
        return START_NOT_STICKY
    }

    private fun startSniffer() {
        if (isRunning) return

        // Foreground first (Android 12+ requires this quickly after startService)
        val fgsTypes = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        val started = runCatching {
            ServiceCompat.startForeground(
                this, Notifications.ID_VPN, Notifications.snifferNotification(this), fgsTypes
            )
        }.isSuccess
        if (!started) {
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    Notifications.ID_VPN,
                    Notifications.snifferNotification(this),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                )
            }
        }

        val builder = Builder()
            .setSession("Ultra Floating Video Sniffer")
            .setMtu(1500)
            .addAddress("10.111.222.2", 32)
            .addRoute("0.0.0.0", 0)

        // Our own traffic must never enter the tunnel (avoids loops while downloading)
        runCatching { builder.addDisallowedApplication(packageName) }

        val pfd = runCatching { builder.establish() }.getOrNull()
        if (pfd == null) {
            stopSelf()
            return
        }
        tun = pfd

        val sniffer = TrafficSniffer(
            emit = { SnifferRepository.add(it) },
            sniLogging = { Prefs.sniLogging }
        )
        val ipRelay = IpRelay(this, pfd, sniffer) { Prefs.blockQuic }
        relay = ipRelay
        readerThread = Thread(ipRelay, "ultra-ip-relay").also { it.start() }

        isRunning = true
        SnifferRepository.notifyVpnState(true)

        if (Settings.canDrawOverlays(this)) {
            FloatingButtonService.start(this)
        }
    }

    override fun onRevoke() {
        SnifferRepository.notifyVpnState(false)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        relay?.stop()
        runCatching { tun?.close() }
        readerThread?.join(1500)
        tun = null
        relay = null
        readerThread = null
        SnifferRepository.notifyVpnState(false)
        FloatingButtonService.stop(this)
        super.onDestroy()
    }
}
