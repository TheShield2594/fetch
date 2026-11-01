package com.fetch.tv.ui.screens.youtube

import com.fetch.tv.data.youtube.SponsorBlockCategory
import com.fetch.tv.data.youtube.SponsorBlockMode
import com.fetch.tv.data.youtube.YouTubeChapter
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.ui.screens.settings.nextSponsorBlockMode
import com.fetch.tv.ui.screens.settings.nextVideoHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** SponsorBlock modes, chapter navigation, and how speeds, counts and settings choices step and read. */
class YouTubePlayerControlsTest {

    private val sponsor = YouTubeSkipSegment(10_000, 40_000, "sponsor")
    private val intro = YouTubeSkipSegment(0, 8_000, "intro")
    private val unknown = YouTubeSkipSegment(50_000, 60_000, "something_new")

    @Test
    fun `each category follows its mode`() {
        val modes = mapOf(SponsorBlockCategory.SPONSOR to SponsorBlockMode.ASK, SponsorBlockCategory.INTRO to SponsorBlockMode.SKIP)
        assertNull("asked, not skipped", segmentToSkip(listOf(sponsor), 20_000, emptySet(), modes))
        assertEquals(sponsor, segmentToOffer(listOf(sponsor), 20_000, emptySet(), modes))
        assertEquals(intro, segmentToSkip(listOf(intro, sponsor), 1_000, emptySet(), modes))
        assertNull("an offer taken isn't offered again", segmentToOffer(listOf(sponsor), 20_000, setOf(sponsor), modes))
    }

    @Test
    fun `shown and off segments are neither skipped nor offered`() {
        val modes = mapOf(SponsorBlockCategory.SPONSOR to SponsorBlockMode.SHOW, SponsorBlockCategory.INTRO to SponsorBlockMode.OFF)
        assertNull(segmentToSkip(listOf(sponsor, intro), 20_000, emptySet(), modes))
        assertNull(segmentToOffer(listOf(sponsor, intro), 2_000, emptySet(), modes))
    }

    @Test
    fun `without a choice a category keeps its default, and unknown ones skip`() {
        assertEquals(SponsorBlockMode.SKIP, modeOf(sponsor, emptyMap()))
        assertEquals(SponsorBlockMode.OFF, modeOf(intro, emptyMap()))
        assertEquals(SponsorBlockMode.SKIP, modeOf(unknown, emptyMap()))
    }

    private val chapters = listOf(YouTubeChapter(0, "Intro"), YouTubeChapter(60_000, "Middle"), YouTubeChapter(120_000, "End"))

    @Test
    fun `the playing chapter is the last one started`() {
        assertEquals("Intro", chapterAt(chapters, 59_999)?.title)
        assertEquals("Middle", chapterAt(chapters, 60_000)?.title)
        assertEquals("End", chapterAt(chapters, 500_000)?.title)
        assertNull(chapterAt(emptyList(), 1_000))
    }

    @Test
    fun `next goes to the following chapter, none after the last`() {
        assertEquals(60_000L, nextChapterStart(chapters, 10_000))
        // Just short of a chapter's start counts as in it.
        assertEquals(120_000L, nextChapterStart(chapters, 59_500))
        assertNull(nextChapterStart(chapters, 130_000))
        assertNull(nextChapterStart(emptyList(), 0))
    }

    @Test
    fun `previous restarts the chapter, or goes back one just after its start`() {
        assertEquals(60_000L, previousChapterStart(chapters, 90_000))
        assertEquals(0L, previousChapterStart(chapters, 61_000))
        assertEquals(0L, previousChapterStart(chapters, 1_000))
        assertNull(previousChapterStart(emptyList(), 1_000))
    }

    @Test
    fun `speeds and counts read as YouTube writes them`() {
        assertEquals("1.5", formatSpeed(1.5f))
        assertEquals("2", formatSpeed(2f))
        assertEquals("0.25", formatSpeed(0.25f))
        assertEquals("999", formatCount(999, Locale.US))
        assertEquals("1.2K", formatCount(1_234, Locale.US))
        assertEquals("452K", formatCount(452_247, Locale.US))
        assertEquals("20M", formatCount(20_124_579, Locale.US))
        assertEquals("1.5B", formatCount(1_500_000_000, Locale.US))
    }

    @Test
    fun `the player's speeds include normal`() {
        assert(1f in YouTubeViewModel.PLAYBACK_SPEEDS)
    }

    @Test
    fun `settings choices step round`() {
        assertEquals(1440, nextVideoHeight(1080))
        assertEquals(720, nextVideoHeight(2160))
        assertEquals(720, nextVideoHeight(999))
        assertEquals(SponsorBlockMode.ASK, nextSponsorBlockMode(SponsorBlockMode.SKIP))
        assertEquals(SponsorBlockMode.SKIP, nextSponsorBlockMode(SponsorBlockMode.OFF))
    }
}
