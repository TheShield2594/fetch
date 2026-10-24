package com.fetch.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPlayEarlyPickTest {

    private val addons = listOf("Torrentio", "Comet", "MediaFusion")

    @Test
    fun `no addon limit means every addon could still win`() {
        assertNull(
            StreamAutoPlaySelector.sourcesNeededForPick(
                selectedAddons = emptySet(),
                streamAddonNames = addons,
                serverSourceNames = emptyList()
            )
        )
    }

    @Test
    fun `limited addons plus media servers are all that must answer`() {
        assertEquals(
            setOf("Torrentio", "Jellyfin"),
            StreamAutoPlaySelector.sourcesNeededForPick(
                selectedAddons = setOf("Torrentio", "Uninstalled"),
                streamAddonNames = addons,
                serverSourceNames = listOf("Jellyfin")
            )
        )
    }

    @Test
    fun `a limit naming no stream addon falls back to waiting`() {
        assertNull(
            StreamAutoPlaySelector.sourcesNeededForPick(
                selectedAddons = setOf("Cinemeta"),
                streamAddonNames = addons,
                serverSourceNames = emptyList()
            )
        )
    }
}
