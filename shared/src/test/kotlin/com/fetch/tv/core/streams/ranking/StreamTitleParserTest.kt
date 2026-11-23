package com.fetch.tv.core.streams.ranking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTitleParserTest {

    @Test
    fun `clientResolve isCached marks the stream debrid cached`() {
        fun stream(isCached: Boolean?) = com.fetch.tv.domain.model.Stream(
            name = "Fusion",
            title = "Movie.2026.2160p.WEB-DL.mkv",
            description = null,
            url = null,
            ytId = null,
            infoHash = "abc",
            fileIdx = 0,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Fusion",
            addonLogo = null,
            clientResolve = com.fetch.tv.domain.model.StreamClientResolve(
                type = "debrid",
                infoHash = "abc",
                fileIdx = 0,
                torrentName = null,
                filename = null,
                isCached = isCached
            )
        )
        assertTrue(StreamTitleParser.parse(stream(isCached = true)).isDebridCached)
        assertFalse(StreamTitleParser.parse(stream(isCached = false)).isDebridCached)
        assertFalse(StreamTitleParser.parse(stream(isCached = null)).isDebridCached)
        assertTrue(StreamTitleParser.parse(stream(isCached = false)).isDebridUncached)
        assertFalse(StreamTitleParser.parse(stream(isCached = null)).isDebridUncached)
    }

    @Test
    fun `an uncached marker on a bare torrent is not an uncached debrid link`() {
        fun stream(url: String?) = com.fetch.tv.domain.model.Stream(
            name = "MediaFusion 🧲  ⏳ 2160p",
            title = null,
            description = "🎨 DV|HDR 📺 BluRay REMUX 🎞️ hevc \n📦 85.37 GB 👤 20",
            url = url,
            ytId = null,
            infoHash = "a5d76d0c2f99b0119863e7da21803ff637d4d1d0",
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "MediaFusion",
            addonLogo = null
        )
        // The P2P result has no debrid link to wait for (#202).
        assertFalse(StreamTitleParser.parse(stream(url = null)).isDebridUncached)
        // The same marker on a debrid link still means it has to download first.
        assertTrue(StreamTitleParser.parse(stream(url = "https://example.invalid/playback/1")).isDebridUncached)
        // Text alone, with no stream to tell, keeps reading the marker.
        assertTrue(StreamTitleParser.parse("MediaFusion ⏳ 2160p").isDebridUncached)
    }

    @Test
    fun `parses torrentio style multi line title`() {
        val d = StreamTitleParser.parse(
            "Torrentio\n4k DV | HDR\n" +
                "Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.7.1.Atmos-FGT\n" +
                "👤 312 💾 58.2 GB ⚙️ ThePirateBay"
        )
        assertEquals(StreamResolution.UHD_2160, d.resolution)
        assertEquals(VideoCodec.HEVC, d.videoCodec)
        assertTrue(HdrFormat.DOLBY_VISION in d.hdr)
        assertTrue(HdrFormat.HDR10 in d.hdr)
        assertTrue(AudioFormat.TRUEHD in d.audio)
        assertTrue(d.hasAtmos)
        assertEquals(ReleaseSource.REMUX, d.source)
        assertEquals(312, d.seeders)
        assertEquals((58.2 * 1_073_741_824).toLong(), d.sizeBytes)
        assertEquals("FGT", d.releaseGroup)
        assertFalse(d.isDebridCached)
    }

    @Test
    fun `detects debrid cached markers`() {
        assertTrue(StreamTitleParser.parse("[RD+] Torrentio 1080p").isDebridCached)
        assertTrue(StreamTitleParser.parse("⚡ Comet 4K").isDebridCached)
        assertTrue(StreamTitleParser.parse("TB+ MediaFusion 720p").isDebridCached)
        assertFalse(StreamTitleParser.parse("[RD download] Torrentio 1080p").isDebridCached)
    }

    @Test
    fun `detects not yet cached debrid markers`() {
        listOf(
            "[RD download] Torrentio 1080p",
            "[RD⬇️] Comet 4K",
            "⏳ MediaFusion 1080p",
            "Movie 2024 1080p | Not cached",
            "Uncached 2160p"
        ).forEach { text ->
            val d = StreamTitleParser.parse(text)
            assertTrue(text, d.isDebridUncached)
            assertFalse("$text is not cached", d.isDebridCached)
        }
        assertFalse(StreamTitleParser.parse("[RD+] Torrentio 1080p").isDebridUncached)
        assertFalse(StreamTitleParser.parse("Movie 2024 1080p WEB-DL").isDebridUncached)
    }

    @Test
    fun `words in the film's title are not read as release tags`() {
        // TheShield2594/fetch#309
        val piranha = StreamTitleParser.parse("Piranha.3D.2010.1080p.BluRay.x264-GRP")
        assertFalse(piranha.is3d)
        assertEquals(ReleaseSource.BLURAY, piranha.source)
        assertFalse(StreamTitleParser.parse("Torrentio\n1080p\nStep Up 3D (2010) 1080p WEB-DL").is3d)
        assertEquals(ReleaseSource.WEB_DL, StreamTitleParser.parse("Cam.2018.1080p.NF.WEB-DL.DDP5.1.x264").source)
        assertTrue(StreamTitleParser.parse("The.Italian.Job.2003.1080p.BluRay.x264").languages.isEmpty())
        assertTrue(StreamTitleParser.parse("The French Dispatch 2021 2160p WEB-DL").languages.isEmpty())
        assertTrue(StreamTitleParser.parse("Chi-Raq.2015.720p.WEB-DL").languages.isEmpty())
        assertTrue(StreamTitleParser.parse("Show.Polish.Name.S01E02.1080p.WEB-DL").languages.isEmpty())
        // A title that is itself a year still has its tags read after the release year.
        assertEquals(setOf(StreamLanguage.ITALIAN), StreamTitleParser.parse("1917.2019.1080p.BluRay.ITA").languages)

        // The same tags after the title still count.
        assertTrue(StreamTitleParser.parse("Avatar.2009.3D.1080p.BluRay.Half-SBS.x264").is3d)
        assertEquals(ReleaseSource.CAM, StreamTitleParser.parse("Cam.2018.HDCAM.x264").source)
        assertEquals(setOf(StreamLanguage.FRENCH), StreamTitleParser.parse("The.Italian.Job.2003.FRENCH.1080p").languages)
        // Bracketed tags before the title, and flags anywhere, still count.
        assertEquals(setOf(StreamLanguage.ITALIAN), StreamTitleParser.parse("[ITA] Il Film 2010 1080p").languages)
        assertEquals(ReleaseSource.CAM, StreamTitleParser.parse("[HDCAM] New Movie 2025 720p").source)
        assertEquals(setOf(StreamLanguage.ITALIAN), StreamTitleParser.parse("🇮🇹 The French Dispatch 2021 1080p").languages)
        // A line with no year, episode or resolution is read whole.
        assertEquals(
            setOf(StreamLanguage.ITALIAN, StreamLanguage.ENGLISH),
            StreamTitleParser.parse("Torrentio\n1080p\nAudio: ITA / ENG").languages
        )
    }

    @Test
    fun `reads explicit dolby vision profiles`() {
        assertEquals(5, StreamTitleParser.parse("Show.S01E01.2160p.WEB-DL.DV.P5.DDP5.1.H.265").dolbyVisionProfile)
        assertEquals(7, StreamTitleParser.parse("Movie 2160p DoVi Profile 7 FEL HEVC").dolbyVisionProfile)
        assertEquals(8, StreamTitleParser.parse("Movie dvhe.08.06 2160p").dolbyVisionProfile)
        assertEquals(7, StreamTitleParser.parse("Movie 2160p BluRay DV FEL HDR10").dolbyVisionProfile)
        assertNull(StreamTitleParser.parse("Movie 2160p DV HDR").dolbyVisionProfile)
    }

    @Test
    fun `separates dd plus from dd and dts flavours`() {
        val ddp = StreamTitleParser.parse("Show.1080p.WEB-DL.DDP5.1.H.264-NTb")
        assertEquals(setOf(AudioFormat.EAC3), ddp.audio)
        assertEquals(VideoCodec.AVC, ddp.videoCodec)
        assertEquals(ReleaseSource.WEB_DL, ddp.source)

        assertEquals(setOf(AudioFormat.AC3), StreamTitleParser.parse("Movie.720p.HDTV.DD5.1.x264").audio)
        assertTrue(AudioFormat.DTS_HD_MA in StreamTitleParser.parse("Movie 1080p BluRay DTS-HD MA 5.1").audio)
        assertTrue(AudioFormat.DTS_X in StreamTitleParser.parse("Movie 2160p DTS-X 7.1").audio)
    }

    @Test
    fun `hdr10 plus is not reported as plain hdr10`() {
        val d = StreamTitleParser.parse("Movie.2160p.AMZN.WEB-DL.HDR10+.HEVC")
        assertEquals(setOf(HdrFormat.HDR10_PLUS), d.hdr)
    }

    @Test
    fun `sdr releases have no hdr`() {
        assertTrue(StreamTitleParser.parse("Movie 2160p SDR x265").hdr.isEmpty())
    }

    @Test
    fun `tokens inside words are ignored`() {
        val d = StreamTitleParser.parse("WebStreamr | Hdrezka mirror | Its Thunderbolts")
        assertEquals(ReleaseSource.UNKNOWN, d.source)
        assertTrue(d.hdr.isEmpty())
        assertEquals(StreamResolution.UNKNOWN, d.resolution)
    }

    @Test
    fun `cam and telesync are detected but file extensions are not`() {
        assertEquals(ReleaseSource.CAM, StreamTitleParser.parse("New.Movie.2025.HDCAM.x264").source)
        assertEquals(ReleaseSource.TELESYNC, StreamTitleParser.parse("New Movie 2025 TS 720p").source)
        assertEquals(ReleaseSource.UNKNOWN, StreamTitleParser.parse("stream_720p.ts").source)
    }

    @Test
    fun `size accepts comma decimals and megabytes`() {
        assertEquals((1.5 * 1_073_741_824).toLong(), StreamTitleParser.parse("💾 1,5 GB").sizeBytes)
        assertEquals(700L * 1_048_576, StreamTitleParser.parse("700 MB").sizeBytes)
    }

    @Test
    fun `summary labels are compact`() {
        val d = StreamTitleParser.parse("2160p DV HDR10 HEVC TrueHD Atmos REMUX 58 GB")
        assertEquals(listOf("4K", "DV", "HDR10", "HEVC", "TrueHD Atmos", "Remux", "58.0 GB"), d.summaryLabels())
    }

    @Test
    fun `reads audio languages from names, tags and flags`() {
        assertEquals(
            setOf(StreamLanguage.ITALIAN, StreamLanguage.ENGLISH),
            StreamTitleParser.parse("Movie.2024.1080p.WEB-DL.ITA.ENG.DDP5.1-GRP").languages
        )
        assertEquals(setOf(StreamLanguage.FRENCH), StreamTitleParser.parse("Film 2024 TRUEFRENCH 1080p").languages)
        assertEquals(
            setOf(StreamLanguage.SPANISH, StreamLanguage.LATINO),
            StreamTitleParser.parse("Torrentio\n1080p\n🇪🇸 / 🇲🇽 💾 2 GB").languages
        )
        assertEquals(setOf(StreamLanguage.MULTI), StreamTitleParser.parse("Movie 2160p Dual Audio HEVC").languages)
        // Two-letter codes are ordinary words in free text and are not read as languages.
        assertTrue(StreamTitleParser.parse("It Ends With Us 2024 1080p").languages.isEmpty())
    }

    // ── Cases from real addon output (see StreamTitleParserFixturesTest) ──────

    @Test
    fun `a pixel height beats a UHD source tag`() {
        assertEquals(StreamResolution.QHD_1440, StreamTitleParser.parse("The Shawshank Redemption (1994) UHD BDRip 1440p | 4K | HDR10").resolution)
        assertEquals(StreamResolution.FHD_1080, StreamTitleParser.parse("Movie (1994) UHD BDRip-HEVC 1080p | HDR").resolution)
        assertEquals(StreamResolution.UHD_2160, StreamTitleParser.parse("Movie 4k UHD HDR").resolution)
    }

    @Test
    fun `reads atmos and dolby vision glued together`() {
        val d = StreamTitleParser.parse("The Last of Us - S01E01 - [WEBDL-2160p]x265 EAC3 AtmosDV HDR10.mkv")
        assertTrue(d.hasAtmos)
        assertTrue(d.hasDolbyVision)
        assertTrue(HdrFormat.HDR10 in d.hdr)
    }

    @Test
    fun `reads anime style sources and leading groups`() {
        val d = StreamTitleParser.parse("[Judas] Sousou no Frieren (Season 01) [BD 1080p][HEVC x265 10bit][Dual-Audio][Eng-Subs] (Batch)")
        assertEquals(ReleaseSource.BLURAY, d.source)
        assertEquals("Judas", d.releaseGroup)
        assertEquals(ReleaseSource.BLURAY, StreamTitleParser.parse("[DB]Sousou no Frieren (Dual Audio 10bit BD1080p x265).mkv").source)
        assertEquals("SubsPlease", StreamTitleParser.parse("[SubsPlease] Sousou no Frieren - 01 (1080p) [F02B9CEE].mkv").releaseGroup)
        // A bracket that describes the file isn't a group.
        assertNull(StreamTitleParser.parse("[1080p] Some Show - 01.mkv").releaseGroup)
        assertEquals("FGT", StreamTitleParser.parse("[YTS] Movie.2024.1080p.WEB-DL-FGT").releaseGroup)
    }

    @Test
    fun `reads glued audio layouts and other rip names`() {
        assertEquals(setOf(AudioFormat.OPUS), StreamTitleParser.parse("Breaking.Bad.S01E01.1080p.WEBRIP.HEVC.OPUS2.0").audio)
        assertTrue(AudioFormat.OPUS in StreamTitleParser.parse("Movie.2160p.HDR10.BRRip.OPUS5.1.AV1").audio)
        assertEquals(ReleaseSource.BLURAY, StreamTitleParser.parse("[BluRayRip 2160p] [10 bit x265 HEVC HDR10]").source)
        assertEquals(ReleaseSource.BLURAY, StreamTitleParser.parse("Le.ali.della.liberta.1994.UHDRip.2160p.Hevc.HDR").source)
        assertEquals(ReleaseSource.REMUX, StreamTitleParser.parse("Oppenheimer.2023.2160p.IMAX.UHDRemux.HYBRID.HDR.DV").source)
    }

    @Test
    fun `multi subs are not multi audio`() {
        assertFalse(StreamLanguage.MULTI in StreamTitleParser.parse("Frieren - 01 [WebRip 2160p HEVC OPUS Multi-Subs]").languages)
        assertTrue(StreamLanguage.MULTI in StreamTitleParser.parse("Frieren.S01E01.2160p.WEB-DL.Multi.AUDIO.x265.Multi.Subs").languages)
        assertTrue(StreamLanguage.JAPANESE in StreamTitleParser.parse("[RUS(int) JAP+Sub] [2023 WEB-DL] [1080p]").languages)
    }

    @Test
    fun `reads a release group followed by the uploader's tag`() {
        assertEquals("TrollUHD", StreamTitleParser.parse("Breaking.Bad.S01E01.2160p.WEB-DL.DTS-HD.MA5.1.x264-TrollUHD [RiCK]").releaseGroup)
        assertEquals("Qman", StreamTitleParser.parse("The.Shawshank.Redemption.1994.BluRay.720p.x265.HEVC-Qman[UTR].mkv").releaseGroup)
        // A bracket straight after the dash is the tag itself, not a group.
        assertNull(StreamTitleParser.parse("Movie.2024.1080p.BluRay.x264.AAC5.1-[YTS.MX].mp4").releaseGroup)
    }

    @Test
    fun `reads dotted, hyphenated and ampersand release groups`() {
        fun group(name: String) = StreamTitleParser.parse(name).releaseGroup
        assertEquals("BEN.THE.MEN", group("Dune.Part.Two.2024.2160p.BluRay.REMUX.DV.HDR.DDP5.1.H265.MP4-BEN.THE.MEN.mp4"))
        assertEquals("MeM.GP", group("The.Last.of.Us.S01E01.ITA.ENG.2160p.UHD.BluRay.x265-MeM.GP.mkv"))
        assertEquals("Tsundere-Raws", group("Frieren S01E01 VOSTFR 1080p WEB x264 AAC -Tsundere-Raws (CR).mkv"))
        assertEquals("R&H", group("Breaking.Bad.S01-2160P.BluRay.10.bit.Encode.AV1.DTS.5.1-R&H"))
        // The extension is never part of a dotted group, and a web address is not a group.
        assertEquals("FGT", group("Movie.2024.1080p.WEB-DL.DDP5.1.H.264-FGT.mkv"))
        assertNull(group("Breaking.Bad.S01E01.4K.2160p.WEB-DL.5.1.x264.DUAL-WWW.BLUDV.COM.mkv"))
    }

    @Test
    fun `reads a release group closing the quality tags`() {
        fun group(name: String) = StreamTitleParser.parse(name).releaseGroup
        assertEquals("Tigole", group("Dune - Part Two (2024) (2160p BluRay x265 10bit HDR Tigole).mkv"))
        assertEquals("Silence", group("Breaking Bad (2008) - S01E01 - Pilot (1080p BluRay x265 Silence).mkv"))
        assertEquals("HONE", group("Breaking Bad (2008) S01E01 (2160p AMZN WEB-DL H265 SDR DDP 5.1 English - HONE).mkv"))
        assertEquals("TURG", group("Oppenheimer (2023) 1080p BluRay [EN-TRSub] DTS x264 - TURG.mkv"))
        assertEquals("GEGE", group("Le ali della libertà (1994) (1080p BluRay x265 HEVC 10bit ITA AC3) - GEGE [9.3 GB]"))
        // Without a resolution or codec before it, the closing word is part of the title.
        assertNull(group("Breaking Bad - S01E01 - Pilot.mkv"))
        assertNull(group("Frieren - Beyond Journey's End - Saison 1"))
        // A closing quality, language or subtitle tag is not a group.
        assertNull(group("Movie (2020) (1080p BluRay x265 HEVC).mkv"))
        assertNull(group("Movie (2020) (2160p WEB-DL H265 HDR).mkv"))
        assertNull(group("Movie (2020) (1080p BluRay x264 English).mkv"))
        assertNull(group("Movie 2020 1080p BluRay x264 - ESub.mkv"))
    }

    @Test
    fun `reads a two word group and a group alone in the closing bracket`() {
        fun group(name: String) = StreamTitleParser.parse(name).releaseGroup
        assertEquals("MeM GP", group("The Last Of Us S01 ITA ENG 2160p UHD BluRay x265-MeM GP"))
        // A language or tag after the group is not part of it.
        assertNull(group("Movie 2024 1080p BluRay x264-FGT EN"))
        assertEquals("Prof", group("The Shawshank Redemption (1994) (2160p x265 10bit HDR UHD BD DTS-HD MA 5.1) [Prof]"))
        assertEquals("PMR", group("Frieren - S01E01 (BD Remux 1080p AVC FLAC AAC) [Dual Audio] [PMR].mkv"))
        assertEquals("QxR", group("The Last of Us (2023) S01 (1080p BluRay x265 10bit EAC3 Atmos 7.1 Ghost) [QxR]"))
        assertEquals("SEV", group("Frieren - Episode 01 - The Journey's End 1080p BDRip x265 FLAC 2.0 Kira [SEV].mkv"))
        // A closing bracket that describes the file, or a CRC, is not a group.
        assertNull(group("Movie (2020) 2160p UHD Remux HEVC [Dual Audio]"))
        assertNull(group("Movie (2020) [UHD Remux 2160p HEVC DV-HDR10 ES DD+ 5.1][ES-EN]"))
        assertNull(group("Show S01 WEBRip 720p x264 AAC [EP01-04]"))
        assertNull(group("Movie 2020 1080p BluRay x265 [HDR10]"))
        assertNull(group("Movie 2020 1080p BluRay x265 [English]"))
        assertNull(group("Show - 01 (1080p HEVC) [F02B9CEE].mkv"))
        // Nor is a bracket on a name that gives no quality.
        assertNull(group("Breaking Bad - S01E01 - Pilot [Extended].mkv"))
        // Nor a release source, in a bracket or as the closing word.
        assertNull(group("Movie 2024 1080p x265 [DVD]"))
        assertNull(group("Movie 2024 1080p x265 [CAM]"))
        assertNull(group("Movie (2024) (1080p x264 DVD)"))
    }

    @Test
    fun `the rest of an audio tag is not a release group`() {
        fun group(name: String) = StreamTitleParser.parse(name).releaseGroup
        assertNull(group("Movie 2024 1080p BluRay x264 DTS-HD MA"))
        assertNull(group("Movie.2024.1080p.BluRay.x264.DTS-HD MA"))
        // A group after an audio tag is still one.
        assertEquals("FGT", group("Movie.2024.1080p.BluRay.DTS-FGT"))
        assertEquals("FGT", group("Movie.2024.1080p.BluRay.x264.DTS-HD.MA.5.1-FGT.mkv"))
    }

    @Test
    fun `reads release groups in other scripts`() {
        assertEquals("Нечипорук", StreamTitleParser.parse("Dune.Part.Two.2024.2160p.UHD.BDREMUX.HDR.DV.HEVC-Нечипорук.mkv").releaseGroup)
    }
}
