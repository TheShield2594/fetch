package com.fetch.tv.core.streams.ranking

import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.data.mediaserver.ServerPlaybackTarget
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRankerTest {

    private val dvTv = DeviceDecodeCapabilities(
        maxResolution = StreamResolution.UHD_2160,
        decodableCodecs = setOf(VideoCodec.AVC, VideoCodec.HEVC, VideoCodec.AV1),
        hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.DOLBY_VISION),
        dolbyVisionProfiles = setOf(5, 8)
    )

    private val fireStickLite = DeviceDecodeCapabilities(
        maxResolution = StreamResolution.FHD_1080,
        decodableCodecs = setOf(VideoCodec.AVC, VideoCodec.HEVC),
        hdrFormats = emptySet(),
        dolbyVisionProfiles = emptySet()
    )

    @Test
    fun `prefers 1080p hevc under 15 GB as configured`() {
        val rules = StreamRankingRules(
            preferredResolution = StreamResolution.FHD_1080,
            preferredCodecs = listOf(VideoCodec.HEVC),
            preferredMaxSizeGb = 15.0
        )
        val streams = listOf(
            stream("4K DV HDR HEVC 58 GB"),
            stream("1080p x264 BluRay 9 GB"),
            stream("1080p HEVC BluRay 32 GB"),
            stream("1080p HEVC WEB-DL 6 GB")
        )
        val best = StreamRanker.best(streams, rules, dvTv)
        assertEquals("1080p HEVC WEB-DL 6 GB", best?.stream?.name)
    }

    @Test
    fun `never auto plays dolby vision profile 5 on a device that cannot show it`() {
        val streams = listOf(
            stream("2160p WEB-DL DV P5 HEVC 12 GB"),
            stream("2160p WEB-DL HDR10 HEVC 14 GB")
        )
        val ranked = StreamRanker.rank(streams, StreamRankingRules(), fireStickLite.copy(maxResolution = StreamResolution.UHD_2160))
        assertEquals("2160p WEB-DL HDR10 HEVC 14 GB", ranked.first().stream.name)
        assertEquals(ExclusionReason.UNSUPPORTED_DOLBY_VISION, ranked.last().exclusion)
    }

    @Test
    fun `dv only label is treated as likely profile 5`() {
        val caps = fireStickLite.copy(maxResolution = StreamResolution.UHD_2160)
        val ranked = StreamRanker.rank(listOf(stream("4K DV WEB-DL")), StreamRankingRules(), caps)
        assertEquals(ExclusionReason.UNSUPPORTED_DOLBY_VISION, ranked.single().exclusion)
    }

    @Test
    fun `dv profile 8 with hdr10 base layer stays eligible without a dv decoder`() {
        val caps = fireStickLite.copy(maxResolution = StreamResolution.UHD_2160, hdrFormats = setOf(HdrFormat.HDR10))
        val ranked = StreamRanker.rank(listOf(stream("2160p DV HDR10 HEVC")), StreamRankingRules(), caps)
        assertTrue(ranked.single().isEligible)
    }

    @Test
    fun `dv with hdr10 plus is not auto played where that combination shows a black screen`() {
        val fireStick4k = dvTv.copy(dolbyVisionWithHdr10PlusBroken = true)
        val streams = listOf(stream("2160p DV HDR10+ HEVC REMUX"), stream("2160p DV HDR10 HEVC WEB-DL"))
        val ranked = StreamRanker.rank(streams, StreamRankingRules(), fireStick4k)
        assertEquals("2160p DV HDR10 HEVC WEB-DL", ranked.first().stream.name)
        assertEquals(ExclusionReason.UNSUPPORTED_DOLBY_VISION_HDR10_PLUS, ranked.last().exclusion)
        assertTrue(StreamRanker.rank(streams, StreamRankingRules(), dvTv).all { it.isEligible })
    }

    @Test
    fun `user rule excludes dolby vision profile 5 even on capable hardware`() {
        val rules = StreamRankingRules(excludedDolbyVisionProfiles = setOf(5))
        val best = StreamRanker.best(
            listOf(stream("2160p DV P5 HEVC"), stream("1080p HEVC")),
            rules,
            dvTv
        )
        assertEquals("1080p HEVC", best?.stream?.name)
    }

    @Test
    fun `drops codecs the device cannot decode`() {
        val ranked = StreamRanker.rank(listOf(stream("2160p AV1 10bit"), stream("720p x264")), StreamRankingRules(), fireStickLite)
        assertEquals("720p x264", ranked.first().stream.name)
        assertEquals(ExclusionReason.UNSUPPORTED_CODEC, ranked.last().exclusion)
    }

    @Test
    fun `resolution above what the display supports is not auto played`() {
        val best = StreamRanker.best(listOf(stream("2160p HEVC"), stream("1080p HEVC")), StreamRankingRules(), fireStickLite)
        assertEquals("1080p HEVC", best?.stream?.name)
    }

    @Test
    fun `own library file outranks any streamed result`() {
        val owned = stream("Jellyfin 720p x264").copy(
            serverTarget = ServerPlaybackTarget(ServerItemRef("home", "42"), mediaSourceId = null)
        )
        val ranked = StreamRanker.rank(
            listOf(stream("[RD+] 2160p DV HDR10 REMUX TrueHD Atmos"), owned),
            StreamRankingRules(),
            dvTv
        )
        assertEquals(owned, ranked.first().stream)
        assertTrue(RankReason.OWN_LIBRARY in ranked.first().reasons)
    }

    @Test
    fun `cached debrid beats uncached of the same quality`() {
        val best = StreamRanker.best(
            listOf(stream("1080p HEVC WEB-DL 👤 900"), stream("[RD+] 1080p HEVC WEB-DL")),
            StreamRankingRules(),
            dvTv
        )
        assertEquals("[RD+] 1080p HEVC WEB-DL", best?.stream?.name)
    }

    @Test
    fun `cam releases and excluded keywords are filtered`() {
        val rules = StreamRankingRules(excludedKeywords = listOf("HC"))
        val ranked = StreamRanker.rank(
            listOf(stream("1080p HDCAM"), stream("1080p WEB-DL HC"), stream("720p WEB-DL")),
            rules,
            dvTv
        )
        assertEquals("720p WEB-DL", ranked.first().stream.name)
        assertEquals(setOf(ExclusionReason.CAM_OR_TELESYNC, ExclusionReason.EXCLUDED_KEYWORD), ranked.drop(1).map { it.exclusion }.toSet())
    }

    @Test
    fun `max size is a hard cap and unknown size is allowed`() {
        val rules = StreamRankingRules(maxSizeGb = 20.0)
        val ranked = StreamRanker.rank(listOf(stream("2160p REMUX 70 GB"), stream("2160p WEB-DL")), rules, dvTv)
        assertEquals("2160p WEB-DL", ranked.first().stream.name)
        assertEquals(ExclusionReason.OVER_MAX_SIZE, ranked.last().exclusion)
    }

    @Test
    fun `behavior hint video size wins over text`() {
        val s = stream("1080p 1 GB").copy(
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoSize = 30L * 1_073_741_824
            )
        )
        val ranked = StreamRanker.rank(listOf(s), StreamRankingRules(maxSizeGb = 20.0), dvTv)
        assertEquals(ExclusionReason.OVER_MAX_SIZE, ranked.single().exclusion)
    }

    @Test
    fun `ties keep addon order`() {
        val ranked = StreamRanker.rank(listOf(stream("1080p A"), stream("1080p B")), StreamRankingRules(), dvTv)
        assertEquals(listOf("1080p A", "1080p B"), ranked.map { it.stream.name })
    }

    @Test
    fun `nothing eligible yields no best stream`() {
        assertNull(StreamRanker.best(listOf(stream("1080p")), isPlayable = { false }))
    }

    @Test
    fun `skips passthrough-only audio with no passthrough and no software decoder`() {
        val caps = dvTv.copy(passthroughAudio = emptySet(), softwareAudio = emptySet())
        val ranked = StreamRanker.rank(
            listOf(stream("2160p REMUX HEVC TrueHD Atmos 7.1"), stream("2160p WEB-DL HEVC DDP5.1")),
            StreamRankingRules(),
            caps
        )
        assertEquals("2160p WEB-DL HEVC DDP5.1", ranked.first().stream.name)
        assertEquals(ExclusionReason.UNSUPPORTED_AUDIO, ranked.last().exclusion)
    }

    @Test
    fun `passthrough or the ffmpeg decoder keeps lossless audio playable`() {
        val remux = listOf(stream("2160p REMUX HEVC DTS-HD MA 7.1"))
        val receiver = dvTv.copy(passthroughAudio = setOf(AudioFormat.DTS_HD_MA), softwareAudio = emptySet())
        val ffmpeg = dvTv.copy(passthroughAudio = emptySet(), softwareAudio = setOf(AudioFormat.DTS_HD_MA))
        assertTrue(StreamRanker.rank(remux, StreamRankingRules(), receiver).single().isEligible)
        assertTrue(StreamRanker.rank(remux, StreamRankingRules(), ffmpeg).single().isEligible)
    }

    @Test
    fun `a dts only receiver still plays the dts core of dts-hd`() {
        val caps = dvTv.copy(passthroughAudio = setOf(AudioFormat.DTS), softwareAudio = emptySet())
        val ranked = StreamRanker.rank(listOf(stream("2160p REMUX HEVC DTS-HD MA 7.1")), StreamRankingRules(), caps)
        assertTrue(ranked.single().isEligible)
    }

    @Test
    fun `a release with a compatible track besides truehd stays eligible`() {
        val caps = dvTv.copy(passthroughAudio = emptySet(), softwareAudio = emptySet())
        val ranked = StreamRanker.rank(listOf(stream("1080p BluRay TrueHD 7.1 AC3 5.1")), StreamRankingRules(), caps)
        assertTrue(ranked.single().isEligible)
    }

    @Test
    fun `own library audio is converted by the server so it is never ruled out`() {
        val caps = dvTv.copy(passthroughAudio = emptySet(), softwareAudio = emptySet())
        val owned = stream("4K TrueHD 7.1").copy(
            serverTarget = ServerPlaybackTarget(ServerItemRef("home", "42"), mediaSourceId = null)
        )
        assertTrue(StreamRanker.rank(listOf(owned), StreamRankingRules(), caps).single().isEligible)
    }

    @Test
    fun `ranked list says why each passed over stream was not picked`() {
        val dv5 = stream("2160p WEB-DL DV P5 HEVC")
        val hdr = stream("2160p WEB-DL HDR10 HEVC")
        val list = StreamRanker.rankedList(
            listOf(dv5, hdr),
            StreamRankingRules(),
            fireStickLite.copy(maxResolution = StreamResolution.UHD_2160)
        )
        assertEquals(listOf(hdr, dv5), list.streams)
        assertEquals(ExclusionReason.UNSUPPORTED_DOLBY_VISION, list.exclusionOf(dv5))
        assertNull(list.exclusionOf(hdr))
    }

    @Test
    fun `preferred audio language and release group lift a stream`() {
        val rules = StreamRankingRules(preferredLanguages = listOf(StreamLanguage.ITALIAN))
        val best = StreamRanker.best(
            listOf(stream("1080p WEB-DL x264 ENG-GRP"), stream("1080p WEB-DL x264 ITA ENG-GRP")),
            rules,
            dvTv
        )
        assertEquals("1080p WEB-DL x264 ITA ENG-GRP", best?.stream?.name)

        val grouped = StreamRanker.best(
            listOf(stream("Movie.2024.1080p.WEB-DL.x264-AAA"), stream("Movie.2024.1080p.WEB-DL.x264-FLUX")),
            StreamRankingRules(preferredReleaseGroups = listOf("flux")),
            dvTv
        )
        assertEquals("Movie.2024.1080p.WEB-DL.x264-FLUX", grouped?.stream?.name)
    }

    @Test
    fun `language rules only rule out releases that name their languages`() {
        val rules = StreamRankingRules(excludedLanguages = setOf(StreamLanguage.HINDI))
        val ranked = StreamRanker.rank(
            listOf(stream("1080p HINDI x264"), stream("1080p HINDI ENGLISH x264"), stream("1080p x264")),
            rules,
            dvTv
        ).associateBy { it.stream.name }
        assertEquals(ExclusionReason.LANGUAGE_EXCLUDED, ranked.getValue("1080p HINDI x264").exclusion)
        assertTrue(ranked.getValue("1080p HINDI ENGLISH x264").isEligible)
        assertTrue(ranked.getValue("1080p x264").isEligible)

        val onlyItalian = StreamRanker.rank(
            listOf(stream("1080p ENG x264"), stream("1080p MULTI x264")),
            StreamRankingRules(requiredLanguages = setOf(StreamLanguage.ITALIAN)),
            dvTv
        ).associateBy { it.stream.name }
        assertEquals(ExclusionReason.LANGUAGE_EXCLUDED, onlyItalian.getValue("1080p ENG x264").exclusion)
        assertTrue(onlyItalian.getValue("1080p MULTI x264").isEligible)
    }

    @Test
    fun `size, source, codec, audio and group exclusions`() {
        val rules = StreamRankingRules(
            minSizeGb = 2.0,
            excludedSources = setOf(ReleaseSource.HDTV),
            excludedCodecs = setOf(VideoCodec.AV1),
            excludedAudio = setOf(AudioFormat.AAC),
            excludedReleaseGroups = listOf("YIFY")
        )
        val ranked = StreamRanker.rank(
            listOf(
                stream("1080p WEB-DL x264 900 MB"),
                stream("1080p HDTV x264 4 GB"),
                stream("1080p WEB-DL AV1 4 GB"),
                stream("1080p WEB-DL x264 AAC 4 GB"),
                stream("1080p WEB-DL x264 AAC DDP5.1 4 GB"),
                stream("Movie.2020.1080p.BluRay.x264-YIFY")
            ),
            rules,
            dvTv
        ).associate { it.stream.name to it.exclusion }
        assertEquals(ExclusionReason.UNDER_MIN_SIZE, ranked["1080p WEB-DL x264 900 MB"])
        assertEquals(ExclusionReason.SOURCE_EXCLUDED, ranked["1080p HDTV x264 4 GB"])
        assertEquals(ExclusionReason.CODEC_EXCLUDED, ranked["1080p WEB-DL AV1 4 GB"])
        assertEquals(ExclusionReason.AUDIO_EXCLUDED, ranked["1080p WEB-DL x264 AAC 4 GB"])
        assertNull(ranked["1080p WEB-DL x264 AAC DDP5.1 4 GB"])
        assertEquals(ExclusionReason.RELEASE_GROUP_EXCLUDED, ranked["Movie.2020.1080p.BluRay.x264-YIFY"])
    }

    @Test
    fun `result limits move the rest to other sources best first`() {
        val ranked = StreamRanker.rank(
            listOf(
                stream("2160p HEVC WEB-DL"),
                stream("2160p HEVC BluRay"),
                stream("1080p HEVC WEB-DL"),
                stream("1080p HEVC BluRay"),
                stream("720p x264 WEB-DL")
            ),
            StreamRankingRules(maxPerResolution = 1, maxResults = 2),
            dvTv
        )
        assertEquals(2, ranked.count { it.isEligible })
        assertEquals(setOf(StreamResolution.UHD_2160, StreamResolution.FHD_1080), ranked.filter { it.isEligible }.map { it.descriptor.resolution }.toSet())
        assertTrue(ranked.filterNot { it.isEligible }.all { it.exclusion == ExclusionReason.OVER_RESULT_LIMIT })
    }

    @Test
    fun `allowed streams keep addon order outside best match`() {
        val first = stream("1080p HDTV x264")
        val second = stream("720p WEB-DL x264")
        val third = stream("2160p WEB-DL HEVC")
        val list = StreamRanker.rankedList(
            listOf(first, second, third),
            StreamRankingRules(excludedSources = setOf(ReleaseSource.HDTV)),
            dvTv,
            bestFirst = false
        )
        assertEquals(listOf(second, third, first), list.streams)
        assertEquals(ExclusionReason.SOURCE_EXCLUDED, list.exclusionOf(first))
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
