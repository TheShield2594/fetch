package com.fetch.desktop.website

import com.fetch.desktop.tracking.FakeHttp
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FetchWebsiteTest {
    private fun tempStore() = WebsiteStore(File(Files.createTempDirectory("web").toFile(), "website.json"))

    @Test
    fun `addresses are tidied as on TV`() {
        assertEquals("http://192.168.1.25:7000", FetchWebsiteClient.normalizeServerUrl(" 192.168.1.25:7000/ "))
        assertEquals("https://fetch.example.com/api", FetchWebsiteClient.normalizeServerUrl("https://fetch.example.com/api/"))
        assertNull(FetchWebsiteClient.normalizeServerUrl("  "))
    }

    @Test
    fun `pairing shows the code, then keeps the token once it's confirmed`() = runBlocking {
        var polls = 0
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/api/health" -> 200 to """{"status":"ok","service":"api"}"""
                "/api/devices/pair/start" -> 200 to """{"code":"K7Q2-9XPL","pollSecret":"s3","expiresAt":"2999-01-01T00:00:00Z","intervalSec":1,"confirmUrl":"https://web/confirm?c=K7Q2"}"""
                "/api/devices/pair/poll" -> if (++polls < 2) 202 to "{}" else 200 to """{"status":"approved","token":"tok","device":{"id":"d","name":"Living room PC"}}"""
                else -> 404 to "{}"
            }
        }
        val website = FetchWebsiteState(tempStore(), fake.client)
        website.pair("http://web.test/api", deviceName = "Fetch Desktop")
        assertEquals(WebsitePairing.Paired("Living room PC"), website.pairing)
        assertEquals(WebsiteSession("http://web.test/api", "tok", "Living room PC"), website.session)
        assertEquals("""{"name":"Fetch Desktop"}""", fake.sent.first { it.request.url.encodedPath.endsWith("start") }.body)
        assertEquals("s3", fake.sent.first { it.request.url.encodedPath.endsWith("poll") }.request.header("x-pairing-secret"))
        assertTrue(website.file.initialProfilePush)
        // Kept across restarts, with the address for next time.
        val reloaded = tempStore().also { it.save(website.file) }.load()
        assertEquals("http://web.test/api", reloaded.lastServerUrl)
    }

    @Test
    fun `pairing through the hub needs no address and collects the token from the website it names`() = runBlocking {
        var hubPolls = 0
        var websitePolls = 0
        val fake = FakeHttp { request ->
            when (request.url.toString()) {
                "https://watchfetch.tv/api/hub/pair/start" ->
                    201 to """{"code":"K7Q2-9XPL","pollSecret":"hub-secret","expiresAt":"2999-01-01T00:00:00Z","intervalSec":1,"linkUrl":"https://watchfetch.tv/link?code=K7Q2-9XPL"}"""
                "https://watchfetch.tv/api/hub/pair/poll" ->
                    if (++hubPolls < 2) 202 to "{}" else 200 to """{"status":"linked","api":"https://fetch.home.example/api","web":"https://fetch.home.example"}"""
                // The website claims at the hub just before it stores the pairing, so the
                // first poll can miss it.
                "https://fetch.home.example/api/devices/pair/poll" ->
                    if (++websitePolls < 2) 404 to "{}" else 200 to """{"status":"approved","token":"tok","device":{"id":"d","name":"Desk"}}"""
                else -> 404 to "{}"
            }
        }
        val website = FetchWebsiteState(tempStore(), fake.client)

        website.pairViaHub(deviceName = "Fetch Desktop")

        assertEquals(WebsitePairing.Paired("Desk"), website.pairing)
        assertEquals(WebsiteSession("https://fetch.home.example/api", "tok", "Desk"), website.session)
        val startBody = fake.sent.first { it.request.url.encodedPath.endsWith("hub/pair/start") }.body
        val handoff = fake.sent.first { it.request.url.host == "fetch.home.example" }.request.header("x-pairing-secret")!!
        // The hub got the hash of the secret the website was then polled with, never the secret.
        assertTrue(startBody.contains(com.fetch.tv.data.website.FetchPairingHub.hash(handoff)))
        assertFalse(startBody.contains(handoff))
        assertEquals("hub-secret", fake.sent.first { it.request.url.encodedPath.endsWith("hub/pair/poll") }.request.header("x-pairing-secret"))
    }

    @Test
    fun `an unreachable hub leaves the address to be typed`() = runBlocking {
        val website = FetchWebsiteState(tempStore(), FakeHttp { 503 to "{}" }.client)

        website.pairViaHub()

        assertEquals(true, (website.pairing as WebsitePairing.Failed).hubUnavailable)
    }

    @Test
    fun `pairing from the website address finds the API and confirms on that website`() = runBlocking {
        lateinit var website: FetchWebsiteState
        var shownWhilePolling: WebsitePairing? = null
        val fake = FakeHttp { request ->
            when (request.url.toString()) {
                "http://10.0.0.5:7002/fetch-server.json" -> 200 to """{"service":"web","api":"http://192.168.1.25:7000"}"""
                "http://10.0.0.5:7000/health" -> 200 to """{"status":"ok","service":"api"}"""
                "http://10.0.0.5:7000/devices/pair/start" ->
                    200 to """{"code":"K7Q2-9XPL","pollSecret":"s","expiresAt":"2999-01-01T00:00:00Z","intervalSec":1,"confirmUrl":"http://192.168.1.25:7002/pair?code=K7Q2-9XPL"}"""
                "http://10.0.0.5:7000/devices/pair/poll" -> {
                    shownWhilePolling = website.pairing
                    404 to "{}"
                }
                else -> 404 to "{}"
            }
        }
        website = FetchWebsiteState(tempStore(), fake.client)
        website.pair("10.0.0.5:7002")
        // The code went to the API found from the website...
        val start = fake.sent.first { it.request.url.encodedPath.endsWith("start") }
        assertEquals("http://10.0.0.5:7000/devices/pair/start", start.request.url.toString())
        // ...and the QR code confirms on the website address that worked, not the server's example one.
        val shown = shownWhilePolling as WebsitePairing.ShowingCode
        assertEquals("http://10.0.0.5:7002/pair?code=K7Q2-9XPL", shown.confirmUrl)
        assertEquals("http://10.0.0.5:7000", shown.serverUrl)
        assertEquals(WebsitePairing.Expired, website.pairing)
    }

    @Test
    fun `a website that can't be found says so`() = runBlocking {
        val website = FetchWebsiteState(tempStore(), FakeHttp { 404 to "{}" }.client)
        website.pair("10.0.0.9:7002")
        assertTrue(website.pairing is WebsitePairing.Failed)
        assertTrue(fakeFreeMessage(website.pairing).contains("No Fetch website"))
    }

    private fun fakeFreeMessage(pairing: WebsitePairing): String = (pairing as WebsitePairing.Failed).message.orEmpty()

    @Test
    fun `an expired code says so`() = runBlocking {
        val fake = FakeHttp { request ->
            if (request.url.encodedPath == "/health") 200 to """{"service":"api"}"""
            else if (request.url.encodedPath.endsWith("start")) 200 to """{"code":"A","pollSecret":"s","expiresAt":"2999-01-01T00:00:00Z","intervalSec":1}"""
            else 404 to "{}"
        }
        val website = FetchWebsiteState(tempStore(), fake.client)
        website.pair("web.test")
        assertEquals(WebsitePairing.Expired, website.pairing)
        assertNull(website.session)
    }

    @Test
    fun `website lists load for the profile in this slot, with its id header`() = runBlocking {
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/devices/me/profiles" -> 200 to """{"profiles":[{"slot":1,"id":"p-main","name":"Me"},{"slot":2,"id":"p-kid","name":"Kid","hasPin":true}]}"""
                "/stremio/list/l1" -> 200 to """{"metas":[{"id":"tt1","type":${'"'}${request.url.queryParameter("type")}${'"'},"name":"One","year":2001}]}"""
                else -> 404 to "{}"
            }
        }
        val store = tempStore().apply { save(WebsiteFile(WebsiteSession("http://web.test", "tok", "PC"))) }
        val website = FetchWebsiteState(store, fake.client)
        val items = website.listItems(2, "l1", listOf("movie", "series"))
        assertEquals(listOf("movie", "series"), items.map { it.apiType })
        assertEquals("tt1", items[0].imdbId)
        val listCall = fake.sent.first { it.request.url.encodedPath == "/stremio/list/l1" }
        assertEquals("p-kid", listCall.request.header("x-profile-id"))
        assertEquals("Bearer tok", listCall.request.header("Authorization"))
        assertEquals("p-kid", listCall.request.url.queryParameter("profileId"))
    }

    @Test
    fun `a revoked token signs this computer out`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("")
                .header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val store = tempStore().apply { save(WebsiteFile(WebsiteSession("http://web.test", "tok", "PC"))) }
        val website = FetchWebsiteState(store, client)
        website.checkSession()
        assertNull(website.session)
        assertTrue(website.file.signedOutByWebsite)
        website.dismissSignedOutNotice()
        assertFalse(website.file.signedOutByWebsite)
    }
}
