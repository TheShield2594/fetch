package com.fetch.tv.ui.screens.stream

import com.fetch.tv.core.streams.BestMatchPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FetchingPanelDetailLineTest {

    private fun preview(labels: List<String>, specLabels: List<String>, addon: String?) = BestMatchPreview(
        videoId = "tt1",
        labels = labels,
        specLabels = specLabels,
        buttonLabels = listOf("4K", "Dolby Vision", "Atmos"),
        isCached = true,
        addonName = addon,
        sourceCount = 3,
        isFinal = false
    )

    @Test
    fun `size and addon join on one line`() {
        val best = preview(
            labels = listOf("4K", "DV P8", "TrueHD Atmos", "18.2 GB"),
            specLabels = listOf("4K", "DV P8", "TrueHD Atmos"),
            addon = "Torrentio"
        )
        assertEquals("18.2 GB · Torrentio", fetchingPanelDetailLine(best))
    }

    @Test
    fun `addon alone when the size is unknown`() {
        val best = preview(listOf("4K", "DV"), listOf("4K", "DV"), "Comet")
        assertEquals("Comet", fetchingPanelDetailLine(best))
    }

    @Test
    fun `nothing when neither is known`() {
        assertNull(fetchingPanelDetailLine(preview(listOf("1080p"), listOf("1080p"), null)))
    }
}
