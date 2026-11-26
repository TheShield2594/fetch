package com.fetch.tv.ui.screens.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** TheShield2594/fetch#701: stream credentials stay on the stream's host. */
class StreamHeaderScopeTest {

    private val origin = MockWebServer()
    private val cdn = MockWebServer()

    private val streamHeaders = mapOf(
        "User-Agent" to "FetchTest",
        "Referer" to "https://addon.example/",
        "Authorization" to "Basic dXNlcjpwYXNz",
        "Cookie" to "session=abc",
        "X-Emby-Token" to "emby-secret",
        "X-Plex-Token" to "plex-secret",
        "Range" to "bytes=0-"
    )

    @Before
    fun setUp() {
        val loopback = InetAddress.getByName("127.0.0.1")
        origin.start(loopback, 0)
        cdn.start(loopback, 0)
    }

    @After
    fun tearDown() {
        origin.shutdown()
        cdn.shutdown()
    }

    // The two servers must be different hosts to OkHttp: one is named, the other addressed by IP.
    private fun originUrl(path: String) = origin.url(path).newBuilder().host("localhost").build()
    private fun cdnUrl(path: String) = cdn.url(path).newBuilder().host("127.0.0.1").build()

    private fun client(originUrl: String?): OkHttpClient =
        OkHttpClient.Builder()
            .also { StreamHeaderScope.addInterceptor(it, streamHeaders, originUrl) }
            .build()

    @Test
    fun `credential headers are classified as origin only`() {
        listOf("Authorization", "cookie", "X-Emby-Token", "X-Emby-Authorization", "X-MediaBrowser-Token",
            "X-Plex-Token", "X-Api-Key", "x-auth-token", "Proxy-Authorization").forEach {
            assertTrue(it, StreamHeaderScope.isOriginOnly(it))
        }
        listOf("User-Agent", "Referer", "Origin", "Accept-Language").forEach {
            assertFalse(it, StreamHeaderScope.isOriginOnly(it))
        }
    }

    @Test
    fun `a redirect to another host keeps user agent and referer but drops credentials`() {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdnUrl("/file").toString()))
        cdn.enqueue(MockResponse().setBody("ok"))

        val request = Request.Builder().url(originUrl("/play"))
            .apply { streamHeaders.forEach { (k, v) -> if (k != "Range") header(k, v) } }
            .build()
        client(originUrl = null).newCall(request).execute().use { assertEquals(200, it.code) }

        val first = origin.takeRequest()
        assertEquals("Basic dXNlcjpwYXNz", first.getHeader("Authorization"))
        assertEquals("session=abc", first.getHeader("Cookie"))
        assertEquals("emby-secret", first.getHeader("X-Emby-Token"))

        val hop = cdn.takeRequest()
        assertEquals("FetchTest", hop.getHeader("User-Agent"))
        assertEquals("https://addon.example/", hop.getHeader("Referer"))
        assertNull(hop.getHeader("Authorization"))
        assertNull(hop.getHeader("Cookie"))
        assertNull(hop.getHeader("X-Emby-Token"))
        assertNull(hop.getHeader("X-Plex-Token"))
    }

    @Test
    fun `requests made directly to another host than the stream's get no credentials`() {
        // A segment, key or resolved chunk URL on a CDN, opened with the stream's default headers.
        cdn.enqueue(MockResponse().setBody("segment"))
        val request = Request.Builder().url(cdnUrl("/seg1.ts"))
            .header("Cookie", "session=abc")
            .header("X-Emby-Token", "emby-secret")
            .build()
        client(originUrl = originUrl("/master.m3u8").toString()).newCall(request).execute().use {
            assertEquals(200, it.code)
        }

        val recorded = cdn.takeRequest()
        assertEquals("FetchTest", recorded.getHeader("User-Agent"))
        assertNull(recorded.getHeader("Cookie"))
        assertNull(recorded.getHeader("X-Emby-Token"))
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `requests to the stream's host carry every header except range`() {
        origin.enqueue(MockResponse().setBody("segment"))
        client(originUrl = originUrl("/master.m3u8").toString())
            .newCall(Request.Builder().url(originUrl("/seg1.ts")).build())
            .execute().use { assertEquals(200, it.code) }

        val recorded = origin.takeRequest()
        assertEquals("Basic dXNlcjpwYXNz", recorded.getHeader("Authorization"))
        assertEquals("session=abc", recorded.getHeader("Cookie"))
        assertEquals("plex-secret", recorded.getHeader("X-Plex-Token"))
        assertNull(recorded.getHeader("Range"))
    }
}
