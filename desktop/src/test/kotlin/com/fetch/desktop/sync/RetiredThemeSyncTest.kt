package com.fetch.desktop.sync

import com.fetch.desktop.data.DesktopSettings
import com.fetch.tv.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** Themes the TV retired (#455) arrive from older devices and old settings files as their nearest kept theme. */
class RetiredThemeSyncTest {

    @Test
    fun `a retired theme pulled from the blob becomes its nearest kept theme`() {
        assertEquals("AMBER", RemoteSharedSettings(theme = "GOLD").applyTo(DesktopSettings()).theme)
        assertEquals("OCEAN", RemoteSharedSettings(theme = "ARCTIC_BLUE").applyTo(DesktopSettings()).theme)
        assertEquals("WHITE", RemoteSharedSettings(theme = "GRAPHITE").applyTo(DesktopSettings()).theme)
    }

    @Test
    fun `an unknown theme in the blob leaves the setting alone`() {
        val settings = DesktopSettings(theme = AppTheme.EMERALD.name)
        assertEquals("EMERALD", RemoteSharedSettings(theme = "NEON").applyTo(settings).theme)
    }

    @Test
    fun `a retired theme in settings json reads and pushes as the kept one`() {
        val settings = DesktopSettings(theme = "JADE")
        assertEquals(AppTheme.EMERALD, settings.appTheme)
        assertEquals("EMERALD", settings.toRemoteSharedSettings().theme)
        assertEquals(AppTheme.FETCH, DesktopSettings(theme = "NEON").appTheme)
    }
}
