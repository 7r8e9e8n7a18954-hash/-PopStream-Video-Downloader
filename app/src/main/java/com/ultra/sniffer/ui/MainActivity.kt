package com.ultra.sniffer.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultra.sniffer.R
import com.ultra.sniffer.core.DownloadLog
import com.ultra.sniffer.core.MediaSource
import com.ultra.sniffer.core.Prefs
import com.ultra.sniffer.core.SniffedMedia
import com.ultra.sniffer.core.SnifferEvent
import com.ultra.sniffer.core.SnifferRepository
import com.ultra.sniffer.databinding.ActivityMainBinding
import com.ultra.sniffer.download.DownloadService
import com.ultra.sniffer.overlay.FloatingButtonService
import com.ultra.sniffer.vpn.SnifferVpnService
import kotlinx.coroutines.launch

/**
 * Single screen: "Start Sniffer & VPN" button, live status, settings toggles
 * and the list of everything the sniffer has detected.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaAdapter
    private var pendingStart = false
    private var overlayAskedOnce = false

    // ------------------------------------------------------------------
    // Permission wizard
    // ------------------------------------------------------------------

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { continueWizard() }

    private val vpnPrepareLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                finishStart()
            } else {
                pendingStart = false
                toast(getString(R.string.vpn_denied))
                refreshStatus()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { continueWizard() }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { continueWizard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MediaAdapter(::onMediaClicked)
        binding.rvMedia.layoutManager = LinearLayoutManager(this)
        binding.rvMedia.adapter = adapter

        binding.btnToggle.setOnClickListener { onToggleClicked() }
        binding.btnAddUrl.setOnClickListener { showManualUrlDialog() }
        binding.btnClear.setOnClickListener { SnifferRepository.clear() }
        binding.btnBrowser.setOnClickListener {
            runCatching { startActivity(BrowserActivity.intent(this)) }
        }

        binding.swBlockQuic.isChecked = Prefs.blockQuic
        binding.swBlockQuic.setOnCheckedChangeListener { _, checked -> Prefs.blockQuic = checked }
        binding.swSni.isChecked = Prefs.sniLogging
        binding.swSni.setOnCheckedChangeListener { _, checked -> Prefs.sniLogging = checked }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SnifferRepository.media.collect { list ->
                        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        adapter.submit(list, DownloadLog.state.value)
                        refreshStatus()
                    }
                }
                launch {
                    DownloadLog.state.collect { logs ->
                        adapter.submit(SnifferRepository.media.value, logs)
                    }
                }
                launch {
                    SnifferRepository.events.collect { event ->
                        if (event is SnifferEvent.VpnState) refreshStatus()
                    }
                }
            }
        }

        handleShareIntent(intent)
        refreshStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (pendingStart) continueWizard()
    }

    // ------------------------------------------------------------------
    // Start / stop flow
    // ------------------------------------------------------------------

    private fun onToggleClicked() {
        if (SnifferVpnService.isRunning) {
            SnifferVpnService.stop(this)
            FloatingButtonService.stop(this)
            binding.btnToggle.postDelayed({ refreshStatus() }, 800)
        } else {
            overlayAskedOnce = false
            pendingStart = true
            continueWizard()
        }
    }

    private fun continueWizard() {
        if (!pendingStart) return
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!Settings.canDrawOverlays(this) && !overlayAskedOnce) {
            overlayAskedOnce = true
            toast(getString(R.string.overlay_ask))
            runCatching {
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        val prepare = try {
            android.net.VpnService.prepare(this)
        } catch (t: Throwable) {
            null
        }
        if (prepare != null) {
            vpnPrepareLauncher.launch(prepare)
            return
        }
        finishStart()
    }

    private fun finishStart() {
        pendingStart = false
        SnifferVpnService.start(this)
        toast(getString(R.string.sniffer_starting))
        binding.btnToggle.postDelayed({ refreshStatus() }, 1000)
    }

    private fun refreshStatus() {
        val running = SnifferVpnService.isRunning
        binding.btnToggle.text = getString(if (running) R.string.stop_sniffer else R.string.start_sniffer)
        binding.tvStatus.text = getString(if (running) R.string.status_running else R.string.status_stopped)
        val overlayLabel = if (Settings.canDrawOverlays(this)) {
            getString(R.string.overlay_granted)
        } else {
            getString(R.string.overlay_missing)
        }
        binding.tvStatusSub.text =
            "$overlayLabel • ${getString(R.string.detected_count, SnifferRepository.media.value.size)}"
    }

    // ------------------------------------------------------------------
    // Share intent (share any link to Ultra Sniffer)
    // ------------------------------------------------------------------

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = extractUrl(text) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shared_link_title)
            .setMessage(url)
            .setPositiveButton(R.string.download) { _, _ -> ResolverFlow.resolveAndOffer(this, url) }
            .setNegativeButton(R.string.add_to_list) { _, _ ->
                SnifferRepository.add(SniffedMedia.fromUrl(url, MediaSource.SHARE))
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')

    // ------------------------------------------------------------------
    // Manual URL / item actions
    // ------------------------------------------------------------------

    private fun showManualUrlDialog() {
        val edit = EditText(this).apply {
            hint = getString(R.string.url_hint)
            setPadding(56, 32, 56, 12)
        }
        val clip = runCatching {
            getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        if (!clip.isNullOrBlank() && clip.startsWith("http")) edit.setText(clip)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.paste_url_title)
            .setView(edit)
            .setPositiveButton(R.string.download) { _, _ ->
                val url = edit.text.toString().trim()
                if (url.startsWith("http") || url.contains('.')) {
                    ResolverFlow.resolveAndOffer(this, url)
                } else {
                    toast(getString(R.string.invalid_url))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun enqueueDownload(url: String, media: SniffedMedia? = null) {
        val target = media ?: SniffedMedia.fromUrl(url, MediaSource.MANUAL)
        SnifferRepository.add(target)
        DownloadService.enqueue(this, target)
        toast(getString(R.string.download_started))
    }

    private fun onMediaClicked(media: SniffedMedia) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (media.downloadable) {
            actions.add(getString(R.string.download) to { enqueueDownload(media.url, media) })
            actions.add(getString(R.string.find_quality_options) to {
                ResolverFlow.resolveAndOffer(this, media.url)
            })
            actions.add(getString(R.string.open_browser) to {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(media.url))) }
            })
        } else {
            // Encrypted traffic: we only know the host, so let the resolver
            // find the actual video on it (homepage scan / embedded player).
            actions.add(getString(R.string.find_video_on_host) to {
                ResolverFlow.resolveAndOffer(this, "https://${media.host}")
            })
            actions.add(getString(R.string.open_in_app_browser) to {
                runCatching { startActivity(BrowserActivity.intent(this, "https://${media.host}")) }
            })
            actions.add(getString(R.string.paste_exact_url) to { showManualUrlDialog() })
        }
        actions.add(getString(R.string.copy_url) to { copyToClipboard(media.url) })
        actions.add(getString(R.string.details) to { showDetails(media) })

        MaterialAlertDialogBuilder(this)
            .setTitle(media.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .show()
    }

    private fun showDetails(media: SniffedMedia) {
        val message = """
            URL: ${media.url}

            Host: ${media.host}
            Type: ${media.kind.label}
            Source: ${media.source.label}
        """.trimIndent()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.details)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Ultra Sniffer", text))
            toast(getString(R.string.copied))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
