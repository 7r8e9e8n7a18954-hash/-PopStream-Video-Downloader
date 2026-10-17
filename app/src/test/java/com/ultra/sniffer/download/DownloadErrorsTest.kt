package com.ultra.sniffer.download

import com.ultra.sniffer.util.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DownloadErrorsTest {

    @Test
    fun `expired signed link gets an actionable message`() {
        val text = DownloadErrors.friendly(Net.HttpException(403, "https://cdn.example.com/x.ts?sig=1"))
        assertTrue(text.contains("expire"))
        assertTrue(text.contains("दोबारा"))
    }

    @Test
    fun `server errors and rate limits are explained`() {
        assertTrue(DownloadErrors.friendly(Net.HttpException(429, "u")).contains("rate-limit"))
        assertTrue(DownloadErrors.friendly(Net.HttpException(503, "u")).contains("Server-side"))
        assertTrue(DownloadErrors.friendly(Net.HttpException(404, "u")).contains("नहीं है"))
    }

    @Test
    fun `network errors are translated`() {
        assertTrue(DownloadErrors.friendly(SocketTimeoutException("timeout")).contains("timeout"))
        assertTrue(DownloadErrors.friendly(UnknownHostException("Unable to resolve host x")).contains("Internet"))
    }

    @Test
    fun `wrapped causes are inspected`() {
        val wrapped = IOException("outer", Net.HttpException(403, "u"))
        assertTrue(DownloadErrors.friendly(wrapped).contains("expire"))
    }

    @Test
    fun `unknown errors keep a readable fallback`() {
        assertEquals("Download fail हो गया — दोबारा try करें", DownloadErrors.friendly(IOException("")))
    }
}
