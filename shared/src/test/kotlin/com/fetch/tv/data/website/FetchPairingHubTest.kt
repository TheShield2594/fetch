package com.fetch.tv.data.website

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchPairingHubTest {

    /** Answers every request with [status] and [body], and keeps the requests. */
    private class Canned(private val status: Int, private val body: String) : Interceptor {
        val requests = mutableListOf<Request>()
        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun hubAnswering(status: Int, body: String = "{}"): Pair<FetchPairingHub, Canned> {
        val canned = Canned(status, body)
        val http = OkHttpClient.Builder().addInterceptor(canned).build()
        return FetchPairingHub(http, "https://hub.example/api/") to canned
    }

    @Test
    fun `the handoff hash is the SHA-256 hex the website compares poll secrets with`() {
        // Node: createHash("sha256").update("abc").digest("hex")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FetchPairingHub.hash("abc"))
    }

    @Test
    fun `handoff secrets are fresh each time`() {
        val first = FetchPairingHub.newHandoffSecret()
        assertEquals(43, first.length)
        assertNotEquals(first, FetchPairingHub.newHandoffSecret())
    }

    @Test
    fun `start sends the name and only the hash, and reads the code`() {
        val (hub, canned) = hubAnswering(
            201,
            """{"code":"ABCD-EFGH","pollSecret":"ps","expiresAt":"2026-01-01T00:00:00Z","intervalSec":3,"linkUrl":"https://watchfetch.tv/link?code=ABCD-EFGH"}"""
        )

        val start = hub.start("Shield", "f".repeat(64))

        assertEquals("ABCD-EFGH", start.code)
        assertEquals("https://watchfetch.tv/link?code=ABCD-EFGH", start.linkUrl)
        val request = canned.requests.single()
        assertEquals("https://hub.example/api/hub/pair/start", request.url.toString())
        val sent = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(sent.contains("\"handoffHash\":\"${"f".repeat(64)}\""))
        assertTrue(sent.contains("\"name\":\"Shield\""))
    }

    @Test
    fun `poll reads pending, expired and the linked website`() {
        assertEquals(HubPairingPoll.Pending, hubAnswering(202).first.poll("ps"))
        assertEquals(HubPairingPoll.Expired, hubAnswering(404).first.poll("ps"))

        val (hub, canned) = hubAnswering(200, """{"status":"linked","api":"https://fetch.example/api/","web":"https://fetch.example"}""")
        assertEquals(HubPairingPoll.Linked("https://fetch.example/api", "https://fetch.example"), hub.poll("ps"))
        assertEquals("ps", canned.requests.single().header("x-pairing-secret"))
    }

    @Test
    fun `a linked answer naming something other than an http address is not followed`() {
        val (hub, _) = hubAnswering(200, """{"status":"linked","api":"file:///etc/passwd"}""")
        assertEquals(HubPairingPoll.Pending, hub.poll("ps"))
        assertNull(FetchPairingHub.httpUrlOrNull("javascript:alert(1)"))
    }

    @Test(expected = java.io.IOException::class)
    fun `a hub that refuses to start is an IOException`() {
        hubAnswering(429).first.start("TV", "f".repeat(64))
    }
}
