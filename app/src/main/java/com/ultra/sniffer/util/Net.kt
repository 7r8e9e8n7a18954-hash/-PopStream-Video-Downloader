package com.ultra.sniffer.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared networking layer for playlist fetching and file downloads. */
object Net {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun request(url: String): Request =
        Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(request(url)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${url.take(140)}")
            resp.body?.string() ?: ""
        }
    }

    /**
     * Streams [url] into [outFile].
     * @return the response Content-Type (may be null), useful to detect playlists.
     */
    suspend fun download(
        url: String,
        outFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): String? = withContext(Dispatchers.IO) {
        client.newCall(request(url)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${url.take(140)}")
            val body = resp.body ?: throw IOException("Empty response body")
            val contentType = resp.header("Content-Type")
            val total = body.contentLength()
            var done = 0L
            val buf = ByteArray(64 * 1024)
            outFile.parentFile?.mkdirs()
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            contentType
        }
    }
}
