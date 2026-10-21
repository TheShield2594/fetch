package com.fetch.tv.core.streams

import com.fetch.tv.core.streams.ranking.StreamTitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BestMatchPreviewLabelsTest {

    @Test
    fun `play button keeps picture, hdr, sound and size but drops codec and source`() {
        val descriptor = StreamTitleParser.parse(
            "Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.7.1.Atmos-FGT 💾 58.2 GB"
        )

        val labels = descriptor.playButtonLabels()

        assertEquals("4K", labels.first())
        assertTrue(labels.any { it.startsWith("DV") })
        assertTrue(labels.any { it.contains("Atmos") })
        assertTrue(labels.last().endsWith("GB"))
        assertFalse(descriptor.videoCodec.label in labels)
        assertFalse(descriptor.source.label in labels)
    }

    @Test
    fun `unparseable text gives no labels`() {
        assertTrue(StreamTitleParser.parse("Some stream").playButtonLabels().isEmpty())
    }
}
