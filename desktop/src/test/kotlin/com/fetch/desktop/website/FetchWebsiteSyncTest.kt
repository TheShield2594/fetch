package com.fetch.desktop.website

import com.fetch.desktop.addons.SyncCatalogItem
import com.fetch.desktop.addons.SyncHomeCatalogPayload
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.ProfileLimits
import com.fetch.desktop.parental.PinGate
import com.fetch.desktop.sync.RemoteAddon
import com.fetch.desktop.sync.RemoteProfile
import com.fetch.desktop.sync.RemoteSharedSettings
import com.fetch.desktop.tracking.FakeHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FetchWebsiteSyncTest {
    private fun paired(fake: FakeHttp, now: () -> Long = { 0L }): FetchWebsiteState {
        val store = WebsiteStore(File(Files.createTempDirectory("web").toFile(), "website.json"))
        store.save(WebsiteFile(WebsiteSession("http://web.test", "tok", "PC")))
        return FetchWebsiteState(store, fake.client, now)
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    // --- Mappings ---

    @Test
    fun `the website's profiles replace the ones here, with its locks and limits`() {
        val local = listOf(
            DesktopProfile(1, "Me", "#1E88E5", pinHash = "local-hash"),
            DesktopProfile(2, "Kid", "#43A047", pinHash = "kid-hash", limits = ProfileLimits(dailyMinutes = 60)),
            DesktopProfile(3, "Guest", "#FB8C00")
        )
        val remote = listOf(
            WebsiteProfile(1, "p-main", "Me", hasPin = false, avatarColorHex = "#E53935"),
            WebsiteProfile(
                2, "p-kid", "Kiddo", hasPin = true, usesPrimaryAddons = true,
                parental = WebsiteParental(isKidsProfile = true, maxRating = "PARENTAL_GUIDANCE", allowUnrated = false)
            ),
            WebsiteProfile(4, "p-new", "New", hasPin = true)
        )
        val merged = FetchWebsiteMappings.mergeProfiles(local, remote)
        assertEquals(listOf(1, 2, 4), merged.map { it.id })
        val (me, kid, new) = merged
        // The website says the main profile has no PIN: the copy here goes.
        assertNull(me.pinHash)
        assertFalse(me.isLocked)
        assertEquals("#E53935", me.colorHex)
        // Locked there, with a PIN here: unlocks offline, no online check needed.
        assertEquals("kid-hash", kid.pinHash)
        assertFalse(kid.serverLocked)
        assertEquals("Kiddo", kid.name)
        assertEquals(ProfileLimits(maxRating = "PARENTAL_GUIDANCE", allowUnrated = false, dailyMinutes = 60, kidsProfile = true), kid.limits)
        // Locked there, never unlocked here: checked online.
        assertTrue(new.serverLocked)
        assertNull(new.pinHash)
    }

    @Test
    fun `an unknown rating from the website is read as the strictest`() {
        val limits = FetchWebsiteMappings.limits(ProfileLimits(), WebsiteParental(maxRating = "SOMETHING_NEW", allowUnrated = true))
        assertEquals("ALL_AGES", limits.maxRating)
        assertTrue(limits.allowUnrated)
        assertEquals(ProfileLimits(), FetchWebsiteMappings.limits(ProfileLimits(maxRating = "TEENS"), WebsiteParental()))
    }

    @Test
    fun `a profile is sent as the TV sends it, without anything the website can't draw`() {
        val body = FetchWebsiteMappings.toWebsiteBody(
            RemoteProfile(2, "Kid", "#43A047", usesPrimaryAddons = false, avatarId = "catalog-7", profileBackgroundId = "bg/../x"),
            ProfileLimits(maxRating = "TEENS", allowUnrated = false, kidsProfile = true)
        )
        assertEquals("Kid", body["name"]!!.jsonPrimitive.content)
        assertEquals("#43A047", body["avatarColorHex"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["avatarId"])
        assertEquals(JsonNull, body["profileBackgroundId"])
        assertEquals("false", body["usesPrimaryAddons"]!!.jsonPrimitive.content)
        val parental = body["parental"]!!.jsonObject
        assertEquals("TEENS", parental["maxRating"]!!.jsonPrimitive.content)
        assertEquals("false", parental["allowUnrated"]!!.jsonPrimitive.content)
        assertEquals("true", parental["isKidsProfile"]!!.jsonPrimitive.content)

        val primary = FetchWebsiteMappings.toWebsiteBody(RemoteProfile(1, "Me", "#1E88E5", usesPrimaryAddons = false, avatarId = "fetch:fox"), ProfileLimits())
        assertEquals("fetch:fox", primary["avatarId"]!!.jsonPrimitive.content)
        assertFalse("usesPrimaryAddons" in primary)
        assertEquals(JsonNull, primary["parental"]!!.jsonObject["maxRating"])
    }

    @Test
    fun `addons travel as manifest addresses and come back as base URLs`() {
        val remote = FetchWebsiteMappings.toRemoteAddons(
            listOf(
                WebsiteAddon("https://a.example/manifest.json", enabled = true),
                WebsiteAddon("https://b.example/conf/manifest.json?key=1", enabled = false, name = " ")
            )
        )
        assertEquals(listOf(RemoteAddon("https://a.example", 0, true), RemoteAddon("https://b.example/conf?key=1", 1, false)), remote)
        val sent = FetchWebsiteMappings.toWebsiteAddons(listOf(RemoteAddon("https://b.example/conf?key=1", 1, false, "Mine"), RemoteAddon("https://a.example/", 0)))
        assertEquals(
            listOf(WebsiteAddon("https://a.example/manifest.json"), WebsiteAddon("https://b.example/conf/manifest.json?key=1", false, "Mine")),
            sent
        )
    }

    @Test
    fun `Home rows only the website places keep their place, as do switched-off addons' rows`() {
        val payload = SyncHomeCatalogPayload(
            items = listOf(
                SyncCatalogItem("com.b", "series", "top", enabled = false, order = 1),
                SyncCatalogItem("com.a", "movie", "top", order = 0, customTitle = "Films"),
                SyncCatalogItem("", "", "", order = 2, isCollection = true, collectionId = "c 1")
            )
        )
        val remote = listOf(
            WebsiteHomeRow("continue-watching", source = "builtin"),
            WebsiteHomeRow("addon:com.off:movie:new", hidden = true),
            WebsiteHomeRow("addon:com.gone:movie:new"),
            WebsiteHomeRow("list:l1")
        )
        val rows = FetchWebsiteMappings.toWebsiteRows(payload, remote, installedAddonIds = setOf("com.a", "com.b", "com.off"))
        assertEquals(
            listOf("continue-watching", "addon:com.off:movie:new", "addon:com.a:movie:top", "list:l1", "addon:com.b:series:top", "collection:c%201"),
            rows.map { it.key }
        )
        assertEquals("Films", rows.first { it.key == "addon:com.a:movie:top" }.title)
        assertTrue(rows.first { it.key == "addon:com.b:series:top" }.hidden)
        assertNull(rows.first().source)
    }

    @Test
    fun `the website's Home order becomes the payload, keeping titles from here`() {
        val local = SyncHomeCatalogPayload(
            hideUnreleasedContent = true,
            items = listOf(SyncCatalogItem("com.a", "movie", "top", customTitle = "Films"))
        )
        val rows = listOf(
            WebsiteHomeRow("list:l1"),
            WebsiteHomeRow("collection:c%201", hidden = true),
            WebsiteHomeRow("addon:com.a:movie:top"),
            WebsiteHomeRow("addon:com.new:series:popular%3Aall")
        )
        val payload = FetchWebsiteMappings.fromWebsiteRows(rows, local)
        assertTrue(payload.hideUnreleasedContent)
        assertEquals(
            listOf(
                SyncCatalogItem("", "", "", enabled = false, order = 0, isCollection = true, collectionId = "c 1"),
                SyncCatalogItem("com.a", "movie", "top", order = 1, customTitle = "Films"),
                SyncCatalogItem("com.new", "series", "popular:all", order = 2)
            ),
            payload.items
        )
    }

    // --- Transport ---

    private val profilesJson =
        """{"profiles":[{"slot":1,"id":"p-main","name":"Me"},{"slot":2,"id":"p-kid","name":"Kid","hasPin":true}]}"""

    private val lockedJson = """{"error":"Profile PIN verification required","code":"profile_verification_required"}"""

    @Test
    fun `a locked profile has nothing to read until its PIN brings a token, which expires`() = runBlocking {
        var now = 0L
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/devices/me/profiles" -> 200 to profilesJson
                "/profiles/p-kid/verify" -> 200 to """{"profileToken":"pt-1"}"""
                "/profiles/p-kid/addons" ->
                    if (request.header("x-profile-token") == "pt-1") 200 to """{"addons":[{"manifestUrl":"https://a.example/manifest.json"}],"updatedAt":"2026-01-01T00:00:00Z"}"""
                    else 403 to lockedJson
                else -> 404 to "{}"
            }
        }
        val website = paired(fake) { now }
        assertNull(website.sync.addons(2))

        assertEquals(PinGate.Result.Ok, website.verifyPin(2, "1234"))
        assertEquals("""{"pin":"1234"}""", fake.sent.last().body)
        val unlocked = website.sync.addons(2)!!
        assertTrue(unlocked.everSet)
        assertEquals(listOf("https://a.example"), unlocked.addons.map { it.url })

        // The TV's eleven hours, then the PIN is needed again.
        now = FetchWebsiteState.PROFILE_TOKEN_TTL_MS + 1
        assertNull(website.profileToken("p-kid"))
        assertNull(website.sync.addons(2))
    }

    @Test
    fun `a wrong PIN and a lockout are answers, and a profile without a PIN has nothing to check`() = runBlocking {
        var answer = 401 to """{"error":"Incorrect PIN"}"""
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/devices/me/profiles" -> 200 to profilesJson
                "/profiles/p-kid/verify" -> answer
                else -> 404 to "{}"
            }
        }
        val website = paired(fake)
        assertEquals(PinGate.Result.Wrong("Incorrect PIN"), website.verifyPin(2, "0000"))
        answer = 429 to """{"error":"Too many incorrect attempts. Try again later.","retryAfterSec":120}"""
        assertEquals(PinGate.Result.Locked(120), website.verifyPin(2, "0000"))
        assertNull(website.verifyPin(1, "0000"))
        assertNull(website.profileToken("p-kid"))
    }

    @Test
    fun `a locked profile's list asks for its PIN, then loads with the token`() = runBlocking {
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/devices/me/profiles" -> 200 to profilesJson
                "/profiles/p-kid/verify" -> 200 to """{"profileToken":"pt-9"}"""
                "/stremio/list/l1" ->
                    if (request.header("x-profile-token") == "pt-9") 200 to """{"metas":[{"id":"tt1","type":"movie","name":"One"}]}"""
                    else 403 to lockedJson
                else -> 404 to "{}"
            }
        }
        val website = paired(fake)
        try {
            website.listItems(2, "l1", listOf("movie"))
            fail("expected the profile to be locked")
        } catch (e: WebsiteProfileLockedException) {
            assertEquals(2, e.slot)
            assertEquals("Kid", e.profileName)
        }
        website.verifyPin(2, "1234")
        assertEquals(listOf("tt1"), website.listItems(2, "l1", listOf("movie")).map { it.id })
    }

    @Test
    fun `settings are written into the website's blob with its version, once more after a race`() = runBlocking {
        var version = 3
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/devices/me/profiles" -> 200 to profilesJson
                "/profiles/p-main/settings/tv" -> when (request.method) {
                    "GET" -> 200 to """{"settings":{"version":1,"features":{"tv_only":{"k":{"type":"int","value":1}}}},"version":$version}"""
                    else -> if (request.header("If-Match") == "\"3\"") {
                        version = 4
                        412 to """{"error":"changed"}"""
                    } else {
                        200 to """{"settings":{},"version":5}"""
                    }
                }
                else -> 404 to "{}"
            }
        }
        val website = paired(fake)
        website.sync.pushSharedSettings(1, RemoteSharedSettings(showPosterLabels = false))
        val puts = fake.sent.filter { it.request.method == "PUT" }
        assertEquals(listOf("\"3\"", "\"4\""), puts.map { it.request.header("If-Match") })
        val features = json(puts.last().body)["settings"]!!.jsonObject["features"]!!.jsonObject
        // The TV's own keys are kept; only the changed one is set.
        assertEquals(1, features["tv_only"]!!.jsonObject.size)
        assertEquals("false", features["layout_settings"]!!.jsonObject["poster_labels_enabled"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a Home layout the website never had is given this computer's`() = runBlocking {
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/devices/me/profiles" -> 200 to profilesJson
                "/profiles/p-main/home-layout" ->
                    if (request.method == "GET") 200 to """{"rows":[{"key":"continue-watching","source":"builtin"}],"version":0}"""
                    else 200 to """{"rows":[],"version":1}"""
                else -> 404 to "{}"
            }
        }
        val website = paired(fake)
        val local = SyncHomeCatalogPayload(items = listOf(SyncCatalogItem("com.a", "movie", "top")))
        assertNull(website.sync.pullHomeLayout(1, local, setOf("com.a")))
        val put = fake.sent.single { it.request.method == "PUT" }
        assertEquals("\"0\"", put.request.header("If-Match"))
        val rows = json(put.body)["rows"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertEquals(listOf("continue-watching", "addon:com.a:movie:top"), rows)
        assertFalse("source" in json(put.body)["rows"]!!.jsonArray[0].jsonObject)
    }

    @Test
    fun `profiles go to their slots, and the sync is reported`() = runBlocking {
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/devices/me/profiles" -> 200 to profilesJson
                "/devices/me/profiles/1" -> 200 to """{"profile":{"slot":1,"id":"p-main","name":"Me"}}"""
                "/devices/me/profiles/2" -> 200 to """{"profile":{"slot":2,"id":"p-kid","name":"Kiddo","hasPin":true}}"""
                "/devices/me/report" -> 204 to ""
                else -> 404 to "{}"
            }
        }
        val website = paired(fake)
        website.sync.pushProfiles(listOf(DesktopProfile(1, "Me", "#1E88E5"), DesktopProfile(2, "Kiddo", "#43A047", usesPrimaryAddons = false)))
        val kid = fake.sent.single { it.request.url.encodedPath == "/devices/me/profiles/2" }
        assertEquals("PUT", kid.request.method)
        assertEquals("Kiddo", json(kid.body)["name"]!!.jsonPrimitive.content)
        assertEquals("false", json(kid.body)["usesPrimaryAddons"]!!.jsonPrimitive.content)
        assertFalse("pin" in json(kid.body))
        assertEquals("Kiddo", website.profileFor(2)!!.name)

        website.sync.report(IllegalStateException("boom"))
        val report = json(fake.sent.last().body)
        assertEquals("desktop", report["flavor"]!!.jsonPrimitive.content)
        assertEquals("false", report["ok"]!!.jsonPrimitive.content)
        assertEquals("boom", report["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a pushed profile names the one in use here, and carries a locked profile's own token`() = runBlocking {
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/devices/me/profiles" -> 200 to profilesJson
                "/devices/me/profiles/1" -> 200 to """{"profile":{"slot":1,"id":"p-main","name":"Me"}}"""
                "/devices/me/profiles/2" -> 200 to """{"profile":{"slot":2,"id":"p-kid","name":"Kiddo","hasPin":true}}"""
                "/devices/me/profiles/3" -> 201 to """{"profile":{"slot":3,"id":"p-new","name":"New"}}"""
                else -> 404 to "{}"
            }
        }
        val website = paired(fake)
        website.rememberProfileToken("p-kid", "kid-token")
        website.sync.pushProfiles(
            listOf(DesktopProfile(1, "Me", "#1E88E5"), DesktopProfile(2, "Kiddo", "#43A047"), DesktopProfile(3, "New", "#E53935")),
            actingSlot = 1
        )
        fun put(slot: Int) = fake.sent.single { it.request.url.encodedPath == "/devices/me/profiles/$slot" }.request
        assertEquals("p-main", put(2).header("x-profile-id"))
        assertEquals("kid-token", put(2).header("x-profile-token"))
        assertEquals("p-main", put(3).header("x-profile-id"))
        assertNull(put(3).header("x-profile-token"))
        assertEquals("New", website.profileFor(3)!!.name)
    }

    @Test
    fun `a profile the website won't add from a kids profile is left out, and the rest still go`() = runBlocking {
        val refused = """{"error":"Switch to a grown-up's profile","code":"parental_authority_required"}"""
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/devices/me/profiles" -> 200 to profilesJson
                "/devices/me/profiles/1" -> 200 to """{"profile":{"slot":1,"id":"p-main","name":"Mine"}}"""
                "/devices/me/profiles/2" -> 200 to """{"profile":{"slot":2,"id":"p-kid","name":"Kid","hasPin":true}}"""
                "/devices/me/profiles/3" -> 403 to refused
                else -> 404 to "{}"
            }
        }
        val website = paired(fake)
        website.sync.pushProfiles(
            listOf(DesktopProfile(3, "New", "#E53935"), DesktopProfile(1, "Mine", "#1E88E5")),
            actingSlot = 2
        )
        assertEquals("Mine", website.profileFor(1)!!.name)
        assertNull(website.profiles.firstOrNull { it.slot == 3 })
    }

    @Test
    fun `collections the website never had read as none, so this computer's go up`() = runBlocking {
        var updatedAt = "null"
        val fake = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/devices/me/profiles" -> 200 to profilesJson
                "/collections" -> 200 to """{"collections":[{"id":"c"}],"updatedAt":$updatedAt}"""
                else -> 404 to "{}"
            }
        }
        val remote = paired(fake).sync.collections(1)
        assertNull(remote.pull())
        assertEquals("p-main", fake.sent.last().request.header("x-profile-id"))
        updatedAt = "\"2026-01-01T00:00:00Z\""
        assertEquals(1, remote.pull()!!.size)
    }
}
