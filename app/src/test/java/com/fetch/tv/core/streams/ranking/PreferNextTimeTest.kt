package com.fetch.tv.core.streams.ranking

import com.fetch.tv.domain.model.Stream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferNextTimeTest {
    private val device = DeviceDecodeCapabilities.Permissive

    @After
    fun clear() = PreferNextTime.clear()

    @Test
    fun `offers the group, language, resolution and smaller size that set a pick apart`() {
        val best = StreamTitleParser.parse("Movie.2024.2160p.WEB-DL.DDP5.1.HEVC-AAA\n💾 40 GB")
        val picked = StreamTitleParser.parse("Movie.2024.1080p.WEB-DL.ITA.DDP5.1.x264-FLUX\n💾 8 GB")
        assertEquals(
            listOf(PreferenceSuggestion.ReleaseGroup("FLUX"), PreferenceSuggestion.Language(StreamLanguage.ITALIAN)),
            PreferNextTime.suggest(picked, best, StreamRankingRules())
        )
        assertEquals(
            listOf(PreferenceSuggestion.Resolution(StreamResolution.FHD_1080), PreferenceSuggestion.SizeUpTo(8.0)),
            PreferNextTime.suggest(
                picked,
                best,
                StreamRankingRules(preferredReleaseGroups = listOf("flux"), preferredLanguages = listOf(StreamLanguage.ITALIAN))
            )
        )
    }

    @Test
    fun `picking best match's own choice records nothing`() {
        val streams = listOf(stream("2160p WEB-DL HEVC-AAA"), stream("720p WEB-DL x264-BBB"))
        val best = StreamRanker.best(streams, StreamRankingRules(), device)!!.stream
        PreferNextTime.recordManualPick(best, streams, StreamRankingRules(), device)
        assertNull(PreferNextTime.take(maxAgeMs = 60_000))
    }

    @Test
    fun `a manual pick is offered once and only while it is recent`() {
        val streams = listOf(stream("2160p WEB-DL HEVC-AAA"), stream("Movie.720p.WEB-DL.x264-BBB"))
        PreferNextTime.recordManualPick(streams[1], streams, StreamRankingRules(), device, nowMs = 1_000)
        assertNull(PreferNextTime.take(maxAgeMs = 60_000, nowMs = 120_000))

        PreferNextTime.recordManualPick(streams[1], streams, StreamRankingRules(), device, nowMs = 1_000)
        assertEquals(
            PreferenceSuggestion.ReleaseGroup("BBB"),
            PreferNextTime.take(maxAgeMs = 60_000, nowMs = 2_000)?.suggestions?.first()
        )
        assertNull(PreferNextTime.take(maxAgeMs = 60_000, nowMs = 2_000))
    }

    @Test
    fun `applying a suggestion updates the rules`() {
        val rules = PreferenceSuggestion.ReleaseGroup("FLUX")
            .applyTo(StreamRankingRules(preferredReleaseGroups = listOf("AAA"), excludedReleaseGroups = listOf("flux")))
        assertEquals(listOf("FLUX", "AAA"), rules.preferredReleaseGroups)
        assertEquals(emptyList<String>(), rules.excludedReleaseGroups)
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
