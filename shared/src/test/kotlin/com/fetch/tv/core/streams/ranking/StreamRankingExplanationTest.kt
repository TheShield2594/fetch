package com.fetch.tv.core.streams.ranking

import com.fetch.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamRankingExplanationTest {

    private val dvTv = DeviceDecodeCapabilities(
        maxResolution = StreamResolution.UHD_2160,
        decodableCodecs = setOf(VideoCodec.AVC, VideoCodec.HEVC, VideoCodec.AV1),
        hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.DOLBY_VISION),
        dolbyVisionProfiles = setOf(5, 8)
    )

    @Test
    fun `factor points add up to the score`() {
        val rules = StreamRankingRules(
            preferredCodecs = listOf(VideoCodec.HEVC),
            preferredMaxSizeGb = 10.0,
            preferredAudio = listOf(AudioFormat.TRUEHD)
        )
        val ranked = StreamRanker.rank(
            listOf(
                stream("2160p HDR10 HEVC BluRay TrueHD 40 GB"),
                stream("1080p x264 WEB-DL 👤 120"),
                stream("720p HEVC WEBRip [RD+] 2 GB")
            ),
            rules,
            dvTv
        )
        ranked.forEach { assertEquals(it.score, it.factors.values.sum()) }
    }

    @Test
    fun `a debrid link not cached yet is ordered by the cache, not the picture`() {
        val (ahead, behind) = rankTwo("[RD download] 2160p WEB-DL HEVC", "1080p WEB-DL HEVC")
        val comparison = StreamRanker.compare(ahead, behind)!!
        assertEquals("1080p WEB-DL HEVC", comparison.ahead.stream.name)
        assertEquals(RankFactor.DEBRID_CACHED, comparison.factor)
    }

    @Test
    fun `a cached marker breaks a tie between two otherwise equal files`() {
        val (ahead, behind) = rankTwo("1080p WEB-DL HEVC", "[RD+] 1080p WEB-DL HEVC")
        assertEquals("[RD+] 1080p WEB-DL HEVC", ahead.stream.name)
        assertEquals(RankFactor.DEBRID_CACHED, StreamRanker.decidingFactor(ahead, behind))
    }

    @Test
    fun `a cached marker never outweighs resolution`() {
        val (ahead, behind) = rankTwo("[RD+] 1080p WEB-DL HEVC", "2160p WEB-DL HEVC")
        assertEquals("2160p WEB-DL HEVC", ahead.stream.name)
        assertEquals(RankFactor.RESOLUTION, StreamRanker.decidingFactor(ahead, behind))
    }

    @Test
    fun `resolution orders two otherwise equal streams`() {
        val (ahead, behind) = rankTwo("1080p WEB-DL x264", "2160p WEB-DL x264")
        assertEquals("2160p WEB-DL x264", ahead.stream.name)
        assertEquals(RankFactor.RESOLUTION, StreamRanker.decidingFactor(ahead, behind))
    }

    @Test
    fun `hdr the screen can show orders two 4K files`() {
        val (ahead, behind) = rankTwo("2160p WEB-DL HEVC", "2160p WEB-DL HDR10 HEVC")
        assertEquals("2160p WEB-DL HDR10 HEVC", ahead.stream.name)
        assertEquals(RankFactor.HDR, StreamRanker.decidingFactor(ahead, behind))
    }

    @Test
    fun `a preferred codec names the codec`() {
        val rules = StreamRankingRules(preferredCodecs = listOf(VideoCodec.HEVC))
        val (ahead, behind) = rankTwo("1080p WEB-DL x264", "1080p WEB-DL HEVC", rules)
        assertEquals("1080p WEB-DL HEVC", ahead.stream.name)
        assertEquals(RankFactor.PREFERRED_CODEC, StreamRanker.decidingFactor(ahead, behind))
    }

    @Test
    fun `release source orders a bluray over a web copy`() {
        val (ahead, behind) = rankTwo("1080p WEB-DL x264", "1080p BluRay x264")
        assertEquals("1080p BluRay x264", ahead.stream.name)
        assertEquals(RankFactor.RELEASE_SOURCE, StreamRanker.decidingFactor(ahead, behind))
    }

    @Test
    fun `size over the preferred cap orders two files`() {
        val rules = StreamRankingRules(preferredMaxSizeGb = 10.0)
        val (ahead, behind) = rankTwo("1080p WEB-DL x264 30 GB", "1080p WEB-DL x264 6 GB", rules)
        assertEquals("1080p WEB-DL x264 6 GB", ahead.stream.name)
        assertEquals(RankFactor.SIZE, StreamRanker.decidingFactor(ahead, behind))
    }

    @Test
    fun `seeders order two uncached torrents`() {
        val (ahead, behind) = rankTwo("1080p WEB-DL x264 👤 20", "1080p WEB-DL x264 👤 400")
        assertEquals("1080p WEB-DL x264 👤 400", ahead.stream.name)
        assertEquals(RankFactor.SEEDERS, StreamRanker.decidingFactor(ahead, behind))
    }

    @Test
    fun `a preferred addon orders two copies of the same file`() {
        val rules = StreamRankingRules(preferredAddons = listOf("Comet"))
        val ranked = StreamRanker.rank(
            listOf(stream("1080p WEB-DL x264"), stream("1080p WEB-DL x264 ").copy(addonName = "Comet")),
            rules,
            dvTv
        )
        val comparison = StreamRanker.compare(ranked[1], ranked[0])!!
        assertEquals("Comet", comparison.ahead.stream.addonName)
        assertEquals(RankFactor.PREFERRED_ADDON, comparison.factor)
    }

    @Test
    fun `equal scores are ordered by the addons' own order`() {
        val first = stream("1080p WEB-DL x264")
        val second = stream("1080p WEB-DL x264 ")
        val ranked = StreamRanker.rank(listOf(first, second), StreamRankingRules(), dvTv)
        val comparison = StreamRanker.compare(ranked[1], ranked[0])!!
        assertSame(first, comparison.ahead.stream)
        assertEquals(RankFactor.ADDON_ORDER, comparison.factor)
    }

    @Test
    fun `the biggest lead decides, even when the other file wins on something else`() {
        // The other file has HDR, but the user's preferred language outweighs it.
        val rules = StreamRankingRules(preferredLanguages = listOf(StreamLanguage.ITALIAN))
        val (ahead, behind) = rankTwo("1080p WEB-DL HDR10 HEVC", "1080p WEB-DL x264 ITA", rules)
        assertEquals("1080p WEB-DL x264 ITA", ahead.stream.name)
        assert((behind.factors[RankFactor.HDR] ?: 0) > 0)
        assertEquals(RankFactor.LANGUAGE, StreamRanker.decidingFactor(ahead, behind))
    }

    @Test
    fun `every eligible pair names a factor that favours the one ranked first`() {
        val rules = StreamRankingRules(
            preferredCodecs = listOf(VideoCodec.HEVC),
            preferredMaxSizeGb = 20.0,
            preferredAudio = listOf(AudioFormat.EAC3)
        )
        val ranked = StreamRanker.rank(
            listOf(
                stream("2160p DV HDR10 HEVC WEB-DL DDP5.1 18 GB"),
                stream("2160p HEVC BluRay REMUX TrueHD 60 GB"),
                stream("1080p x264 WEB-DL [RD+] 5 GB"),
                stream("1080p HEVC WEBRip DDP5.1 👤 300"),
                stream("720p x264 HDTV"),
                stream("1080p x264 WEB-DL 5 GB")
            ),
            rules,
            dvTv
        ).filter { it.isEligible }

        for (i in ranked.indices) for (j in i + 1 until ranked.size) {
            val comparison = StreamRanker.compare(ranked[j], ranked[i])!!
            assertSame(ranked[i], comparison.ahead)
            val lead = (ranked[i].factors[comparison.factor] ?: 0) - (ranked[j].factors[comparison.factor] ?: 0)
            if (comparison.factor == RankFactor.ADDON_ORDER) {
                assertEquals(ranked[i].score, ranked[j].score)
            } else {
                assert(lead > 0) { "${comparison.factor} doesn't favour ${ranked[i].stream.name}" }
            }
        }
    }

    @Test
    fun `ruled out streams are not explained`() {
        val ranked = StreamRanker.rank(
            listOf(stream("1080p WEB-DL x264"), stream("1080p CAM x264")),
            StreamRankingRules(),
            dvTv
        )
        assertNull(StreamRanker.compare(ranked[0], ranked[1]))
        assertNull(StreamRanker.compare(ranked[0], ranked[0]))
    }

    @Test
    fun `a shown list explains the pick against the runner up and each row against the one above`() {
        val ranked = StreamRanker.rank(
            listOf(
                stream("[RD download] 2160p WEB-DL HDR10 HEVC"),
                stream("2160p WEB-DL x264"),
                stream("1080p BluRay x264"),
                stream("1080p WEB-DL HDR10 HEVC"),
                stream("1080p CAM x264")
            ),
            StreamRankingRules(),
            dvTv
        )
        val byName = ranked.associateBy { it.stream.name }
        // As the source list shows them: the pick, then 4K, then 1080p, then ruled-out ones.
        val shown = listOf(
            byName.getValue("2160p WEB-DL x264"),
            byName.getValue("[RD download] 2160p WEB-DL HDR10 HEVC"),
            byName.getValue("1080p WEB-DL HDR10 HEVC"),
            byName.getValue("1080p BluRay x264"),
            byName.getValue("1080p CAM x264")
        )

        val explained = StreamRanker.explainShown(shown)

        // The pick against the best of the rest (the 1080p HDR file), not just the row below it.
        assertSame(shown[2], explained[0]!!.behind)
        assertEquals(RankFactor.RESOLUTION, explained[0]!!.factor)
        // The uncached 4K file sits under the pick for its cache status.
        assertSame(shown[0], explained[1]!!.ahead)
        assertEquals(RankFactor.DEBRID_CACHED, explained[1]!!.factor)
        // Grouped under 1080p, the HDR file is shown below the uncached 4K one it outranks.
        assertSame(shown[2], explained[2]!!.ahead)
        assertEquals(RankFactor.DEBRID_CACHED, explained[2]!!.factor)
        assertSame(shown[2], explained[3]!!.ahead)
        assertEquals(RankFactor.HDR, explained[3]!!.factor)
        assertNull(explained[4])
    }

    @Test
    fun `a lone eligible stream has nothing to be compared with`() {
        val ranked = StreamRanker.rank(listOf(stream("1080p WEB-DL x264")), StreamRankingRules(), dvTv)
        assertEquals(listOf<RankComparison?>(null), StreamRanker.explainShown(ranked))
    }

    @Test
    fun `best first lists carry each stream's score, addon order lists don't`() {
        val streams = listOf(stream("1080p WEB-DL x264"), stream("2160p WEB-DL x264"))
        val bestFirst = StreamRanker.rankedList(streams, StreamRankingRules(), dvTv, bestFirst = true)
        assertEquals(streams.map { it.stableKey() }.toSet(), bestFirst.ranked.keys)
        val addonOrder = StreamRanker.rankedList(streams, StreamRankingRules(), dvTv, bestFirst = false)
        assertEquals(emptyMap<String, RankedStream>(), addonOrder.ranked)
    }

    /** Ranks two streams and returns them best first. */
    private fun rankTwo(
        a: String,
        b: String,
        rules: StreamRankingRules = StreamRankingRules()
    ): Pair<RankedStream, RankedStream> {
        val ranked = StreamRanker.rank(listOf(stream(a), stream(b)), rules, dvTv)
        check(ranked.all { it.isEligible }) { "both test streams should be eligible: $ranked" }
        return ranked[0] to ranked[1]
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
