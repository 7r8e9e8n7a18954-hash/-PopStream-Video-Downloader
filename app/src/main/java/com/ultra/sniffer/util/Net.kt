package com.ultra.sniffer.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private fun request(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url)
        if (headers.keys.none { it.equals("User-Agent", true) }) {
            builder.header("User-Agent", USER_AGENT)
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    /** What a URL actually is (after redirects). */
    data class Probe(
        val finalUrl: String,
        val contentType: String?,
        val length: Long,
        val fileName: String? = null
    ) {
        val isHtml: Boolean get() = contentType?.contains("html", true) == true
    }

    /**
     * Asks the server what this URL is without downloading it.
     * Tries HEAD first and falls back to a 1-byte ranged GET, because many
     * CDNs (Googlevideo included) reject HEAD.
     */
    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): Probe =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request(url, headers).newBuilder().head().build()).execute().use { resp ->
                    val ct = resp.header("Content-Type")
                    if (resp.isSuccessful && ct != null) {
                        return@withContext Probe(
                            resp.request.url.toString(),
                            ct,
                            resp.header("Content-Length")?.toLongOrNull() ?: -1L,
                            resp.header("Content-Disposition")?.substringAfter("filename=", "")?.trim('"', ' ')
                        )
                    }
                }
            }
            client.newCall(
                request(url, headers).newBuilder().header("Range", "bytes=0-0").build()
            ).execute().use { resp ->
                Probe(
                    resp.request.url.toString(),
                    resp.header("Content-Type"),
                    resp.header("Content-Range")?.substringAfter('/', "-1")?.toLongOrNull() ?: -1L,
                    resp.header("Content-Disposition")?.substringAfter("filename=", "")?.trim('"', ' ')
                )
            }
        }

    /** Fetches at most [maxBytes] of text (used for HTML pages). */
    suspend fun getTextCapped(url: String, maxBytes: Int = 3 * 1024 * 1024): String =
        withContext(Dispatchers.IO) {
            client.newCall(
                request(url, mapOf("Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"))
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${url.take(140)}")
                val body = resp.body ?: throw IOException("Empty response body")
                body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (out.size() < maxBytes) {
                        val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
            }
        }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            client.newCall(request(url, headers)).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${url.take(140)}")
                resp.body?.string() ?: ""
            }
        }

    /** POSTs JSON and returns the response body (used for YouTube InnerTube). */
    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)
        val builder = Request.Builder().url(url).post(body)
            .header("User-Agent", headers["User-Agent"] ?: USER_AGENT)
        headers.forEach { (k, v) -> if (k != "User-Agent") builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
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
        headers: Map<String, String> = emptyMap(),
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): String? = withContext(Dispatchers.IO) {
        client.newCall(request(url, headers)).execute().use { resp ->
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
