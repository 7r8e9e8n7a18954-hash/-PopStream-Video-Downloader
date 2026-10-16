package com.ultra.sniffer.core

import android.content.Context
import android.content.SharedPreferences

/** App settings (no admin panel, no kill switch — just user preferences). */
object Prefs {

    private const val FILE = "ultra_sniffer_prefs"
    private const val KEY_BLOCK_QUIC = "block_quic"
    private const val KEY_SNI_LOG = "sni_log"
    private const val KEY_AUTO_HIDE = "auto_hide_seconds"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * Block outbound QUIC (UDP/443). Many video apps use QUIC, which is
     * encrypted end-to-end and therefore invisible to the sniffer. Blocking it
     * forces them back to TCP/TLS where at least host hints are visible.
     */
    var blockQuic: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_QUIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_QUIC, value).apply()

    /** Log HTTPS host hints detected via TLS SNI (host only, no full URL). */
    var sniLogging: Boolean
        get() = prefs.getBoolean(KEY_SNI_LOG, true)
        set(value) = prefs.edit().putBoolean(KEY_SNI_LOG, value).apply()

    /** How long the floating bubble stays on screen before auto-hiding. */
    var autoHideSeconds: Int
        get() = prefs.getInt(KEY_AUTO_HIDE, 15)
        set(value) = prefs.edit().putInt(KEY_AUTO_HIDE, value).apply()
}
