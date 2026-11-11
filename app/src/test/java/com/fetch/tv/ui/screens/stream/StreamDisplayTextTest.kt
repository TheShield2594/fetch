package com.fetch.tv.ui.screens.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamDisplayTextTest {

    @Test
    fun `drops a line that only repeats parsed specs`() {
        assertEquals("[RD+] Torrentio", withoutSpecOnlyLines("[RD+] Torrentio\n4k DV"))
        assertEquals("Torrentio", withoutSpecOnlyLines("Torrentio\n4k DV | HDR"))
        assertEquals("MediaFusion", withoutSpecOnlyLines("MediaFusion\n1080p HEVC DD+ 5.1"))
    }

    @Test
    fun `keeps lines that name the addon or the release`() {
        assertEquals("Comet 4K", withoutSpecOnlyLines("Comet 4K"))
        assertEquals(
            "Torrentio\nThe.Long.Field.S02E06.2160p.WEB-DL",
            withoutSpecOnlyLines("Torrentio\nThe.Long.Field.S02E06.2160p.WEB-DL")
        )
    }

    @Test
    fun `keeps the text when every line is specs`() {
        assertEquals("4K\nHDR", withoutSpecOnlyLines("4K\nHDR"))
    }

    @Test
    fun `a release file name may break only after its separators`() {
        val wrapped = wrappableReleaseName("The.Long.Field.S02E06.2160p.WEB-DL.mkv 18.2 GB")
        assertEquals("The.Long.Field.S02E06.2160p.WEB-DL.mkv 18.2 GB", wrapped.replace("\u200B", ""))
        assertEquals("The.\u200BLong.\u200BField.\u200BS02E06.\u200B2160p.\u200BWEB-\u200BDL.\u200Bmkv 18.2 GB", wrapped)
    }

    @Test
    fun `ordinary words are left alone`() {
        assertEquals("Comet 4K · Real-Debrid", wrappableReleaseName("Comet 4K · Real-Debrid"))
    }
}
