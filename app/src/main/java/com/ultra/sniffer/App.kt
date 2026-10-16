package com.ultra.sniffer

import android.app.Application
import com.ultra.sniffer.core.Prefs
import com.ultra.sniffer.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    /** Long-lived scope for background work that must outlive activities. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notifications.createChannels(this)
    }
}
