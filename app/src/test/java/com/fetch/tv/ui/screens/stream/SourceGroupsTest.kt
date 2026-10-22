package com.fetch.tv.ui.screens.stream

import com.fetch.tv.core.streams.ranking.StreamResolution.FHD_1080
import com.fetch.tv.core.streams.ranking.StreamResolution.HD_720
import com.fetch.tv.core.streams.ranking.StreamResolution.QHD_1440
import com.fetch.tv.core.streams.ranking.StreamResolution.UHD_2160
import com.fetch.tv.core.streams.ranking.StreamResolution.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceGroupsTest {

    @Test
    fun `streams split by quality and keep their ranked order`() {
        val sections = groupSourcesByQuality(
            resolutions = listOf(FHD_1080, UHD_2160, HD_720, FHD_1080, UHD_2160, UNKNOWN),
            passedOver = List(6) { false },
            bestMatchFirst = false
        )

        assertEquals(
            listOf(
                SourceSection(SourceGroup.UHD, listOf(1, 4)),
                SourceSection(SourceGroup.FHD, listOf(0, 3)),
                SourceSection(SourceGroup.OTHER, listOf(2, 5))
            ),
            sections
        )
    }

    @Test
    fun `Best match's pick leads on its own, whatever its quality`() {
        val sections = groupSourcesByQuality(
            resolutions = listOf(FHD_1080, UHD_2160, FHD_1080),
            passedOver = List(3) { false },
            bestMatchFirst = true
        )

        assertEquals(SourceSection(SourceGroup.BEST_MATCH, listOf(0)), sections.first())
        assertEquals(SourceSection(SourceGroup.FHD, listOf(2)), sections.last())
    }

    @Test
    fun `passed-over streams stay together at the end`() {
        val sections = groupSourcesByQuality(
            resolutions = listOf(FHD_1080, UHD_2160, UHD_2160),
            passedOver = listOf(false, false, true),
            bestMatchFirst = true
        )

        assertEquals(
            listOf(SourceGroup.BEST_MATCH, SourceGroup.UHD, SourceGroup.PASSED_OVER),
            sections.map { it.group }
        )
        assertEquals(listOf(2), sections.last().indices)
    }

    @Test
    fun `1440p counts with 1080p, and a passed-over first stream is not the pick`() {
        val sections = groupSourcesByQuality(
            resolutions = listOf(QHD_1440, FHD_1080),
            passedOver = listOf(true, false),
            bestMatchFirst = true
        )

        assertEquals(
            listOf(SourceSection(SourceGroup.FHD, listOf(1)), SourceSection(SourceGroup.PASSED_OVER, listOf(0))),
            sections
        )
    }

    @Test
    fun `an empty list has no sections`() {
        assertEquals(emptyList<SourceSection>(), groupSourcesByQuality(emptyList(), emptyList(), true))
    }
}
