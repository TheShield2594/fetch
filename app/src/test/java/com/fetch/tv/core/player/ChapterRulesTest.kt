package com.fetch.tv.core.player

import com.fetch.tv.core.player.ChapterRules.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterRulesTest {

    private val chapters = ChapterRules.normalize(
        listOf(
            Chapter(600_000L, title = "Part 2"),
            Chapter(0L, title = "Part 1"),
            Chapter(1_200_000L, title = " "),
            Chapter(600_000L, title = "duplicate")
        ),
        durationMs = 1_800_000L
    )

    @Test
    fun `normalize sorts, drops duplicates and blank titles, and fills ends`() {
        assertEquals(
            listOf(
                Chapter(0L, 600_000L, "Part 1"),
                Chapter(600_000L, 1_200_000L, "Part 2"),
                Chapter(1_200_000L, 1_800_000L, null)
            ),
            chapters
        )
    }

    @Test
    fun `normalize drops chapters past the end and keeps a valid explicit end`() {
        val result = ChapterRules.normalize(
            listOf(Chapter(0L, endMs = 90_000L, title = "Intro"), Chapter(300_000L), Chapter(2_000_000L)),
            durationMs = 1_000_000L
        )
        assertEquals(listOf(Chapter(0L, 90_000L, "Intro"), Chapter(300_000L, 1_000_000L, null)), result)
    }

    @Test
    fun `next goes to the following chapter and is null in the last one`() {
        assertEquals(600_000L, ChapterRules.nextTargetMs(chapters, 10_000L))
        // Just short of a chapter start (a seek landing a frame early) still counts as in it.
        assertEquals(1_200_000L, ChapterRules.nextTargetMs(chapters, 599_900L))
        assertNull(ChapterRules.nextTargetMs(chapters, 1_500_000L))
    }

    @Test
    fun `previous restarts the chapter, or goes back one near its start`() {
        assertEquals(600_000L, ChapterRules.previousTargetMs(chapters, 700_000L))
        assertEquals(0L, ChapterRules.previousTargetMs(chapters, 601_000L))
        assertEquals(0L, ChapterRules.previousTargetMs(chapters, 1_000L))
        assertNull(ChapterRules.previousTargetMs(emptyList(), 1_000L))
    }

    @Test
    fun `ticks skip the start at zero and need a duration`() {
        assertEquals(listOf(600_000f / 1_800_000f, 1_200_000f / 1_800_000f), ChapterRules.tickFractions(chapters, 1_800_000L))
        assertTrue(ChapterRules.tickFractions(chapters, 0L).isEmpty())
    }

    @Test
    fun `chapter names map to segment kinds on the whole name only`() {
        assertEquals(SegmentKind.INTRO, ChapterRules.segmentKind("Opening", isMovie = false))
        assertEquals(SegmentKind.INTRO, ChapterRules.segmentKind("02. Intro", isMovie = false))
        assertEquals(SegmentKind.INTRO, ChapterRules.segmentKind("OP", isMovie = false))
        assertEquals(SegmentKind.INTRO, ChapterRules.segmentKind("Opening-Credits", isMovie = true))
        assertEquals(SegmentKind.RECAP, ChapterRules.segmentKind("Previously on Lost", isMovie = false))
        assertEquals(SegmentKind.CREDITS, ChapterRules.segmentKind("End Credits", isMovie = true))
        assertEquals(SegmentKind.CREDITS, ChapterRules.segmentKind("ED 2", isMovie = false))
        assertEquals(SegmentKind.CREDITS, ChapterRules.segmentKind("Ending", isMovie = false))
        assertNull(ChapterRules.segmentKind("Ending", isMovie = true))
        assertNull(ChapterRules.segmentKind("Intro to the Murder", isMovie = false))
        assertNull(ChapterRules.segmentKind("Post-Credits Scene", isMovie = true))
        assertNull(ChapterRules.segmentKind("Chapter 3", isMovie = false))
        assertNull(ChapterRules.segmentKind(null, isMovie = false))
    }

    @Test
    fun `skip intervals come from named chapters with sane bounds`() {
        val duration = 1_440_000L
        val episode = ChapterRules.normalize(
            listOf(
                Chapter(0L, title = "Prologue"),
                Chapter(60_000L, title = "Opening"),
                Chapter(150_000L, title = "Part A"),
                Chapter(1_300_000L, title = "Ending"),
                Chapter(1_390_000L, title = "Preview")
            ),
            duration
        )
        val intervals = ChapterRules.skipIntervals(episode, duration, isMovie = false)
        assertEquals(listOf("intro", "outro"), intervals.map { it.type })
        assertEquals(60.0, intervals[0].startTime, 0.0)
        assertEquals(150.0, intervals[0].endTime, 0.0)
        assertEquals(1_390.0, intervals[1].endTime, 0.0)
        assertTrue(intervals.all { it.provider == ChapterRules.SKIP_PROVIDER })
    }

    @Test
    fun `movie credits get the movie type and long or late intros are ignored`() {
        val duration = 6_000_000L
        val movie = ChapterRules.normalize(
            listOf(
                Chapter(0L, title = "Opening"), // 10 minutes long: a story chapter, not an intro
                Chapter(600_000L, title = "Act 1"),
                Chapter(5_000_000L, title = "Intro"), // second half: ignored
                Chapter(5_400_000L, title = "Credits")
            ),
            duration
        )
        val intervals = ChapterRules.skipIntervals(movie, duration, isMovie = true)
        assertEquals(listOf("movie-credits"), intervals.map { it.type })
        assertEquals(5_400.0, intervals.single().startTime, 0.0)
        assertTrue(ChapterRules.skipIntervals(movie, 0L, isMovie = true).isEmpty())
    }
}
