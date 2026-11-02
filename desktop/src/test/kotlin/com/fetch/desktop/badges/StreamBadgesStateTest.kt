package com.fetch.desktop.badges

import com.fetch.tv.core.streams.StreamBadgePlacement
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StreamBadgesStateTest {

    private val badgeJson = """
        {
          "filters": [
            {"id": "f1", "groupId": "g1", "name": "Dolby Vision", "pattern": "(?i)\\b(DV|dolby.?vision)\\b",
             "imageURL": "https://img.example/dv.png", "isEnabled": true, "tagColor": "#102030", "tagStyle": "filled"},
            {"id": "f2", "groupId": "g1", "name": "4K", "pattern": "(?i)\\b(2160p|4k)\\b", "imageURL": "", "isEnabled": true},
            {"id": "f3", "groupId": "g1", "name": "Off", "pattern": "(?i)remux", "isEnabled": false},
            {"id": "f4", "name": "", "pattern": "broken"}
          ],
          "groups": [{"id": "g1", "name": "Quality", "color": "#ffffff"}]
        }
    """.trimIndent()

    private class Fake(val respond: (String) -> Pair<Int, String>) : Interceptor {
        val urls = mutableListOf<String>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            urls += request.url.toString()
            val (code, body) = respond(request.url.toString())
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun tempFile(): File = File(Files.createTempDirectory("badges").toFile(), "stream-badges.json")

    private fun stream(name: String? = null, description: String? = null, size: Long? = null) = Stream(
        name = name, title = null, description = description, url = "https://cdn.example/v.mkv",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
        behaviorHints = size?.let { StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, videoSize = it) },
        addonName = "Addon", addonLogo = null
    )

    @Test
    fun `add fetches, parses and activates the import`() = runBlocking {
        val fake = Fake { 200 to badgeJson }
        val state = StreamBadgesState(this, tempFile(), fake.client) { 1_000L }
        state.add(" https://badges.example/fusion/badges.json ")!!.join()

        assertNull(state.error)
        assertEquals(listOf("https://badges.example/fusion/badges.json"), fake.urls)
        val source = state.sources.single()
        assertEquals("https://badges.example/fusion/badges.json", source.url)
        assertEquals("badges.example · badges", source.name)
        assertEquals(1_000L, source.lastUpdated)
        assertTrue(source.isActive)
        assertEquals(2, source.enabledBadgeCount)
        assertEquals(1, source.groupCount)
        assertFalse(state.importing)
    }

    @Test
    fun `persists rules, metadata and settings across instances`() = runBlocking {
        val file = tempFile()
        val state = StreamBadgesState(this, file, Fake { 200 to badgeJson }.client) { 42L }
        state.add("https://a.example/one.json")!!.join()
        state.add("https://b.example/two.json")!!.join()
        state.setActive("https://a.example/one.json")
        state.setShowFileSizeBadges(false)
        state.setShowAddonLogo(false)
        state.setBadgePlacement(StreamBadgePlacement.TOP)

        val reloaded = StreamBadgesState(this, file, Fake { 500 to "" }.client)
        assertEquals(state.data, reloaded.data)
        assertEquals(listOf(true, false), reloaded.sources.map { it.isActive })
        assertEquals(42L, reloaded.sources.first().lastUpdated)
        assertFalse(reloaded.showFileSizeBadges)
        assertFalse(reloaded.showAddonLogo)
        assertEquals(StreamBadgePlacement.TOP, reloaded.badgePlacement)
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())

        reloaded.remove("https://a.example/one.json")
        val afterRemove = StreamBadgesState(this, file)
        assertEquals(listOf("https://b.example/two.json"), afterRemove.sources.map { it.url })
        assertTrue(afterRemove.sources.single().isActive)
        assertEquals(1, afterRemove.data.sources.size)
    }

    @Test
    fun `badgesFor matches the active import against name and description`() = runBlocking {
        val state = StreamBadgesState(this, tempFile(), Fake { 200 to badgeJson }.client)
        assertTrue(state.badgesFor(stream(name = "4K DV")).isEmpty())
        state.add("https://a.example/one.json")!!.join()

        assertEquals(listOf("Dolby Vision", "4K"), state.badgesFor(stream(name = "Torrentio\n4k", description = "Movie.2160p.DV.mkv")).map { it.name })
        assertEquals(listOf("4K"), state.badgesFor(stream(description = "Film 2160p HDR")).map { it.name })
        assertTrue(state.badgesFor(stream(name = "1080p", description = "REMUX")).isEmpty())
        assertEquals("https://img.example/dv.png", state.badgesFor(stream(name = "dolby vision")).single().imageURL)
    }

    @Test
    fun `invalid urls, http failures, bad json and the import limit set error`() = runBlocking {
        val state = StreamBadgesState(this, tempFile(), Fake { url ->
            when {
                "missing" in url -> 404 to ""
                "junk" in url -> 200 to "{\"filters\": []}"
                else -> 200 to badgeJson
            }
        }.client)

        assertNull(state.add("  "))
        assertEquals("Enter a badge JSON URL.", state.error)
        assertNull(state.add("ftp://x.example/a.json"))
        assertEquals("Badge URL must start with http:// or https://.", state.error)

        state.add("https://x.example/missing.json")!!.join()
        assertEquals("Badge import failed.", state.error)
        state.add("https://x.example/junk.json")!!.join()
        assertEquals("Badge import did not contain any usable filters.", state.error)
        assertTrue(state.sources.isEmpty())

        state.add("https://x.example/1.json")!!.join()
        state.add("https://x.example/2.json")!!.join()
        state.add("https://x.example/3.json")!!.join()
        assertNull(state.error)
        assertNull(state.add("https://x.example/4.json"))
        assertEquals("You can import up to 3 badge URLs.", state.error)
        // Re-importing an existing URL is an update, not a new slot.
        assertNotNull(state.add("https://x.example/2.json")!!.also { it.join() })
        assertNull(state.error)
        assertEquals(3, state.sources.size)
        assertEquals("https://x.example/2.json", state.sources.single { it.isActive }.url)
    }

    @Test
    fun `refreshAll keeps the active import and reports failures`() = runBlocking {
        var payload = badgeJson
        var fail = false
        var now = 1L
        val state = StreamBadgesState(this, tempFile(), Fake { if (fail) 500 to "" else 200 to payload }.client) { now }
        state.add("https://a.example/one.json")!!.join()
        state.add("https://b.example/two.json")!!.join()
        state.setActive("https://a.example/one.json")

        payload = badgeJson.replace("\"Dolby Vision\"", "\"DoVi\"")
        now = 5L
        state.refreshAll()!!.join()
        assertNull(state.error)
        assertEquals(listOf(true, false), state.sources.map { it.isActive })
        assertEquals(listOf(5L, 5L), state.sources.map { it.lastUpdated })
        assertEquals(listOf("DoVi"), state.badgesFor(stream(name = "DV")).map { it.name })

        fail = true
        state.refreshAll()!!.join()
        assertNotNull(state.error)
        assertEquals(2, state.sources.size)
        assertFalse(state.refreshing)
    }
}
