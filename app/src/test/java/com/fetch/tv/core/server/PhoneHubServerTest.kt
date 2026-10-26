package com.fetch.tv.core.server

import com.fetch.tv.core.remote.RemoteCommand
import com.fetch.tv.core.remote.RemoteKey
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.core.streams.ranking.StreamResolution
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class PhoneHubServerTest {
    private class FakeActions : PhoneHubActions {
        val commands = mutableListOf<RemoteCommand>()
        var savedRules: StreamRankingRules? = null

        override fun sendRemote(command: RemoteCommand): Boolean {
            commands += command
            return true
        }

        override suspend fun accountStatus() = PhoneHubAccountStatus(
            mdbListKeySet = false,
            trakt = PhoneHubTrackerStatus(connected = true, username = "sam"),
            simkl = PhoneHubTrackerStatus(connected = false),
            mediaServerProviders = listOf("jellyfin" to "Jellyfin"),
            mediaServers = emptyList()
        )

        override suspend fun saveMdbListKey(apiKey: String) = PhoneHubResult(false, "That key wasn't accepted by MDBList.")

        override suspend fun connectMediaServer(providerId: String, address: String, username: String, password: String) =
            PhoneHubResult(true)

        override suspend fun startTrackerSignIn(tracker: String) =
            Result.success(PhoneHubDeviceCode("ABCD1234", "https://trakt.tv/activate"))

        override suspend fun rankingRules() = StreamRankingRules(preferredResolution = StreamResolution.FHD_1080)

        override suspend fun saveRankingRules(rules: StreamRankingRules) {
            savedRules = rules
        }
    }

    private val actions = FakeActions()
    private val server = PhoneHubServer(
        actions = actions,
        sections = mapOf(
            PhoneHubServer.SECTION_POSTERS to CustomPosterConfigServer(
                currentPatternProvider = { "https://img/{imdb}.jpg" },
                onPatternChanged = {}
            )
        )
    )

    private fun body(response: NanoHTTPD.Response): String =
        response.data.use { String(it.readBytes(), StandardCharsets.UTF_8) }

    private fun post(uri: String, json: String) =
        server.serve(FakeHttpSession(uri, NanoHTTPD.Method.POST, json))

    @Test
    fun `landing page links every section`() {
        val page = body(server.serve(FakeHttpSession("/")))
        assertTrue(page.contains("href=\"/remote\""))
        assertTrue(page.contains("href=\"/accounts\""))
        assertTrue(page.contains("href=\"/ranking\""))
        assertTrue(page.contains("href=\"/posters/\""))
        assertFalse(page.contains("href=\"/badges/\""))
    }

    @Test
    fun `remote keys text and search reach the TV`() {
        assertEquals(NanoHTTPD.Response.Status.OK, post("/api/remote", """{"type":"key","key":"ok"}""").status)
        post("/api/remote", """{"type":"text","text":"star wars"}""")
        post("/api/remote", """{"type":"search","query":"dune"}""")

        assertEquals(
            listOf(RemoteCommand.Key(RemoteKey.OK), RemoteCommand.Text("star wars"), RemoteCommand.Search("dune")),
            actions.commands
        )
    }

    @Test
    fun `settings search reaches the TV`() {
        assertEquals(
            NanoHTTPD.Response.Status.OK,
            post("/api/remote", """{"type":"settings_search","query":"subtitles"}""").status
        )

        assertEquals(listOf(RemoteCommand.SettingsSearch("subtitles")), actions.commands)
    }

    @Test
    fun `bad remote input is rejected`() {
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, post("/api/remote", """{"type":"key","key":"POWER"}""").status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, post("/api/remote", """{"type":"text","text":""}""").status)
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, post("/api/remote", "not json").status)
        assertTrue(actions.commands.isEmpty())
    }

    @Test
    fun `account status never includes secrets`() {
        val json = body(server.serve(FakeHttpSession("/api/accounts")))
        assertTrue(json.contains("\"connected\":true"))
        assertTrue(json.contains("\"username\":\"sam\""))
        assertFalse(json.contains("apiKey"))
    }

    @Test
    fun `saves accounts and reports failures`() {
        val rejected = post("/api/accounts/mdblist", """{"apiKey":"bad"}""")
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, rejected.status)
        assertTrue(body(rejected).contains("MDBList"))

        val tracker = body(post("/api/accounts/tracker", """{"tracker":"trakt"}"""))
        assertTrue(tracker.contains("ABCD1234"))
    }

    @Test
    fun `ranking rules round trip`() {
        val current = body(server.serve(FakeHttpSession("/api/ranking")))
        assertTrue(current.contains("\"preferredResolution\":\"FHD_1080\""))

        val response = post("/api/ranking", """{"rules":{"preferredResolution":"HD_720","maxSizeGb":8.5,"excludedKeywords":["HC"]}}""")
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(StreamResolution.HD_720, actions.savedRules?.preferredResolution)
        assertEquals(8.5, actions.savedRules?.maxSizeGb)
        assertEquals(listOf("HC"), actions.savedRules?.excludedKeywords)
    }

    @Test
    fun `existing pages are served under their prefix with links rewritten`() {
        val page = body(server.serve(FakeHttpSession("/posters/")))
        assertTrue(page.contains("'/posters/api/pattern'"))
        assertFalse(page.contains("'/api/pattern'"))
        assertTrue(page.contains("All settings"))

        val api = server.serve(FakeHttpSession("/posters/api/pattern"))
        assertEquals(NanoHTTPD.Response.Status.OK, api.status)
        assertTrue(body(api).contains("{imdb}"))
    }

    @Test
    fun `unknown paths are not found`() {
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, server.serve(FakeHttpSession("/nope")).status)
    }
}
