package com.fetch.desktop.addons

import com.fetch.desktop.data.DesktopSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AddonToolsTest {
    @Test
    fun `health keeps a window, a median, a failure rate and failures in a row`() {
        var h = AddonHealth("A")
        h = h.plus(AddonHealthSample(AddonRequestOutcome.SUCCESS, 100, 0), null)
        h = h.plus(AddonHealthSample(AddonRequestOutcome.SUCCESS, 300, 1_000), null)
        h = h.plus(AddonHealthSample(AddonRequestOutcome.TIMEOUT, 20_000, 2_000), "Timed out")
        assertEquals(200L, h.p50LatencyMs)
        assertEquals(1f / 3, h.failureRate, 0.001f)
        assertEquals(AddonHealthStatus.FAILING, h.status)
        assertEquals(1_000L, h.lastSuccessAtMs)
        // Failures within ten seconds of each other count once.
        h = h.plus(AddonHealthSample(AddonRequestOutcome.ERROR, 10, 3_000), "HTTP 500")
        assertEquals(1, h.consecutiveFailures)
        repeat(5) { i -> h = h.plus(AddonHealthSample(AddonRequestOutcome.ERROR, 10, 60_000L * (i + 1)), "HTTP 500") }
        assertTrue(h.shouldSuggestDisabling)
        assertEquals("HTTP 500", h.lastError)
        repeat(60) { h = h.plus(AddonHealthSample(AddonRequestOutcome.SUCCESS, 50, 1_000_000), null) }
        assertEquals(AddonHealth.WINDOW_SIZE, h.requests)
        assertEquals(0, h.consecutiveFailures)
    }

    @Test
    fun `health is kept across restarts and sorts worst first`() {
        val file = File(Files.createTempDirectory("health").toFile(), "addon-health.json")
        val tracker = AddonHealthTracker(file, saveDelayMs = 60_000)
        tracker.record("Slow", AddonRequestOutcome.SUCCESS, 9_000, nowMs = 10)
        tracker.record("Broken", AddonRequestOutcome.ERROR, 50, "HTTP 502", AddonRequestKind.CATALOG, nowMs = 20)
        tracker.record("Fine", AddonRequestOutcome.SUCCESS, 100, nowMs = 30)
        tracker.save()
        val reloaded = AddonHealthTracker(file).health
        assertEquals(setOf("Slow", "Broken", "Fine"), reloaded.keys)
        assertEquals(AddonRequestKind.CATALOG, reloaded["Broken"]!!.samples.single().kind)
        val names = listOf("Fine", "New", "Broken", "Slow")
        assertEquals(listOf("Slow", "Fine", "Broken", "New"), sortByHealth(names, { it }, { reloaded[it] }, AddonHealthSort.LATENCY))
        assertEquals(listOf("Broken", "Fine", "Slow", "New"), sortByHealth(names, { it }, { reloaded[it] }, AddonHealthSort.FAILURE_RATE))
        assertEquals(listOf("Broken", "Fine", "New", "Slow"), sortByHealth(names, { it }, { reloaded[it] }, AddonHealthSort.NAME))
        tracker.clear()
        assertTrue(tracker.health.isEmpty())
    }

    private val a = SyncableCatalog("u1|movie|top", "cinemeta", "movie", "top")
    private val b = SyncableCatalog("u1|series|top", "cinemeta", "series", "top")
    private val c = SyncableCatalog("u2|movie|new", "other", "movie", "new")
    private val all = listOf(a, b, c)

    private fun order(settings: DesktopSettings) =
        HomeCatalogOrder.ordered(all, settings, { it.key }, { it.tvKey }).map { it.catalogId + "/" + it.type }

    @Test
    fun `catalog order moves rows, keeps pins first and hides by either key`() {
        var s = DesktopSettings(pinnedCatalogs = listOf(c.key))
        assertEquals(listOf("new/movie", "top/movie", "top/series"), order(s))
        s = HomeCatalogOrder.move(s, all.map { it.ordered }, b.ordered, -1)
        assertEquals(listOf("new/movie", "top/series", "top/movie"), order(s))
        assertEquals(listOf("other_movie_new", "cinemeta_series_top", "cinemeta_movie_top"), s.catalogOrder)
        assertTrue(s.pinnedCatalogs.isEmpty())
        s = HomeCatalogOrder.setEnabled(s.copy(disabledCatalogs = listOf(a.tvKey)), a.ordered, enabled = true)
        assertFalse(HomeCatalogOrder.isDisabled(s, a.ordered))
        s = HomeCatalogOrder.setEnabled(s, a.ordered, enabled = false)
        assertTrue(HomeCatalogOrder.isDisabled(s, a.ordered))
        assertEquals(listOf("top/movie", "top/series", "new/movie"), order(HomeCatalogOrder.reset(s)))
    }

    @Test
    fun `the TV's payload applies here and goes back with its collections and titles`() {
        val remoteJson = Json.parseToJsonElement(
            """{"hide_unreleased_content":true,"extra_key":1,"items":[
              {"addon_id":"other","type":"movie","catalog_id":"new","enabled":true,"order":0,"custom_title":"Fresh"},
              {"addon_id":"cinemeta","type":"series","catalog_id":"top","enabled":false,"order":1},
              {"addon_id":"","type":"","catalog_id":"","enabled":true,"order":2,"is_collection":true,"collection_id":"col1"},
              {"addon_id":"cinemeta","type":"movie","catalog_id":"top","enabled":true,"order":3}]}"""
        ).jsonObject
        val (payload, hasHide) = HomeCatalogSync.decode(remoteJson)!!
        val local = DesktopSettings(pinnedCatalogs = listOf(a.key), disabledCatalogs = listOf("filter|x", a.key))
        val applied = HomeCatalogSync.apply(local, payload, hasHide)
        assertEquals(listOf("new/movie", "top/series", "top/movie"), order(applied))
        assertTrue(applied.hideUnreleased)
        assertEquals(listOf("filter|x", "cinemeta_series_top"), applied.disabledCatalogs)

        val back = HomeCatalogSync.payload(all, applied, payload)
        assertEquals(listOf("new", "top", "top", ""), back.items.map { it.catalogId })
        assertEquals("Fresh", back.items[0].customTitle)
        assertFalse(back.items.first { it.type == "series" }.enabled)
        assertEquals("col1", back.items.last().collectionId)
        assertEquals(listOf(0, 1, 2, 3), back.items.map { it.order })
        val merged: JsonObject = HomeCatalogSync.merged(remoteJson, back)
        assertTrue("extra_key" in merged)
        assertEquals(remoteJson, HomeCatalogSync.settingsJsonOf("""[{"profile_id":1,"settings_json":$remoteJson}]"""))
        assertNull(HomeCatalogSync.settingsJsonOf("[]"))
    }
}
