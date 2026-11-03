package com.fetch.desktop.sync

import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.TmdbSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbSettingsSyncTest {

    private val tvBlob = Json.parseToJsonElement(
        """{"version":1,"features":{"tmdb_settings":{
            "tmdb_enabled":{"type":"boolean","value":true},
            "tmdb_language":{"type":"string","value":"pt-br"},
            "tmdb_use_collections":{"type":"boolean","value":false},
            "tmdb_modern_home_enabled":{"type":"boolean","value":true}}}}"""
    ).jsonObject

    @Test
    fun `the TV's TMDB settings apply here`() {
        val applied = SyncSettingsBlob.sharedSettings(tvBlob).applyTo(DesktopSettings())
        assertTrue(applied.tmdb.enabled)
        assertEquals("pt-br", applied.tmdb.language)
        assertFalse(applied.tmdb.useCollections)
        assertTrue("unset keys keep this app's value", applied.tmdb.useArtwork)
    }

    @Test
    fun `turning TMDB on here pushes only that key and keeps the TV's other TMDB keys`() {
        val before = DesktopSettings().toRemoteSharedSettings()
        val after = DesktopSettings(tmdb = TmdbSettings(enabled = true)).toRemoteSharedSettings()
        val diff = after.changedFrom(before)!!
        assertEquals(mapOf("tmdb_enabled" to true), diff.tmdbFlags)
        assertNull(diff.tmdbLanguage)

        val merged: JsonObject = SyncSettingsBlob.withSharedSettings(tvBlob, diff.copy(tmdbFlags = mapOf("tmdb_enabled" to false)))
        val tmdb = (merged["features"] as JsonObject)["tmdb_settings"] as JsonObject
        assertTrue("tmdb_modern_home_enabled" in tmdb)
        assertEquals(false, SyncSettingsBlob.sharedSettings(merged).tmdbFlags?.get("tmdb_enabled"))
    }
}

class ContinueWatchingSettingsSyncTest {
    @Test
    fun `Continue Watching layout settings travel with the TV's keys`() {
        val desktop = com.fetch.desktop.data.DesktopSettings(
            showUnairedNextUp = false,
            continueWatchingSort = com.fetch.tv.domain.model.ContinueWatchingSortMode.SPLIT_UPCOMING
        )
        val diff = desktop.toRemoteSharedSettings().changedFrom(com.fetch.desktop.data.DesktopSettings().toRemoteSharedSettings())!!
        val blob = SyncSettingsBlob.withSharedSettings(null, diff)
        val layout = (blob["features"] as JsonObject)["layout_settings"] as JsonObject
        assertEquals(setOf("show_unaired_next_up", "continue_watching_sort_mode"), layout.keys)
        val back = SyncSettingsBlob.sharedSettings(blob).applyTo(com.fetch.desktop.data.DesktopSettings())
        assertFalse(back.showUnairedNextUp)
        assertEquals(com.fetch.tv.domain.model.ContinueWatchingSortMode.SPLIT_UPCOMING, back.continueWatchingSort)
        assertTrue(back.nextUpFromFurthest)
    }
}
