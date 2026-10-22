package com.fetch.tv.ui.screens.stream

import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceRulesTest {

    private fun stream(addon: String, title: String) = Stream(
        name = addon,
        title = title,
        description = null,
        url = "https://example.invalid/${title.hashCode()}",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = addon,
        addonLogo = null
    )

    @Test
    fun `a source offers its addon and its release group`() {
        val options = sourceRulesFor(stream("Comet", "Show.S01E01.1080p.WEB-DL-GRP"), StreamRankingRules())

        assertEquals(listOf(SourceRule.PreferAddon("Comet"), SourceRule.NeverReleaseGroup("GRP")), options)
    }

    @Test
    fun `rules already held are not offered again, whatever their case`() {
        val rules = StreamRankingRules(preferredAddons = listOf("comet"), excludedReleaseGroups = listOf("grp"))

        assertEquals(emptyList<SourceRule>(), sourceRulesFor(stream("Comet", "Show.S01E01.1080p.WEB-DL-GRP"), rules))
    }

    @Test
    fun `preferring an addon puts it first without repeating it`() {
        val rules = StreamRankingRules(preferredAddons = listOf("Torrentio", "COMET"))

        assertEquals(listOf("Comet", "Torrentio"), SourceRule.PreferAddon("Comet").applyTo(rules).preferredAddons)
    }

    @Test
    fun `skipping a release group also stops preferring it`() {
        val rules = StreamRankingRules(preferredReleaseGroups = listOf("GRP", "Other"))

        val updated = SourceRule.NeverReleaseGroup("grp").applyTo(rules)

        assertEquals(listOf("grp"), updated.excludedReleaseGroups)
        assertEquals(listOf("Other"), updated.preferredReleaseGroups)
    }
}
