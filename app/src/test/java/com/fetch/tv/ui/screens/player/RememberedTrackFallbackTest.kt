package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberedTrackFallbackTest {

    private fun track(index: Int, name: String, channels: Int? = null, codec: String? = null, describesVideo: Boolean = false) =
        TrackInfo(index = index, name = name, language = "en", channelCount = channels, codec = codec, describesVideo = describesVideo)

    private fun best(candidates: List<TrackInfo>, remembered: String?): TrackInfo =
        candidates.maxBy { RememberedTrackFallback.score(it, remembered, sameVariant = false) }

    @Test
    fun `commentary listed first does not become the remembered main track`() {
        // Same layout and codec on both, so only commentary decides.
        val tracks = listOf(track(0, "English Commentary (AC-3 5.1)"), track(1, "English (AC-3 5.1)"))
        assertEquals(1, best(tracks, "English (AC-3 5.1)").index)
    }

    @Test
    fun `audio description loses to the main track`() {
        val tracks = listOf(track(0, "English", describesVideo = true), track(1, "English"))
        assertEquals(1, best(tracks, "English (AC-3 5.1)").index)
    }

    @Test
    fun `a remembered commentary track prefers commentary`() {
        val tracks = listOf(track(0, "English (AC-3 5.1)"), track(1, "Director's Commentary"))
        assertEquals(1, best(tracks, "English Commentary (AAC 2.0)").index)
    }

    @Test
    fun `the same channel layout wins, then the same codec`() {
        val tracks = listOf(track(0, "English (AAC 2.0)"), track(1, "English", channels = 6), track(2, "English (AC-3 5.1)"))
        assertEquals(2, best(tracks, "English (AC-3 5.1)").index)
        assertEquals(0, best(tracks, "English (AAC stereo)").index)
    }

    @Test
    fun `ties keep the stream order`() {
        val tracks = listOf(track(0, "English"), track(1, "English"))
        assertEquals(0, best(tracks, null).index)
    }

    @Test
    fun `names are read for layout and codec`() {
        assertEquals(6, RememberedTrackFallback.channelCount("English (AC-3 5.1)"))
        assertEquals(8, RememberedTrackFallback.channelCount("TrueHD 7.1 Atmos"))
        assertEquals(2, RememberedTrackFallback.channelCount("Stereo"))
        assertNull(RememberedTrackFallback.channelCount("English"))
        assertEquals("eac3", RememberedTrackFallback.codecFamily("English (E-AC-3 5.1)"))
        assertEquals("ac3", RememberedTrackFallback.codecFamily("English (AC-3 5.1)"))
        assertTrue(RememberedTrackFallback.isCommentary("Commentary with the director"))
        assertFalse(RememberedTrackFallback.isCommentary("English"))
    }

    @Test
    fun `remembered languages match across engine language codes`() {
        assertTrue(rememberedLanguageMatches("eng", "en"))
        assertTrue(rememberedLanguageMatches("en", "eng"))
        assertTrue(rememberedLanguageMatches("jpn", "ja"))
        assertFalse(rememberedLanguageMatches("fre", "en"))
        assertFalse(rememberedLanguageMatches(null, "en"))
        assertTrue(rememberedLanguageMatches("de", null))
    }
}
