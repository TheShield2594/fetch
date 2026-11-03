package com.fetch.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameEpisodeMatcherTest {
    @Test
    fun `moves the episode on in the TV's patterns, keeping padding and casing`() {
        assertEquals("Show.S01E04.1080p.mkv", FilenameEpisodeMatcher.buildNextFilename("Show.S01E03.1080p.mkv", 1, 3, 1, 4))
        assertEquals("show s02e01 720p", FilenameEpisodeMatcher.buildNextFilename("show s01e10 720p", 1, 10, 2, 1))
        assertEquals("Show 1x04.mkv", FilenameEpisodeMatcher.buildNextFilename("Show 1x03.mkv", 1, 3, 1, 4))
        assertEquals("Anime - EP.13 [1080p].mkv", FilenameEpisodeMatcher.buildNextFilename("Anime - EP.12 [1080p].mkv", null, 12, null, 13))
        assertNull("another episode's file", FilenameEpisodeMatcher.buildNextFilename("Show.S01E05.mkv", 1, 3, 1, 4))
        assertNull(FilenameEpisodeMatcher.buildNextFilename("", 1, 3, 1, 4))
    }

    @Test
    fun `reads season and episode, and matches any case`() {
        assertEquals(2 to 6, FilenameEpisodeMatcher.extractSeasonEpisode("Show.S02E06.mkv"))
        assertEquals(null to 7, FilenameEpisodeMatcher.extractSeasonEpisode("Show E07.mkv"))
        assertTrue(FilenameEpisodeMatcher.matches("SHOW.S01E04.MKV", "show.s01e04.mkv"))
        assertFalse(FilenameEpisodeMatcher.matches(null, "a"))
    }
}
