package com.ultra.sniffer.ui

import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultra.sniffer.R
import com.ultra.sniffer.core.MediaKind
import com.ultra.sniffer.core.MediaSource
import com.ultra.sniffer.core.SniffedMedia
import com.ultra.sniffer.core.SnifferRepository
import com.ultra.sniffer.download.DownloadService
import com.ultra.sniffer.download.MediaResolver
import com.ultra.sniffer.download.ResolveResult
import com.ultra.sniffer.download.ResolvedStream
import com.ultra.sniffer.util.MediaUrlUtils
import kotlinx.coroutines.launch

/**
 * Shared "paste a link → get real video options → download" flow used by the
 * main screen, the in-app browser and the share intent.
 */
object ResolverFlow {

    /** Resolves [rawUrl] with a small progress dialog, then calls [then]. */
    fun resolveWithUi(
        activity: AppCompatActivity,
        rawUrl: String,
        then: (ResolveResult, String) -> Unit
    ) {
        val url = MediaResolver.normalize(rawUrl)
        if (!url.startsWith("http")) {
            Toast.makeText(activity, activity.getString(R.string.invalid_url), Toast.LENGTH_SHORT).show()
            return
        }
        val progress = ProgressBar(activity).apply {
            val pad = (28 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.resolving_title)
            .setMessage(url.take(160))
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()

        activity.lifecycleScope.launch {
            val result = try {
                MediaResolver.resolve(url)
            } catch (t: Throwable) {
                ResolveResult.Failed(t.message ?: activity.getString(R.string.resolve_failed), url)
            }
            runCatching { dialog.dismiss() }
            then(result, url)
        }
    }

    /** One tap: resolve → download directly, or show the quality chooser. */
    fun resolveAndOffer(activity: AppCompatActivity, rawUrl: String) {
        resolveWithUi(activity, rawUrl) { result, url -> handleResult(activity, result, url) }
    }

    fun handleResult(activity: AppCompatActivity, result: ResolveResult, url: String) {
        when (result) {
            is ResolveResult.Direct -> downloadMedia(activity, result.media)
            is ResolveResult.Options -> showOptions(activity, result.title, result.streams)
            is ResolveResult.Failed -> showFailed(activity, url, result.message)
        }
    }

    /** Starts a download for one chosen option. */
    fun downloadStream(activity: AppCompatActivity, title: String, stream: ResolvedStream) {
        val media = toMedia(title, stream)
        SnifferRepository.add(media)
        DownloadService.enqueue(activity, media, stream.audioUrl, stream.headers)
        Toast.makeText(activity, activity.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
    }

    /** Quality / manifest chooser. */
    fun showOptions(activity: AppCompatActivity, title: String, streams: List<ResolvedStream>) {
        if (streams.isEmpty()) {
            showFailed(activity, "", activity.getString(R.string.resolve_failed))
            return
        }
        if (streams.size == 1) {
            downloadStream(activity, title, streams.first())
            return
        }
        val labels = streams.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(title.ifBlank { activity.getString(R.string.choose_stream_title) })
            .setItems(labels) { _, index -> downloadStream(activity, title, streams[index]) }
            .setNeutralButton(R.string.add_all_to_list) { _, _ ->
                streams.forEach { SnifferRepository.add(toMedia(title, it)) }
                Toast.makeText(activity, activity.getString(R.string.added_to_list), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Nothing resolved: explain + offer "download anyway" and the browser. */
    fun showFailed(activity: AppCompatActivity, url: String, message: String) {
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.resolve_failed)
            .setMessage(message)
            .setNeutralButton(R.string.open_in_app_browser) { _, _ ->
                runCatching { activity.startActivity(BrowserActivity.intent(activity, url)) }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (url.startsWith("http")) {
            builder.setPositiveButton(R.string.download_anyway) { _, _ ->
                downloadMedia(activity, SniffedMedia.fromUrl(url, MediaSource.MANUAL))
            }
        }
        builder.show()
    }

    private fun downloadMedia(activity: AppCompatActivity, media: SniffedMedia) {
        SnifferRepository.add(media)
        DownloadService.enqueue(activity, media)
        Toast.makeText(activity, activity.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
    }

    /** Builds the list entry / file name for one option. */
    fun toMedia(title: String, stream: ResolvedStream): SniffedMedia {
        val tag = stream.tag.ifBlank { MediaUrlUtils.extOf(stream.url).ifBlank { "mp4" } }
        val base = MediaUrlUtils.sanitize(title).take(70).ifBlank {
            MediaUrlUtils.guessDisplayName(stream.url, MediaUrlUtils.hostOf(stream.url)).substringBeforeLast('.')
        }
        val ext = when (stream.kind) {
            MediaKind.AUDIO -> MediaUrlUtils.extOf(stream.url).ifBlank { "m4a" }
            else -> "mp4"
        }
        val name = "${base.ifBlank { "ultra_video" }}_$tag.$ext"
        return SniffedMedia.fromUrl(stream.url, MediaSource.MANUAL)
            .copy(kind = stream.kind, label = name)
    }
}
