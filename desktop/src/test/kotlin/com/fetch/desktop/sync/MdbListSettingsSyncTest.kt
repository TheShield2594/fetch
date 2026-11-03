package com.fetch.desktop.sync

import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.mdblist.MdbListSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbListSettingsSyncTest {
    private val tvBlob = Json.parseToJsonElement(
        """{"version":1,"features":{"mdblist_settings":{
            "mdblist_enabled":{"type":"boolean","value":true},
            "mdblist_show_trakt":{"type":"boolean","value":false},
            "mdblist_show_on_hero":{"type":"boolean","value":true},
            "mdblist_rating_order":{"type":"string","value":"tomatoes,imdb,,trakt"}}}}"""
    ).jsonObject

    @Test
    fun `the TV's MDBList settings apply here, keeping this profile's key`() {
        val local = DesktopSettings(mdblist = MdbListSettings(apiKey = "mine"))
        val applied = SyncSettingsBlob.sharedSettings(tvBlob).applyTo(local).mdblist
        assertTrue(applied.enabled)
        assertFalse(applied.showTrakt)
        assertTrue(applied.showOnHero)
        assertTrue("unset keys keep this app's value", applied.showImdb)
        assertEquals(listOf("tomatoes", "imdb", "trakt"), applied.ratingOrder)
        assertEquals("mine", applied.apiKey)
    }

    @Test
    fun `a change here pushes only that key, and never the API key`() {
        val before = DesktopSettings().toRemoteSharedSettings()
        val after = DesktopSettings(mdblist = MdbListSettings(enabled = true, apiKey = "secret")).toRemoteSharedSettings()
        val diff = after.changedFrom(before)!!
        assertEquals(mapOf("mdblist_enabled" to true), diff.mdblistFlags)
        val merged: JsonObject = SyncSettingsBlob.withSharedSettings(tvBlob, diff)
        val mdb = (merged["features"] as JsonObject)["mdblist_settings"] as JsonObject
        assertTrue("the TV's other keys stay", "mdblist_rating_order" in mdb)
        assertFalse("mdblist_api_key" in mdb)
        assertFalse(merged.toString().contains("secret"))
    }
}
