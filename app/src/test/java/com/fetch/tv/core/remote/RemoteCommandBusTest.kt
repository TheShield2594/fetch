package com.fetch.tv.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteCommandBusTest {

    @Test
    fun `a settings search waits for the settings screen and is taken once`() {
        val bus = RemoteCommandBus()

        bus.send(RemoteCommand.SettingsSearch("subtitles"))

        assertEquals("subtitles", bus.pendingSettingsSearch.value)
        assertEquals("subtitles", bus.consumePendingSettingsSearch())
        assertNull(bus.pendingSettingsSearch.value)
        assertNull(bus.consumePendingSettingsSearch())
    }

    @Test
    fun `content and settings searches don't take each other's query`() {
        val bus = RemoteCommandBus()

        bus.send(RemoteCommand.Search("dune"))

        assertNull(bus.pendingSettingsSearch.value)
        assertEquals("dune", bus.consumePendingSearch())
    }
}
