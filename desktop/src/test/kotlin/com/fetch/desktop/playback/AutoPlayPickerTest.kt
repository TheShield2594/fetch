package com.fetch.desktop.playback

import com.fetch.desktop.data.AutoPlayMode
import com.fetch.desktop.data.DesktopDefaults
import com.fetch.tv.core.streams.ranking.StreamRanker
import com.fetch.tv.core.streams.ranking.StreamResolution
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlayPickerTest {

    private fun stream(
        name: String,
        group: String? = null,
        filename: String? = null,
        hash: String? = null,
        url: String? = "https://cdn.example/${name.replace(' ', '_')}.mkv",
        addon: String = "Addon"
    ) = Stream(
        name = name, title = null, description = null, url = url,
        ytId = null, infoHash = hash ?: if (url == null) "abc" else null, fileIdx = null, externalUrl = null,
        behaviorHints = StreamBehaviorHints(notWebReady = null, bingeGroup = group, countryWhitelist = null, proxyHeaders = null, filename = filename),
        addonName = addon, addonLogo = null
    )

    private fun rank(vararg streams: Stream) =
        StreamRanker.rank(streams.toList(), rules = DesktopDefaults.rankingRules, isPlayable = { it.url != null })

    private val rules = DesktopDefaults.rankingRules

    @Test
    fun `start early needs the preferred quality and, when preferred, a cached file`() {
        val wanted = rules.copy(preferredResolution = StreamResolution.FHD_1080, preferCachedDebrid = true)
        assertFalse(AutoPlayPicker.isGoodEnoughToStartEarly(stream("720p WEB [RD+]"), wanted))
        assertFalse("not cached", AutoPlayPicker.isGoodEnoughToStartEarly(stream("1080p BluRay"), wanted))
        assertTrue(AutoPlayPicker.isGoodEnoughToStartEarly(stream("1080p BluRay [RD+]"), wanted))
        assertTrue(AutoPlayPicker.isGoodEnoughToStartEarly(stream("1080p BluRay"), wanted.copy(preferCachedDebrid = false)))
        assertFalse("a torrent never starts early", AutoPlayPicker.isGoodEnoughToStartEarly(stream("2160p [RD+]", url = null), wanted))
    }

    @Test
    fun `the binge group prefers the next episode's file, then the same torrent, then any in the group`() {
        val hint = BingeHint("torrentio|1080p", filename = "Show.S01E03.1080p.WEB.mkv", infoHash = "pack")
        val ranked = rank(
            stream("2160p other", group = "other"),
            stream("1080p same group", group = "torrentio|1080p", filename = "Show.S01E04.1080p.OTHER.mkv"),
            stream("1080p same pack", group = "torrentio|1080p", hash = "PACK"),
            stream("1080p next file", group = "torrentio|1080p", filename = "Show.S01E04.1080p.WEB.mkv")
        )
        assertEquals("1080p next file", AutoPlayPicker.bingeMatch(ranked, hint, current = 1 to 3, next = 1 to 4)?.stream?.name)
        val noFile = rank(stream("1080p same group", group = "torrentio|1080p"), stream("1080p same pack", group = "torrentio|1080p", hash = "pack"))
        assertEquals("1080p same pack", AutoPlayPicker.bingeMatch(noFile, hint, 1 to 3, 1 to 4)?.stream?.name)
        assertEquals("1080p same group", AutoPlayPicker.bingeMatch(rank(stream("1080p same group", group = "torrentio|1080p")), hint, 1 to 3, 1 to 4)?.stream?.name)
        assertNull(AutoPlayPicker.bingeMatch(rank(stream("2160p other", group = "other")), hint, 1 to 3, 1 to 4))
    }

    @Test
    fun `the next episode falls back to the usual pick unless fallback is off`() {
        val hint = BingeHint("missing")
        val ranked = rank(stream("720p WEB"), stream("2160p REMUX"))
        fun pick(fallback: Boolean) = AutoPlayPicker.nextEpisodePick(
            ranked, AutoPlayMode.BEST_MATCH, "", hint, null, preferBingeGroup = true, fallback = fallback, current = 1 to 1, next = 1 to 2
        )
        assertEquals("2160p REMUX", pick(fallback = true)?.stream?.name)
        assertNull("the sources open instead", pick(fallback = false))
        // Without a group to look for, it's the usual pick; "Pick every time" still takes the best.
        assertEquals("2160p REMUX", AutoPlayPicker.nextEpisodePick(ranked, AutoPlayMode.MANUAL, "", null, null, true, false, 1 to 1, 1 to 2)?.stream?.name)
    }

    @Test
    fun `the same release by file name comes before the usual pick`() {
        val ranked = rank(stream("2160p REMUX"), stream("720p WEB", filename = "Show.S02E06.720p.mkv"))
        val pick = AutoPlayPicker.nextEpisodePick(ranked, AutoPlayMode.BEST_MATCH, "", null, "Show.S02E05.720p.mkv", true, true, 2 to 5, 2 to 6)
        assertEquals("720p WEB", pick?.stream?.name)
    }

    @Test
    fun `auto-play waits for every addon or the timeout, then picks or shows the sources`() {
        val ranked = rank(stream("720p WEB"), stream("2160p REMUX"))
        fun decide(all: Boolean, over: Boolean, mode: AutoPlayMode = AutoPlayMode.BEST_MATCH, early: Boolean = false, hint: BingeHint? = null, list: List<com.fetch.tv.core.streams.ranking.RankedStream> = ranked) =
            AutoPlayPicker.decide(list, mode, "", rules.copy(preferredResolution = StreamResolution.UHD_2160, preferCachedDebrid = false), early, hint, null, anyAnswered = list.isNotEmpty(), allAnswered = all, waitOver = over)
        assertEquals(AutoPlayDecision.Wait, decide(all = false, over = false))
        assertEquals("2160p REMUX", (decide(all = false, over = true) as AutoPlayDecision.Play).pick.stream.name)
        assertEquals("2160p REMUX", (decide(all = true, over = false) as AutoPlayDecision.Play).pick.stream.name)
        assertEquals("start early with a good enough best", "2160p REMUX", (decide(all = false, over = false, early = true) as AutoPlayDecision.Play).pick.stream.name)
        assertEquals(AutoPlayDecision.ShowSources, decide(all = true, over = false, list = emptyList()))
        assertEquals("nothing in yet: keep waiting past the timeout", AutoPlayDecision.Wait, decide(all = false, over = true, list = emptyList()))
        assertEquals(AutoPlayDecision.Wait, decide(all = false, over = true, mode = AutoPlayMode.MANUAL))
        assertEquals(AutoPlayDecision.ShowSources, decide(all = true, over = true, mode = AutoPlayMode.MANUAL))
    }

    @Test
    fun `a remembered binge group plays as soon as it shows up, even when picking by hand`() {
        val ranked = rank(stream("2160p REMUX"), stream("720p group", group = "g", filename = "Show.S01E05.mkv"))
        val decision = AutoPlayPicker.decide(
            ranked, AutoPlayMode.MANUAL, "", rules, false, BingeHint("g", filename = "Show.S01E04.mkv"), next = 1 to 5,
            anyAnswered = true, allAnswered = false, waitOver = false
        )
        assertEquals("720p group", (decision as AutoPlayDecision.Play).pick.stream.name)
    }

    @Test
    fun `binge hints come from a stream's behavior hints`() {
        assertEquals(BingeHint("g", "a.mkv", null), BingeHint.of(stream("x", group = " g ", filename = "a.mkv").let { it.copy(behaviorHints = it.behaviorHints?.copy(bingeGroup = "g")) }))
        assertNull(BingeHint.of(stream("x")))
    }
}
