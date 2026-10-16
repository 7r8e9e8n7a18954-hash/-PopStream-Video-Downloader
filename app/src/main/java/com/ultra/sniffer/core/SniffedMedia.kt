package com.ultra.sniffer.core

import com.ultra.sniffer.util.MediaUrlUtils

/** What kind of media container a detected URL points to. */
enum class MediaKind(val label: String) {
    MP4("MP4"),
    HLS("HLS / .m3u8"),
    DASH("DASH / .mpd"),
    TS("TS chunk"),
    AUDIO("Audio"),
    UNKNOWN("Video")
}

/** How the URL was discovered. */
enum class MediaSource(val label: String) {
    HTTP("Packet sniff"),
    TLS_SNI("HTTPS host hint"),
    BROWSER("In-app browser"),
    SHARE("Shared link"),
    MANUAL("Manual paste")
}

/**
 * A single detected media stream.
 *
 * @param downloadable false for TLS-SNI-only hints (encrypted traffic where
 *        only the host name is visible, not the full URL).
 */
data class SniffedMedia(
    val url: String,
    val host: String,
    val kind: MediaKind,
    val source: MediaSource,
    val label: String,
    val timeMs: Long = System.currentTimeMillis(),
    val downloadable: Boolean = true
) {
    val id: String get() = url

    companion object {
        fun fromUrl(url: String, source: MediaSource, downloadable: Boolean = true): SniffedMedia {
            val host = MediaUrlUtils.hostOf(url)
            val kind = MediaUrlUtils.kindOf(url)
            val label = MediaUrlUtils.guessDisplayName(url, host)
            return SniffedMedia(url, host, kind, source, label, System.currentTimeMillis(), downloadable)
        }

        fun hostHint(host: String): SniffedMedia {
            return SniffedMedia(
                url = "tls://$host",
                host = host,
                kind = MediaKind.UNKNOWN,
                source = MediaSource.TLS_SNI,
                label = host,
                timeMs = System.currentTimeMillis(),
                downloadable = false
            )
        }
    }
}
