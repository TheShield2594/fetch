package com.fetch.desktop.sync

import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.PlayerExtras
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.SubtitleOrganization
import com.fetch.desktop.trailer.TrailerPlayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerExtrasSyncTest {

    private val tvBlob = Json.parseToJsonElement(
        """{"version":1,"features":{
            "player_settings":{
              "subtitle_show_only_preferred_languages":{"type":"boolean","value":true},
              "subtitle_organization_mode":{"type":"string","value":"BY_ADDON"},
              "subtitle_hdr_style_enabled":{"type":"boolean","value":true},
              "subtitle_hdr_text_color":{"type":"int","value":-10496},
              "subtitle_hdr_brightness":{"type":"int","value":60},
              "skip_silence":{"type":"boolean","value":true},
              "center_mix_level_db":{"type":"int","value":9}},
            "animeskip_settings":{"animeskip_enabled":{"type":"boolean","value":true}},
            "trailer_settings":{"trailer_enabled":{"type":"boolean","value":true},"trailer_delay_seconds":{"type":"int","value":10},"trailer_allow_4k":{"type":"boolean","value":true}}
        }}"""
    ).jsonObject

    @Test
    fun `the TV's keys are read and applied`() {
        val extras = SyncSettingsBlob.sharedSettings(tvBlob).playerExtras!!
        val applied = RemoteSharedSettings(playerExtras = extras).applyTo(DesktopSettings()).player.extras
        assertTrue(applied.subtitleOnlyPreferredLanguages)
        assertEquals(SubtitleOrganization.BY_ADDON, applied.subtitleOrganization)
        assertTrue(applied.subtitleHdrStyle)
        assertEquals("#FFD700", applied.subtitleHdrColor)
        assertEquals(60, applied.subtitleHdrBrightness)
        assertTrue(applied.skipSilence)
        assertTrue(applied.animeSkipEnabled)
        // Trailer on/off and delay are Layout's (LayoutSync), one setting as on TV.
        val full = SyncSettingsBlob.sharedSettings(tvBlob).applyTo(DesktopSettings())
        assertTrue(full.layout.trailersEnabled)
        assertEquals(10, full.layout.trailerDelaySeconds)
        assertEquals("device-only on TV: not synced", 0, applied.centerMixLevelDb)
        assertFalse("device-only on TV: not synced", applied.trailer4k)
        assertNull(SyncSettingsBlob.sharedSettings(JsonObject(emptyMap())).playerExtras)
    }

    @Test
    fun `pushing writes the TV's types and keeps the client id local`() {
        val desktop = DesktopSettings(
            player = PlayerSettings(extras = PlayerExtras(subtitleHdrColor = "#00E5FF", animeSkipEnabled = true, animeSkipClientId = "secret")),
            layout = com.fetch.desktop.data.LayoutPrefs(trailerDelaySeconds = 5)
        )
        val blob = SyncSettingsBlob.withSharedSettings(tvBlob, desktop.toRemoteSharedSettings())
        val features = blob["features"]!!.jsonObject
        val player = features["player_settings"]!!.jsonObject
        assertEquals("int", player["subtitle_hdr_text_color"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals((0xFF00E5FF).toInt().toString(), player["subtitle_hdr_text_color"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("BY_LANGUAGE", player["subtitle_organization_mode"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("the TV's own keys stay", "9", player["center_mix_level_db"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("5", features["trailer_settings"]!!.jsonObject["trailer_delay_seconds"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("true", features["trailer_settings"]!!.jsonObject["trailer_allow_4k"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertFalse(blob.toString().contains("secret"))
        assertFalse(features["animeskip_settings"]!!.jsonObject.containsKey("animeskip_client_id"))
    }

    @Test
    fun `only changed extras are pushed`() {
        val before = DesktopSettings().toRemoteSharedSettings()
        val after = DesktopSettings(player = PlayerSettings(extras = PlayerExtras(skipSilence = true))).toRemoteSharedSettings()
        assertEquals(RemotePlayerExtras(skipSilence = true), after.changedFrom(before)!!.playerExtras)
        assertNull(before.changedFrom(before))
    }

    @Test
    fun `trailers cap at 1080p unless 4K is on`() {
        assertTrue(TrailerPlayer.options(PlayerSettings()).contains("--ytdl-format=bestvideo[height<=?1080]+bestaudio/best[height<=?1080]/best"))
        assertTrue(TrailerPlayer.options(PlayerSettings(extras = PlayerExtras(trailer4k = true))).any { "2160" in it })
        assertEquals("https://www.youtube.com/watch?v=abc", TrailerPlayer.watchUrl("abc"))
    }
}
