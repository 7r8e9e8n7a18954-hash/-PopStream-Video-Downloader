package com.ultra.sniffer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultra.sniffer.R
import com.ultra.sniffer.core.MediaKind
import com.ultra.sniffer.core.MediaSource
import com.ultra.sniffer.core.SniffedMedia
import com.ultra.sniffer.core.SnifferRepository
import com.ultra.sniffer.databinding.ActivityBrowserBinding
import com.ultra.sniffer.download.DownloadService
import com.ultra.sniffer.util.MediaUrlUtils
import kotlinx.coroutines.launch

/**
 * Built-in browser that can see *inside* HTTPS traffic.
 *
 * Android's VPN capture stops at the TLS layer, so encrypted sites
 * (YouTube, Facebook, Instagram, …) can never reveal their real stream URLs
 * to a packet sniffer. Loading the page inside our own WebView solves that:
 * every media request the page makes is visible to the app, over HTTP or
 * HTTPS alike, and lands in the same detection list + floating bubble.
 *
 * Detection sources:
 *   1. WebViewClient.shouldInterceptRequest() — every resource URL the page loads
 *   2. injected JS hooks on XMLHttpRequest / fetch / <video> / <source>
 *   3. the Resource Timing API (catches manifests fetched by MSE players)
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val HOME_URL = "https://m.youtube.com/"
        private const val JS_BRIDGE = "UltraBridge"

        fun intent(context: Context, url: String? = null): Intent =
            Intent(context, BrowserActivity::class.java).apply { url?.let { putExtra(EXTRA_URL, it) } }

        /** Injected into every page: hooks XHR, fetch, <video> and resource timing. */
        private val JS_HOOKS = """
            (function(){
              try {
                if (window.__ultraHooked) return;
                window.__ultraHooked = true;
                function report(u){
                  try { u = String(u || ''); if (u.length < 12) return;
                        UltraBridge.onMediaUrl(u, document.title || ''); } catch(e){}
                }
                var re = /\.(m3u8|mpd|mp4|m4v|m4s|ts|webm|mkv|mov|flv|m4a|mp3|aac)(\?|#|${'$'})/i;
                window.__ultraLooks = function(u){
                  try {
                    u = String(u || '');
                    if (!/^https?:/i.test(u)) return false;
                    if (re.test(u)) return true;
                    return /videoplayback|mime=video|\/hls\/|\/dash\/|playlist\.m3u8|manifest\.mpd/i.test(u);
                  } catch(e){ return false; }
                };
                var xhrOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(m, u){
                  try { if (window.__ultraLooks(u)) report(u); } catch(e){}
                  return xhrOpen.apply(this, arguments);
                };
                if (window.fetch) {
                  var origFetch = window.fetch;
                  window.fetch = function(input){
                    try {
                      var u = (typeof input === 'string') ? input : (input && input.url);
                      if (window.__ultraLooks(u)) report(u);
                    } catch(e){}
                    return origFetch.apply(this, arguments);
                  };
                }
                function scanDom(){
                  try {
                    var els = document.querySelectorAll('video,source,audio,a');
                    for (var i = 0; i < els.length; i++) {
                      var el = els[i];
                      var s = el.currentSrc || el.src || el.getAttribute('data-src') || el.href || '';
                      if (window.__ultraLooks(s)) report(s);
                    }
                  } catch(e){}
                }
                function scanPerf(){
                  try {
                    var es = performance.getEntriesByType('resource') || [];
                    for (var i = 0; i < es.length; i++) {
                      if (window.__ultraLooks(es[i].name)) report(es[i].name);
                    }
                  } catch(e){}
                }
                setInterval(function(){ scanDom(); scanPerf(); }, 2500);
                document.addEventListener('click', function(){ setTimeout(function(){ scanDom(); scanPerf(); }, 900); }, true);
                document.addEventListener('play', function(){ scanDom(); }, true);
              } catch(e){}
            })();
        """.trimIndent()

        private const val JS_PERF_SCAN = "(function(){try{" +
            "var e=performance.getEntriesByType('resource')||[];" +
            "for(var i=0;i<e.length;i++){UltraBridge.onMediaUrl(e[i].name, document.title||'');}" +
            "}catch(x){}})()"
    }

    private lateinit var binding: ActivityBrowserBinding
    private val detected = LinkedHashSet<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        binding.webView.addJavascriptInterface(Bridge(), JS_BRIDGE)
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.url?.toString()?.let { report(it, binding.webView.title) }
                return null // never block the page
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                view ?: return
                view.evaluateJavascript(JS_HOOKS, null)
                view.evaluateJavascript(JS_PERF_SCAN, null)
            }
        }

        binding.btnGo.setOnClickListener { load(binding.etUrl.text.toString()) }
        binding.etUrl.setOnEditorActionListener { _, _, _ ->
            load(binding.etUrl.text.toString())
            true
        }
        binding.chipDetected.setOnClickListener { showDetectedDialog() }

        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SnifferRepository.media.collect { refreshChip(it) }
            }
        }

        val start = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http") } ?: HOME_URL
        binding.etUrl.setText(start)
        binding.webView.loadUrl(start)
    }

    override fun onDestroy() {
        runCatching { binding.webView.destroy() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun load(raw: String) {
        var url = raw.trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http")) url = "https://$url"
        binding.etUrl.setText(url)
        binding.webView.loadUrl(url)
    }

    /** Registers a media URL coming from the WebView (already filtered). */
    private fun report(url: String, pageTitle: String? = null) {
        if (url.length < 12 || !url.startsWith("http")) return
        val kind = MediaUrlUtils.kindOf(url)
        if (kind == MediaKind.UNKNOWN && !MediaUrlUtils.isLikelyMedia(url)) return
        val media = SniffedMedia.fromUrl(url, MediaSource.BROWSER).let { base ->
            // A real page title makes a much nicer file name than "videoplayback"
            val title = MediaUrlUtils.sanitize(pageTitle.orEmpty())
            if (title.isBlank() || title.length < 3) base else base.copy(label = "${title.take(80)}.${base.label.substringAfterLast('.', "mp4")}")
        }
        if (SnifferRepository.add(media)) {
            synchronized(detected) { detected.add(url) }
            runOnUiThread { refreshChip(SnifferRepository.media.value) }
        }
    }

    private fun refreshChip(items: List<SniffedMedia>) {
        val shown = items.filter { it.source == MediaSource.BROWSER || it.downloadable }
        if (shown.isEmpty()) {
            binding.chipDetected.visibility = View.GONE
            return
        }
        binding.chipDetected.visibility = View.VISIBLE
        binding.chipDetected.text = getString(R.string.browser_detected_count, shown.size)
    }

    private fun showDetectedDialog() {
        val items = SnifferRepository.media.value.filter { it.downloadable }.take(15)
        if (items.isEmpty()) return
        val labels = items.map { "${it.kind.label} • ${it.label}\n${it.host}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_detected_title)
            .setItems(labels) { _, index ->
                val media = items[index]
                DownloadService.enqueue(this, media)
                android.widget.Toast.makeText(
                    this, getString(R.string.download_started), android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** JS -> Kotlin bridge used by the injected hooks. */
    private inner class Bridge {
        @JavascriptInterface
        fun onMediaUrl(url: String?, title: String?) {
            url?.let { report(it, title) }
        }
    }
}
