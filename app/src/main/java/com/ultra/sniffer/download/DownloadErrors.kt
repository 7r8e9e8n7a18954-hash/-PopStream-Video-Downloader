package com.ultra.sniffer.download

import com.ultra.sniffer.util.Net

/**
 * Turns raw exceptions into short, actionable messages for the notification
 * and the detected-media list. Pure logic → unit-tested.
 */
object DownloadErrors {

    fun friendly(t: Throwable): String {
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth < 6) {
            if (cause is Net.HttpException) return http(cause.code)
            cause = cause.cause
            depth++
        }
        val message = t.message.orEmpty()
        return when {
            message.contains("ENOSPC", true) || message.contains("No space left", true) ->
                "फ़ोन में जगह नहीं है — कुछ files delete करके फिर try करें"
            message.contains("Unable to resolve host", true) || message.contains("UnknownHost", true) ->
                "Internet नहीं मिल रहा — connection check करके फिर try करें"
            message.contains("timeout", true) ->
                "Network slow है (timeout) — दोबारा try करें"
            message.contains("EACCES", true) || message.contains("Permission denied", true) ->
                "Storage permission नहीं मिली — app settings में allow करें"
            message.contains("ENOTFOUND", true) || message.contains("ECONNREFUSED", true) ->
                "Server नहीं मिला — दोबारा try करें"
            message.contains("Output file", true) || message.contains("mux", true) || message.contains("merge", true) ->
                "Merge fail हो गया — दोबारा try करें (video फिर से चलाकर नया link पकड़ें)"
            message.isBlank() -> "Download fail हो गया — दोबारा try करें"
            else -> message.take(160)
        }
    }

    private fun http(code: Int): String = when (code) {
        401, 403 ->
            "Link expire हो गया ($code) — ये CDN links कुछ मिनट में बदल जाते हैं। Video दोबारा चलाकर नया link पकड़ें या फिर try करें"
        404, 410 -> "Video अब server पर नहीं है ($code) — link पुराना है"
        416 -> "पहले का partial download mismatch — ऐप खुद restart करेगा, दोबारा try करें"
        429 -> "Server ने rate-limit लगाया (429) — थोड़ी देर बाद try करें"
        in 500..599 -> "Server-side problem ($code) — थोड़ी देर बाद try करें"
        else -> "Download में HTTP error $code आया"
    }
}
