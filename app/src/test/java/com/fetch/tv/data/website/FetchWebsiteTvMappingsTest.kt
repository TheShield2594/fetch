package com.fetch.tv.data.website

import com.fetch.tv.core.sync.SyncCatalogItem
import com.fetch.tv.core.sync.SyncHomeCatalogPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FetchWebsiteTvMappingsTest {

    // ─── Addons ───

    @Test
    fun `an addon base URL becomes its manifest address, query kept after it`() {
        assertEquals(
            "https://torrentio.example/debrid=KEY/manifest.json",
            FetchWebsiteTvMappings.toManifestUrl("https://torrentio.example/debrid=KEY/")
        )
        assertEquals(
            "https://addon.example/manifest.json?token=abc",
            FetchWebsiteTvMappings.toManifestUrl("https://addon.example?token=abc")
        )
        assertEquals(
            "https://addon.example/manifest.json",
            FetchWebsiteTvMappings.toManifestUrl("https://addon.example/manifest.json")
        )
    }

    @Test
    fun `a manifest address comes back as the base URL the TV stores`() {
        assertEquals("https://torrentio.example/debrid=KEY", FetchWebsiteTvMappings.toTvAddonUrl("https://torrentio.example/debrid=KEY/manifest.json"))
        assertEquals("https://addon.example?token=abc", FetchWebsiteTvMappings.toTvAddonUrl("https://addon.example/manifest.json?token=abc"))
    }

    // ─── Home layout ───

    private fun catalog(addonId: String, type: String, catalogId: String, enabled: Boolean = true, order: Int = 0, customTitle: String = "") =
        SyncCatalogItem(addonId = addonId, type = type, catalogId = catalogId, enabled = enabled, order = order, customTitle = customTitle)

    private fun collection(id: String, order: Int = 0) =
        SyncCatalogItem(addonId = "", type = "", catalogId = "", order = order, isCollection = true, collectionId = id)

    @Test
    fun `row keys match the website's, encodeURIComponent included`() {
        assertEquals("addon:org.x%3Ay:movie:top%20rated", FetchWebsiteTvMappings.rowKey(catalog("org.x:y", "movie", "top rated")))
        assertEquals("collection:abc", FetchWebsiteTvMappings.rowKey(collection("abc")))
        assertEquals("a-_.!~*'()%2F%C3%A9", FetchWebsiteTvMappings.encodeUriComponent("a-_.!~*'()/é"))
    }

    @Test
    fun `a push sends the TV's order and keeps the rows only the website places`() {
        val payload = SyncHomeCatalogPayload(
            items = listOf(
                catalog("com.cinemeta", "series", "top", order = 1, enabled = false),
                catalog("com.cinemeta", "movie", "top", order = 0, customTitle = "Films"),
                collection("c1", order = 2)
            )
        )
        val remote = listOf(
            WebsiteHomeRow(key = "builtin:continue_watching", source = "builtin"),
            WebsiteHomeRow(key = "addon:com.cinemeta:movie:top", source = "addon"),
            WebsiteHomeRow(key = "list:l1", hidden = true, title = "Watchlist", source = "list")
        )

        val rows = FetchWebsiteTvMappings.toWebsiteRows(payload, remote)

        assertEquals(
            listOf(
                "builtin:continue_watching" to false,
                "addon:com.cinemeta:movie:top" to false,
                "list:l1" to true,
                "addon:com.cinemeta:series:top" to true,
                "collection:c1" to false
            ),
            rows.map { it.key to it.hidden }
        )
        assertEquals("Films", rows.first { it.key == "addon:com.cinemeta:movie:top" }.title)
        // What the website calls a row's source is never sent back.
        assertEquals(listOf<String?>(null), rows.map { it.source }.distinct())
    }

    @Test
    fun `a pull takes the website's order and visibility, keeping titles renamed on the TV`() {
        val local = SyncHomeCatalogPayload(
            hideUnreleasedContent = true,
            items = listOf(catalog("com.cinemeta", "movie", "top", customTitle = "Films"), collection("c1", order = 1))
        )
        val rows = listOf(
            WebsiteHomeRow(key = "list:l1"),
            WebsiteHomeRow(key = "collection:c1", hidden = true),
            WebsiteHomeRow(key = "addon:com.cinemeta:movie:top"),
            WebsiteHomeRow(key = "addon:com.new%3Aaddon:series:latest")
        )

        val payload = FetchWebsiteTvMappings.fromWebsiteRows(rows, local)

        assertEquals(true, payload.hideUnreleasedContent)
        assertEquals(
            listOf("collection:c1", "addon:com.cinemeta:movie:top", "addon:com.new%3Aaddon:series:latest"),
            payload.items.map { FetchWebsiteTvMappings.rowKey(it) }
        )
        assertEquals(listOf(0, 1, 2), payload.items.map { it.order })
        assertEquals(false, payload.items[0].enabled)
        assertEquals("Films", payload.items[1].customTitle)
        assertEquals("com.new:addon", payload.items[2].addonId)
    }

    // ─── Settings ───

    private val json = Json

    private fun blob(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    private fun value(blob: JsonObject, feature: String, key: String): String? =
        ((blob["features"] as JsonObject)[feature] as? JsonObject)?.get(key)?.let { (it as JsonObject)["value"].toString() }

    @Test
    fun `a change on either side survives the other's`() {
        val base = blob("""{"version":1,"features":{"player_settings":{"a":{"type":"int","value":1},"b":{"type":"int","value":1}}}}""")
        val local = blob("""{"version":1,"features":{"player_settings":{"a":{"type":"int","value":2},"b":{"type":"int","value":1}}}}""")
        val remote = blob("""{"version":1,"features":{"player_settings":{"a":{"type":"int","value":1},"b":{"type":"int","value":3}}}}""")

        val merged = FetchWebsiteTvMappings.mergeSettings(base, local, remote)

        assertEquals("2", value(merged, "player_settings", "a"))
        assertEquals("3", value(merged, "player_settings", "b"))
    }

    @Test
    fun `a key reset on the TV is removed, one the TV never had is kept`() {
        val base = blob("""{"features":{"player_settings":{"size":{"type":"int","value":120}}}}""")
        val local = blob("""{"features":{"player_settings":{}}}""")
        val remote = blob("""{"features":{"player_settings":{"size":{"type":"int","value":120},"from_a_newer_tv":{"type":"boolean","value":true}}}}""")

        val merged = FetchWebsiteTvMappings.mergeSettings(base, local, remote)

        assertEquals(null, value(merged, "player_settings", "size"))
        assertEquals("true", value(merged, "player_settings", "from_a_newer_tv"))
    }

    @Test
    fun `features and fields this TV doesn't sync are the website's untouched`() {
        val local = blob("""{"version":1,"features":{"player_settings":{"a":{"type":"int","value":2}}}}""")
        val remote = blob("""{"version":2,"features":{"future_settings":{"x":{"type":"string","value":"y"}}},"extra":true}""")

        val merged = FetchWebsiteTvMappings.mergeSettings(base = null, local = local, remote = remote)

        assertEquals("\"y\"", value(merged, "future_settings", "x"))
        assertEquals("2", value(merged, "player_settings", "a"))
        assertEquals("true", merged["extra"].toString())
        assertEquals("2", merged["version"].toString())
    }
}
