package com.ultra.sniffer.download

import com.ultra.sniffer.core.MediaKind

/**
 * One concrete, downloadable option produced by [MediaResolver].
 *
 * @param url       video (or audio-only) stream URL
 * @param kind      container type — decides which downloader runs
 * @param label     human readable line for the chooser, e.g. "1080p • H.264 + audio • 45 MB"
 * @param audioUrl  separate audio track that must be muxed into the MP4 (YouTube / DASH style)
 * @param quality   height in pixels (audio: kbps) — used for sorting
 * @param sizeBytes -1 when unknown
 * @param headers   extra HTTP headers required by the CDN
 * @param tag       short quality tag used in the saved file name ("1080p", "audio", …)
 */
data class ResolvedStream(
    val url: String,
    val kind: MediaKind = MediaKind.MP4,
    val label: String,
    val audioUrl: String? = null,
    val quality: Int = 0,
    val sizeBytes: Long = -1L,
    val headers: Map<String, String> = emptyMap(),
    val tag: String = ""
) {
    val isPair: Boolean get() = audioUrl != null

    companion object {
        /** "45 MB" / "1.2 GB" — empty when the size is unknown. */
        fun sizeLabel(bytes: Long): String = when {
            bytes <= 0 -> ""
            bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1073741824.0)
            bytes >= 1024L * 1024 -> "${bytes / 1048576} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
