package com.nuvio.tv.core.streams.ranking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTitleParserTest {

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
        assertEquals(8, d.audioChannels)
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
        assertEquals(6, ddp.audioChannels)
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
}
