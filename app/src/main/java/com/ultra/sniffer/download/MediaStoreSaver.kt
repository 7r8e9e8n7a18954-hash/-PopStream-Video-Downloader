package com.ultra.sniffer.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Saves finished videos into the phone gallery (Movies/UltraSniffer). */
object MediaStoreSaver {

    fun save(context: Context, file: File, displayName: String, mime: String): Uri {
        return if (Build.VERSION.SDK_INT >= 29) {
            saveViaMediaStore(context, file, displayName, mime)
        } else {
            saveLegacy(context, file, displayName, mime)
        }
    }

    private fun saveViaMediaStore(context: Context, file: File, displayName: String, mime: String): Uri {
        val resolver = context.contentResolver
        val isAudio = mime.startsWith("audio/")
        val collection = if (isAudio) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (isAudio) "Music/UltraSniffer" else "Movies/UltraSniffer"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore entry नहीं बन सका")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 128 * 1024) }
            } ?: throw IOException("Output stream नहीं खुला")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun saveLegacy(context: Context, file: File, displayName: String, mime: String): Uri {
        val dirName = if (mime.startsWith("audio/")) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val dir = File(Environment.getExternalStoragePublicDirectory(dirName), "UltraSniffer")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Storage folder नहीं बन सका")
        var target = File(dir, displayName)
        var counter = 1
        while (target.exists()) {
            val base = displayName.substringBeforeLast('.')
            val ext = displayName.substringAfterLast('.', "mp4")
            target = File(dir, "${base}_${counter++}.$ext")
        }
        file.copyTo(target, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(target)
    }
}
