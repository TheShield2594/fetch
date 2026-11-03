package com.fetch.desktop.data

import com.fetch.desktop.SettingsPage
import com.fetch.desktop.ui.FirstRunState
import com.fetch.desktop.ui.FirstRunStatus
import com.fetch.desktop.ui.FirstRunStep
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonResource
import com.fetch.tv.domain.model.ExperienceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExperienceSettingsTest {
    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun `a fresh install has no mode, so the wizard shows`() {
        val store = settingsFile(dir.root)
        assertNull(store.load().experience.mode)
        // Saved before the wizard finished, it still has none.
        store.save(store.load())
        assertNull(store.load().experience.mode)
    }

    @Test
    fun `a settings file from before the mode reads as advanced`() {
        java.io.File(dir.root, "settings.json").writeText("""{"theme":"OCEAN","showContinueWatching":false}""")
        val loaded = settingsFile(dir.root).load()
        assertEquals("OCEAN", loaded.theme)
        assertEquals(ExperienceMode.ADVANCED, loaded.experience.mode)
    }

    @Test
    fun `essential folds playback into one page`() {
        val hidden = ExperiencePolicy.hiddenPages(ExperienceMode.ESSENTIAL)
        assertTrue(SettingsPage.SUBTITLES in hidden && SettingsPage.YOUTUBE in hidden)
        assertFalse(SettingsPage.PLAYBACK_BASICS in hidden)
        assertEquals(setOf(SettingsPage.PLAYBACK_BASICS), ExperiencePolicy.hiddenPages(ExperienceMode.ADVANCED))
        assertEquals(SettingsPage.PLAYBACK_BASICS, ExperiencePolicy.substitute(SettingsPage.PLAYER, ExperienceMode.ESSENTIAL))
        assertEquals(SettingsPage.PLAYBACK_BASICS, ExperiencePolicy.substitute(SettingsPage.STREAM_BADGES, ExperienceMode.ESSENTIAL))
        assertEquals(SettingsPage.PLAYER, ExperiencePolicy.substitute(SettingsPage.PLAYBACK_BASICS, ExperienceMode.ADVANCED))
        assertEquals(SettingsPage.LAYOUT, ExperiencePolicy.substitute(SettingsPage.LAYOUT, ExperienceMode.ESSENTIAL))
        assertFalse(ExperiencePolicy.canReorderAddons(ExperienceMode.ESSENTIAL))
        assertTrue(ExperiencePolicy.canReorderAddons(ExperienceMode.ADVANCED))
    }

    private fun addon(id: String, vararg resources: String) = Addon(
        id = id, name = id, version = "1", description = null, logo = null, baseUrl = "https://$id",
        catalogs = emptyList(), types = emptyList(), resources = resources.map { AddonResource(it, emptyList(), null) }
    )

    @Test
    fun `essential addon setup shows until something can play`() {
        val essential = ExperienceSettings(ExperienceMode.ESSENTIAL)
        val catalogsOnly = listOf(addon("cinemeta", "catalog", "meta"), addon("opensubtitles", "subtitles"))
        assertTrue(needsEssentialAddonSetup(essential, catalogsOnly, addonsLoading = false, readOnly = false))
        assertFalse(needsEssentialAddonSetup(essential, catalogsOnly + addon("torrentio", "stream"), false, false))
        assertFalse("not while the addons load", needsEssentialAddonSetup(essential, emptyList(), true, false))
        assertFalse("put off", needsEssentialAddonSetup(essential.copy(addonSetupSkipped = true), emptyList(), false, false))
        assertFalse("advanced", needsEssentialAddonSetup(ExperienceSettings.Advanced, emptyList(), false, false))
        assertFalse("read-only profile", needsEssentialAddonSetup(essential, emptyList(), false, true))
    }

    @Test
    fun `a profile with limits only picks the experience`() {
        assertEquals(listOf(FirstRunStep.EXPERIENCE), FirstRunState.steps(restricted = true))
        assertEquals(FirstRunStep.entries, FirstRunState.steps(restricted = false))
        assertFalse(FirstRunState.isDone(FirstRunStep.SOURCES, FirstRunStatus()))
        assertTrue(FirstRunState.isDone(FirstRunStep.SOURCES, FirstRunStatus(mediaServers = listOf("Home"))))
        assertTrue(FirstRunState.isDone(FirstRunStep.METADATA, FirstRunStatus(trackingConnected = listOf("Trakt"))))
        assertFalse(FirstRunState.isDone(FirstRunStep.SERVER, FirstRunStatus()))
    }

    @Test
    fun `only the main profile's shared addons are read-only`() {
        val main = DesktopProfile(id = DesktopProfile.PRIMARY_ID, name = "Main", colorHex = "#FFFFFF")
        assertFalse(AddonManagementAccess.isReadOnly(main))
        assertTrue(AddonManagementAccess.isReadOnly(DesktopProfile(id = 2, name = "Kids", colorHex = "#FFFFFF", usesPrimaryAddons = true)))
        assertFalse(AddonManagementAccess.isReadOnly(DesktopProfile(id = 2, name = "Kids", colorHex = "#FFFFFF", usesPrimaryAddons = false)))
    }
}
