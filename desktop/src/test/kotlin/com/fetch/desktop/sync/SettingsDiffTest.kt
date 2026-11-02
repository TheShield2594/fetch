package com.fetch.desktop.sync

import com.fetch.desktop.data.DesktopSettings
import com.fetch.tv.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsDiffTest {

    @Test
    fun `only what changed here is pushed`() {
        val before = DesktopSettings()
        val after = before.copy(theme = AppTheme.OCEAN.name, showPosterLabels = false)
        val diff = after.toRemoteSharedSettings().changedFrom(before.toRemoteSharedSettings())
        assertEquals(RemoteSharedSettings(theme = "OCEAN", showPosterLabels = false), diff)
    }

    @Test
    fun `nothing changed means no push`() {
        val s = DesktopSettings()
        assertNull(s.toRemoteSharedSettings().changedFrom(s.toRemoteSharedSettings()))
    }
}
