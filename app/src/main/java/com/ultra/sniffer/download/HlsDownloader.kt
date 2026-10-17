package com.ultra.sniffer.download

import com.ultra.sniffer.util.MediaUrlUtils
import com.ultra.sniffer.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * Downloads an HLS stream and merges it into ONE MP4:
 *
 *   master playlist -> highest bandwidth variant (+ separate audio rendition)
 *   media playlist  -> every segment (plus the fMP4 init segment when present)
 *
 * The chunks are then handed to FFmpeg through a locally written playlist —
 * FFmpeg fixes the timestamp discontinuities between chunks and stream-copies
 * everything into a single high quality MP4. If FFmpeg is not usable the
 * downloader falls back to concatenating the chunks and remuxing them with the
 * pure-Android MediaMuxer engine.
 */
class HlsDownloader(private val workDir: File) {

    private val chunkExts = setOf("ts", "m4s", "mp4", "m4a", "aac", "cmp4", "cmfv", "cmfa", "m4v")

    /** Everything that was downloaded for one track. */
    private class Track(
        val parts: List<File>,
        val segmentFiles: List<File>,
        val initFile: File?,
        val durations: List<Double>,
        val mime: String
    ) {
        val fmp4: Boolean get() = initFile != null
    }

    suspend fun download(url: String, onProgress: (Int, String) -> Unit): File =
        withContext(Dispatchers.IO) {
            onProgress(0, "HLS playlist पढ़ी जा रही है…")

            var playlistUrl = url
            var playlistText = Net.getText(url)
            var audioPlaylistUrl: String? = null

            val masterText = playlistText
            if (M3u8Parser.isMaster(masterText)) {
                val variants = M3u8Parser.parseMaster(masterText, url)
                if (variants.isEmpty()) throw IOException("HLS master में कोई stream नहीं मिली")
                val best = variants.maxByOrNull { it.bandwidth } ?: variants.first()
                playlistUrl = best.url
                playlistText = Net.getText(playlistUrl)
                val group = best.audioGroup
                if (!group.isNullOrBlank()) {
                    val renditions = M3u8Parser.parseAudioRenditions(masterText, url)
                    val rendition = renditions.firstOrNull { it.groupId == group && it.isDefault }
                        ?: renditions.firstOrNull { it.groupId == group }
                    audioPlaylistUrl = rendition?.url
                }
            }

            val media = M3u8Parser.parseMedia(playlistText, playlistUrl)
            if (media.segmentUrls.isEmpty()) throw IOException("HLS playlist में कोई segment नहीं मिला")

            val hasSeparateAudio = audioPlaylistUrl != null

            // ---------------- video track (no separate audio) ----------------
            if (!hasSeparateAudio) {
                val videoTrack = downloadTrack(
                    media.segmentUrls, media.initUrl, media.durations,
                    File(workDir, "hls_video"), "video", 0, 100, onProgress
                )
                val videoPlaylist = writeLocalPlaylist(File(workDir, "video_track.m3u8"), videoTrack)
                onProgress(-1, "FFmpeg से single MP4 बन रहा है…")
                Merger.remuxPlaylist(videoPlaylist, File(workDir, "final.mp4"))
                    ?.let { return@withContext it }
                // Fallback: naive concat + MediaMuxer remux
                val merged = concat(videoTrack.parts, File(workDir, if (videoTrack.fmp4) "merged_video.mp4" else "merged_video.ts"))
                onProgress(-1, "MediaMuxer से remux हो रहा है…")
                return@withContext Merger.remux(merged, File(workDir, "final.mp4"), TrackMode.ALL)
            }

            // ---------------- video + audio tracks, downloaded in parallel ----------------
            val audioMedia = M3u8Parser.parseMedia(Net.getText(audioPlaylistUrl!!), audioPlaylistUrl)
            val videoPct = AtomicInteger(0)
            val audioPct = AtomicInteger(0)
            val (videoTrack, audioTrack) = coroutineScope {
                val videoJob = async(Dispatchers.IO) {
                    downloadTrack(
                        media.segmentUrls, media.initUrl, media.durations,
                        File(workDir, "hls_video"), "video", 0, 100
                    ) { pct, message ->
                        if (pct in 0..100) videoPct.set(pct)
                        onProgress((videoPct.get() * 55 + audioPct.get() * 45) / 100, message)
                    }
                }
                val audioJob = async(Dispatchers.IO) {
                    downloadTrack(
                        audioMedia.segmentUrls, audioMedia.initUrl, audioMedia.durations,
                        File(workDir, "hls_audio"), "audio", 0, 100
                    ) { pct, message ->
                        if (pct in 0..100) audioPct.set(pct)
                        onProgress((videoPct.get() * 55 + audioPct.get() * 45) / 100, message)
                    }
                }
                videoJob.await() to audioJob.await()
            }
            val videoPlaylist = writeLocalPlaylist(File(workDir, "video_track.m3u8"), videoTrack)
            val audioPlaylist = writeLocalPlaylist(File(workDir, "audio_track.m3u8"), audioTrack)

            onProgress(-1, "Video + Audio merge हो रहे हैं…")
            Merger.muxPlaylists(videoPlaylist, audioPlaylist, File(workDir, "final.mp4"))
                ?.let { return@withContext it }

            // Fallback: remux both tracks separately, then mux them together.
            onProgress(-1, "Video + Audio merge (fallback engine)…")
            val vMerged = concat(videoTrack.parts, File(workDir, if (videoTrack.fmp4) "merged_video.mp4" else "merged_video.ts"))
            val aMerged = concat(audioTrack.parts, File(workDir, if (audioTrack.fmp4) "merged_audio.mp4" else "merged_audio.ts"))
            val videoOnly = Merger.remux(vMerged, File(workDir, "video_only.mp4"), TrackMode.VIDEO_ONLY)
            val audioOnly = Merger.remux(aMerged, File(workDir, "audio_only.mp4"), TrackMode.AUDIO_ONLY)
            Merger.mux(videoOnly, audioOnly, File(workDir, "final.mp4"))
        }

