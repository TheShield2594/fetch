package com.fetch.desktop.playback

import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SegmentType
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPlayTimingTest {
    private val movie = 6_000.0 // 100 minutes
    private val settings = AutoPlaySettings()

    @Test
    fun `the card shows at the movie threshold without credits`() {
        assertEquals(5_760.0, PostPlayTiming.triggerSec(movie, emptyList(), settings), 0.001) // 96 %
        assertFalse(PostPlayTiming.shouldShow(5_700.0, movie, emptyList(), settings))
        assertTrue(PostPlayTiming.shouldShow(5_760.0, movie, emptyList(), settings))
        assertEquals(4_800.0, PostPlayTiming.triggerSec(movie, emptyList(), settings.copy(postPlayMovieThresholdPercent = 10)), 0.001)
    }

    @Test
    fun `credits move it, unless a scene may follow them`() {
        val atTheEnd = listOf(Segment(SegmentType.OUTRO, 5_500.0, 5_990.0))
        assertEquals(5_500.0, PostPlayTiming.triggerSec(movie, atTheEnd, settings), 0.001)
        // Credits end two minutes early: the next-episode threshold (99 %, 60 s) from the end.
        val early = listOf(Segment(SegmentType.OUTRO, 5_500.0, 5_880.0))
        assertEquals(5_940.0, PostPlayTiming.triggerSec(movie, early, settings), 0.001)
    }

    @Test
    fun `it's looked up ahead and counts down in the last five seconds`() {
        assertTrue(PostPlayTiming.shouldPrefetch(5_460.0, movie, emptyList(), settings)) // 91 %
        assertFalse(PostPlayTiming.shouldPrefetch(1_000.0, movie, emptyList(), settings))
        assertTrue("ten minutes before the end", PostPlayTiming.shouldPrefetch(5_400.0, 6_000.0, emptyList(), settings.copy(postPlayMovieThresholdPercent = 100)))
        assertNull(PostPlayTiming.countdownSeconds(5_990.0, movie))
        assertEquals(5, PostPlayTiming.countdownSeconds(5_995.0, movie))
        assertEquals(1, PostPlayTiming.countdownSeconds(6_000.0, movie))
    }

    private fun preview(id: String, backdrop: String? = "b") = MetaPreview(
        id = id, type = ContentType.MOVIE, name = id, poster = null, posterShape = PosterShape.LANDSCAPE, background = backdrop,
        logo = null, description = null, releaseInfo = "2020", imdbRating = null, genres = emptyList()
    )

    @Test
    fun `candidates skip the movie, watched and hidden titles, and lead with a backdrop`() {
        val recs = listOf(preview("tt0"), preview("tt1", backdrop = null), preview("tt2"), preview("tt3"), preview("tt4"), preview("tt5"), preview("tt6"))
        val picked = PostPlayTiming.candidates(recs, setOf("tt0"), watched = { it.id == "tt2" }, allowed = { it.id != "tt3" }, hideUnreleased = false)
        assertEquals(listOf("tt4", "tt1", "tt5", "tt6"), picked.map { it.id })
        assertTrue(PostPlayTiming.candidates(emptyList(), emptySet(), { false }, { true }, false).isEmpty())
    }
}
