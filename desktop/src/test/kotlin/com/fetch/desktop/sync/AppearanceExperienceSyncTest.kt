package com.fetch.desktop.sync

import com.fetch.desktop.data.AppearanceSettings
import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.ExperienceSettings
import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.ExperienceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceExperienceSyncTest {

    private val tvBlob = Json.parseToJsonElement(
        """{"version":1,"features":{
            "theme_settings":{
              "selected_theme":{"type":"string","value":"CUSTOM"},
              "custom_theme_colors":{"type":"string","value":"#B75AFF,#EC70A9,#FFB37A"},
              "selected_font":{"type":"string","value":"INTER"},
              "amoled_mode":{"type":"boolean","value":true},
              "amoled_surfaces_mode":{"type":"boolean","value":true},
              "high_contrast":{"type":"boolean","value":true},
              "app_icon":{"type":"string","value":"classic"}},
            "experience_settings":{
              "mode":{"type":"string","value":"ESSENTIAL"},
              "addon_setup_skipped":{"type":"boolean","value":true}}}}"""
    ).jsonObject

    @Test
    fun `the TV's theme and experience settings apply here`() {
        val applied = SyncSettingsBlob.sharedSettings(tvBlob).applyTo(DesktopSettings())
        assertEquals("CUSTOM", applied.theme)
        assertEquals("#B75AFF,#EC70A9,#FFB37A", applied.appearance.customThemeColors)
        assertEquals(AppFont.INTER, applied.appearance.appFont)
        assertTrue(applied.appearance.amoledMode && applied.appearance.amoledSurfaces && applied.appearance.highContrast)
        assertEquals(ExperienceMode.ESSENTIAL, applied.experience.mode)
        assertTrue(applied.experience.addonSetupSkipped)
    }

    @Test
    fun `unknown values from the TV leave this app's alone`() {
        val odd = RemoteSharedSettings(
            appearance = RemoteAppearance(font = "COMIC_SANS", customThemeColors = "not,a,colour"),
            experience = RemoteExperience(mode = "EXPERT")
        ).applyTo(DesktopSettings())
        assertEquals(AppearanceSettings(), odd.appearance)
        assertEquals(ExperienceMode.ADVANCED, odd.experience.mode)
    }

    @Test
    fun `a change here pushes only its key and keeps the TV's others`() {
        val before = DesktopSettings().toRemoteSharedSettings()
        val after = DesktopSettings(appearance = AppearanceSettings(highContrast = true), experience = ExperienceSettings(ExperienceMode.ESSENTIAL))
            .toRemoteSharedSettings()
        val diff = after.changedFrom(before)!!
        assertEquals(RemoteAppearance(highContrast = true), diff.appearance)
        assertEquals(RemoteExperience(mode = "ESSENTIAL"), diff.experience)
        assertNull(diff.theme)

        val merged = SyncSettingsBlob.withSharedSettings(tvBlob, diff)
        val theme = (merged["features"] as JsonObject)["theme_settings"] as JsonObject
        assertTrue("keys desktop doesn't have stay", "app_icon" in theme)
        assertEquals("INTER", SyncSettingsBlob.sharedSettings(merged).appearance?.font)
        assertEquals("ESSENTIAL", SyncSettingsBlob.sharedSettings(merged).experience?.mode)
    }

    @Test
    fun `a mode not chosen yet isn't pushed`() {
        val fresh = DesktopSettings(experience = ExperienceSettings.Fresh).toRemoteSharedSettings()
        assertNull(fresh.experience?.mode)
        val blob = SyncSettingsBlob.withSharedSettings(null, fresh)
        val experience = (blob["features"] as JsonObject)["experience_settings"] as JsonObject
        assertFalse("mode" in experience)
        assertEquals(setOf("addon_setup_skipped"), experience.keys)
    }

    @Test
    fun `a blob without these keys reads as unset`() {
        val shared = SyncSettingsBlob.sharedSettings(Json.parseToJsonElement("""{"features":{"theme_settings":{"selected_theme":{"type":"string","value":"OCEAN"}}}}""").jsonObject)
        assertNull(shared.appearance)
        assertNull(shared.experience)
    }
}
