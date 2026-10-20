package com.fetch.tv.core.image

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserImageRequestHeadersTest {
    @Test
    fun `adds browser headers and an origin-only referer`() {
        val request = Request.Builder()
            .url("https://images.example.com/uploads/poster/a.jpg?size=w500")
            .build()
            .withBrowserImageRequestHeaders()

        assertEquals("https://images.example.com/", request.header("Referer"))
        assertTrue(request.header("User-Agent")!!.startsWith("Mozilla/5.0"))
        assertTrue(request.header("Accept")!!.startsWith("image/"))
    }

    @Test
    fun `keeps headers the caller already set`() {
        val request = Request.Builder()
            .url("https://images.example.com/a.jpg")
            .header("Referer", "https://site.example/")
            .header("User-Agent", "Custom")
            .build()
            .withBrowserImageRequestHeaders()

        assertEquals("https://site.example/", request.header("Referer"))
        assertEquals("Custom", request.header("User-Agent"))
    }
}
