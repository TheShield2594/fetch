package com.fetch.tv.core.player

import com.fetch.tv.data.local.StreamAutoPlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPlayEarlyPickTest {

    private val addons = listOf("Torrentio", "Comet", "MediaFusion")

    @Test
    fun `no addon limit means every addon could still win`() {
        assertNull(
            StreamAutoPlaySelector.sourcesNeededForPick(
                source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                selectedAddons = emptySet(),
                streamAddonNames = addons,
                serverSourceNames = emptyList(),
                pluginSourceNames = emptyList()
            )
        )
    }

    @Test
    fun `limited addons plus media servers are all that must answer`() {
        assertEquals(
            setOf("Torrentio", "Jellyfin"),
            StreamAutoPlaySelector.sourcesNeededForPick(
                source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                selectedAddons = setOf("Torrentio", "Uninstalled"),
                streamAddonNames = addons,
                serverSourceNames = listOf("Jellyfin"),
                pluginSourceNames = listOf("Some plugin")
            )
        )
    }

    @Test
    fun `plug-ins in play keep auto-play waiting for everything`() {
        assertNull(
            StreamAutoPlaySelector.sourcesNeededForPick(
                source = StreamAutoPlaySource.ALL_SOURCES,
                selectedAddons = setOf("Torrentio"),
                streamAddonNames = addons,
                serverSourceNames = emptyList(),
                pluginSourceNames = listOf("Some plugin")
            )
        )
    }

    @Test
    fun `a limit naming no stream addon falls back to waiting`() {
        assertNull(
            StreamAutoPlaySelector.sourcesNeededForPick(
                source = StreamAutoPlaySource.ALL_SOURCES,
                selectedAddons = setOf("Cinemeta"),
                streamAddonNames = addons,
                serverSourceNames = emptyList(),
                pluginSourceNames = emptyList()
            )
        )
    }
}