    // ------------------------------------------------------------------
    // Segment downloading
    // ------------------------------------------------------------------

    private suspend fun downloadTrack(
        urls: List<String>,
        initUrl: String?,
        durations: List<Double>,
        dir: File,
        label: String,
        startPct: Int,
        endPct: Int,
        onProgress: (Int, String) -> Unit
    ): Track {
        dir.mkdirs()
        val defaultExt = if (initUrl != null) "mp4" else "ts"

        var initFile: File? = null
        if (initUrl != null) {
            val ext = extFor(initUrl, "mp4")
            initFile = File(dir, "init_00000.$ext")
            Net.download(initUrl, initFile)
        }

        val files = downloadSegments(urls, dir, label, startPct, endPct, defaultExt, onProgress)
        val mime = if (initUrl != null) "video/mp4" else "video/mp2t"
        return Track(
            parts = buildList {
                initFile?.let { add(it) }
                addAll(files)
            },
            segmentFiles = files,
            initFile = initFile,
            durations = durations,
            mime = mime
        )
    }

    private suspend fun downloadSegments(
        urls: List<String>,
        dir: File,
        label: String,
        startPct: Int,
        endPct: Int,
        defaultExt: String,
        onProgress: (Int, String) -> Unit
    ): List<File> = coroutineScope {
        val semaphore = Semaphore(Net.SEMAPHORE_PERMITS)
        val total = urls.size
        val done = AtomicInteger(0)
        urls.mapIndexed { index, u ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val ext = extFor(u, defaultExt)
                    val file = File(dir, "seg_${index.toString().padStart(5, '0')}.$ext")
                    if (!file.exists() || file.length() == 0L) Net.download(u, file)
                    val completed = done.incrementAndGet()
                    val pct = startPct + (endPct - startPct) * completed / total
                    onProgress(pct, "$label chunks: $completed/$total")
                    file
                }
            }
        }.awaitAll()
    }

    private fun extFor(url: String, fallback: String): String {
        val ext = MediaUrlUtils.extOf(url)
        return if (ext in chunkExts) ext else fallback
    }

    // ------------------------------------------------------------------
    // Local playlist + concat helpers
    // ------------------------------------------------------------------

    /**
     * Writes a VOD playlist that points at the downloaded chunks (all names are
     * relative, so they resolve next to the playlist file). FFmpeg eats this
     * and produces a clean MP4.
     */
    private fun writeLocalPlaylist(playlistFile: File, track: Track): File {
        val files = track.segmentFiles
        val durations = track.durations
        val targetDuration = maxOf(2, ceil(durations.maxOrNull() ?: 10.0).toInt())

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:").append(if (track.fmp4) 7 else 3).append('\n')
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        track.initFile?.let { sb.append("#EXT-X-MAP:URI=\"").append(it.name).append("\"\n") }
        files.forEachIndexed { index, file ->
            val dur = durations.getOrNull(index)?.takeIf { it > 0.0 } ?: targetDuration.toDouble()
            sb.append("#EXTINF:").append(dur).append(",\n")
            sb.append(file.name).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        playlistFile.writeText(sb.toString())
        return playlistFile
    }

    private fun concat(parts: List<File>, out: File): File {
        out.outputStream().buffered(256 * 1024).use { output ->
            for (part in parts) {
                part.inputStream().buffered(256 * 1024).use { input -> input.copyTo(output, 256 * 1024) }
            }
        }
        return out
    }
}
