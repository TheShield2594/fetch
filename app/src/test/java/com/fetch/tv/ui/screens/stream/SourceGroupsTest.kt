package com.fetch.tv.ui.screens.stream

import com.fetch.tv.core.streams.ranking.StreamResolution.FHD_1080
import com.fetch.tv.core.streams.ranking.StreamResolution.HD_720
import com.fetch.tv.core.streams.ranking.StreamResolution.QHD_1440
import com.fetch.tv.core.streams.ranking.StreamResolution.UHD_2160
import com.fetch.tv.core.streams.ranking.StreamResolution.UNKNOWN
import com.fetch.tv.core.streams.ranking.DeviceDecodeCapabilities
import com.fetch.tv.core.streams.ranking.RankFactor
import com.fetch.tv.core.streams.ranking.StreamRanker
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `every eligible row is explained against the row shown above it, the pick against the next best`() {
        val streams = listOf(
            stream("1080p WEB-DL x264 [RD+]"),
            stream("2160p WEB-DL HDR10 HEVC"),
            stream("1080p BluRay x264"),
            stream("2160p WEB-DL x264"),
            stream("1080p CAM x264")
        )
        val list = StreamRanker.rankedList(streams, StreamRankingRules(), DeviceDecodeCapabilities.Permissive)
        val sections = sourceSections(list.streams, list.exclusions, bestMatchFirst = true, addonFilter = null)

        val explanations = sourceExplanations(list.streams, sections, list.ranked)

        val shown = sections.flatMap { it.indices }
        val pick = explanations.getValue(shown[0])
        assertTrue(pick.isFirst)
        assertEquals(RankFactor.HDR, pick.comparison.factor)
        val eligibleShown = shown.filter { list.exclusionOf(list.streams[it]) == null }
        eligibleShown.drop(1).forEach { index ->
            val explanation = explanations.getValue(index)
            assertTrue(!explanation.isFirst)
            assertTrue(explanation.comparison.ahead === explanation.self || explanation.comparison.behind === explanation.self)
        }
        // The cam copy is ruled out, so it isn't explained.
        assertEquals(eligibleShown.toSet(), explanations.keys)
    }

    @Test
    fun `addon order lists carry no explanations`() {
        val streams = listOf(stream("1080p WEB-DL x264"), stream("2160p WEB-DL x264"))
        val list = StreamRanker.rankedList(streams, StreamRankingRules(), DeviceDecodeCapabilities.Permissive, bestFirst = false)
        val sections = sourceSections(list.streams, list.exclusions, bestMatchFirst = false, addonFilter = null)
        assertEquals(emptyMap<Int, SourceExplanation>(), sourceExplanations(list.streams, sections, list.ranked))
    }

    private fun stream(name: String): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://example.com/${name.hashCode()}.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null
    )
}
