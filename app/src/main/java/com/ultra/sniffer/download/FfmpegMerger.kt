package com.ultra.sniffer.download

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Mobile-FFmpeg (ffmpeg-kit 6.0 LTS) integration.
 *
 * This is the engine that turns the mess that HLS/DASH really is
 * (hundreds of .ts chunks, separate audio renditions, fMP4 init segments)
 * into ONE high quality MP4 with a single `-c copy` pass — no re-encode,
 * so picture and sound quality stay exactly as downloaded.
 *
 * Everything here is synchronous/blocking: call it from a background thread
 * (the download service does).
 */
object FfmpegMerger {

    private const val TAG = "FfmpegMerger"

    /**
     * true when the native ffmpeg libraries loaded successfully.
     * The app still works when this is false: [Merger] then falls back to the
     * MediaMuxer based engine in MediaMerger.kt.
     */
    val available: Boolean by lazy {
        runCatching {
            FFmpegKitConfig.setLogLevel(Level.AV_LOG_ERROR)
            true
        }.getOrElse { t ->
            Log.w(TAG, "ffmpeg-kit unavailable: ${t.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // Low level
    // ------------------------------------------------------------------

    /** Runs ffmpeg with the given arguments (no shell quoting needed). */
    fun run(args: List<String>): Boolean {
        if (!available) return false
        return try {
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            val ok = ReturnCode.isSuccess(session.returnCode)
            if (!ok) {
                Log.w(TAG, "ffmpeg failed rc=${session.returnCode}: ${args.joinToString(" ")}")
                Log.w(TAG, session.allLogsAsString.takeLast(2000))
            }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "ffmpeg exception: ${t.message}")
            false
        }
    }

    private fun common(): List<String> = listOf(
        "-hide_banner",
        "-loglevel", "error",
        "-y"
    )

    // ------------------------------------------------------------------
    // Remux / mux helpers (all stream-copy, no quality loss)
    // ------------------------------------------------------------------

    /** Remux everything (video + audio) into MP4. */
    fun remux(input: File, output: File): Boolean = run(
        common() + listOf(
            "-i", input.absolutePath,
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-c", "copy",
            "-movflags", "+faststart",
            output.absolutePath
        )
    )

    fun remuxVideoOnly(input: File, output: File): Boolean = run(
        common() + listOf(
            "-i", input.absolutePath,
            "-map", "0:v:0?",
            "-c", "copy",
            "-movflags", "+faststart",
            output.absolutePath
        )
    )

    fun remuxAudioOnly(input: File, output: File): Boolean = run(
        common() + listOf(
            "-i", input.absolutePath,
            "-map", "0:a:0?",
            "-c", "copy",
            "-movflags", "+faststart",
            output.absolutePath
        )
    )

    /** Mux a separate video file and audio file into one MP4. */
    fun mux(video: File, audio: File, output: File): Boolean = run(
        common() + listOf(
            "-i", video.absolutePath,
            "-i", audio.absolutePath,
            "-map", "0:v:0?", "-map", "1:a:0?",
            "-c", "copy",
            "-movflags", "+faststart",
            output.absolutePath
        )
    )

    /**
     * Lets ffmpeg read a *local* HLS playlist (written by the downloader) and
     * remux it into a single MP4. FFmpeg fixes timestamp discontinuities
     * between chunks, which is what makes the result play everywhere.
     */
    fun remuxPlaylist(playlist: File, output: File): Boolean = run(
        common() + listOf(
            "-protocol_whitelist", "file,crypto,data,pipe",
            "-allowed_extensions", "ALL",
            "-i", playlist.absolutePath,
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-c", "copy",
            "-movflags", "+faststart",
            output.absolutePath
        )
    )

    /** Mux a video playlist and a separate audio playlist (HLS audio rendition). */
    fun muxPlaylists(videoPlaylist: File, audioPlaylist: File, output: File): Boolean = run(
        common() + listOf(
            "-protocol_whitelist", "file,crypto,data,pipe",
            "-allowed_extensions", "ALL",
            "-i", videoPlaylist.absolutePath,
            "-i", audioPlaylist.absolutePath,
            "-map", "0:v:0?", "-map", "1:a:0?",
            "-c", "copy",
            "-movflags", "+faststart",
            output.absolutePath
        )
    )
}

/**
 * Facade that prefers FFmpeg and transparently falls back to the pure-Android
 * [MediaMerger] engine when FFmpeg is missing or refuses a file.
 */
object Merger {

    fun remux(input: File, output: File, mode: TrackMode = TrackMode.ALL): File {
        if (FfmpegMerger.available) {
            val ok = when (mode) {
                TrackMode.ALL -> FfmpegMerger.remux(input, output)
                TrackMode.VIDEO_ONLY -> FfmpegMerger.remuxVideoOnly(input, output)
                TrackMode.AUDIO_ONLY -> FfmpegMerger.remuxAudioOnly(input, output)
            }
            if (ok && output.exists() && output.length() > 0) return output
            runCatching { output.delete() }
        }
        return MediaMerger.remux(input, output, mode)
    }

    fun mux(video: File, audio: File, output: File): File {
        if (FfmpegMerger.available) {
            if (FfmpegMerger.mux(video, audio, output) && output.exists() && output.length() > 0) {
                return output
            }
            runCatching { output.delete() }
        }
        return MediaMerger.mux(video, audio, output)
    }

    /** FFmpeg-only path (HLS playlist). Returns null when FFmpeg is unusable. */
    fun remuxPlaylist(playlist: File, output: File): File? {
        if (!FfmpegMerger.available) return null
        return if (FfmpegMerger.remuxPlaylist(playlist, output) && output.exists() && output.length() > 0) {
            output
        } else {
            runCatching { output.delete() }
            null
        }
    }

    /** FFmpeg-only path (video playlist + separate audio playlist). Returns null on failure. */
    fun muxPlaylists(videoPlaylist: File, audioPlaylist: File, output: File): File? {
        if (!FfmpegMerger.available) return null
        return if (FfmpegMerger.muxPlaylists(videoPlaylist, audioPlaylist, output) &&
            output.exists() && output.length() > 0
        ) {
            output
        } else {
            runCatching { output.delete() }
            null
        }
    }
}
