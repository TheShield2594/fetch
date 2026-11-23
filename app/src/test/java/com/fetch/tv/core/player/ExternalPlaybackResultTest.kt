package com.fetch.tv.core.player

import com.fetch.tv.data.repository.SkipInterval
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What ExternalPlaybackTracker makes of the result an external player hands back (#580). */
class ExternalPlaybackResultTest {

    private val hour = 60 * 60_000L

    @Test
    fun resultsAreClassifiedAsPlaceholderCompletedOrPartial() {
        val cases = listOf(
            // A debrid cache-sync clip played to its end is neither saved nor chained.
            ExternalPlayerResult(5_000, 5_000, endedByUser = false) to ExternalResultOutcome.PLACEHOLDER,
            ExternalPlayerResult(29_999, 29_999, endedByUser = false) to ExternalResultOutcome.PLACEHOLDER,
            ExternalPlayerResult(1_000, 20_000, endedByUser = true) to ExternalResultOutcome.PLACEHOLDER,
            ExternalPlayerResult(30_000, 30_000, endedByUser = false) to ExternalResultOutcome.COMPLETED,
            // A natural end counts whatever the position, and so does an end-only report.
            ExternalPlayerResult(10_000, hour, endedByUser = false) to ExternalResultOutcome.COMPLETED,
            ExternalPlayerResult(0, null, endedByUser = false) to ExternalResultOutcome.COMPLETED,
            ExternalPlayerResult(0, 0, endedByUser = false) to ExternalResultOutcome.COMPLETED,
            // Stopped by the user: completed from 90% on.
            ExternalPlayerResult(hour * 9 / 10, hour, endedByUser = true) to ExternalResultOutcome.COMPLETED,
            ExternalPlayerResult(hour * 9 / 10 - 1, hour, endedByUser = true) to ExternalResultOutcome.PARTIAL,
            ExternalPlayerResult(hour / 2, null, endedByUser = true) to ExternalResultOutcome.PARTIAL,
            ExternalPlayerResult(0, 0, endedByUser = true) to ExternalResultOutcome.PARTIAL
        )
        cases.forEach { (result, expected) -> assertEquals(result.toString(), expected, classifyExternalResult(result)) }
    }

    @Test
    fun onlyAPositionWithoutADurationIsBackfilled() {
        assertTrue(needsExternalDurationBackfill(ExternalPlayerResult(60_000, null)))
        assertTrue(needsExternalDurationBackfill(ExternalPlayerResult(60_000, 0)))
        assertTrue(needsExternalDurationBackfill(ExternalPlayerResult(60_000, -1)))
        assertFalse(needsExternalDurationBackfill(ExternalPlayerResult(60_000, hour)))
        assertFalse(needsExternalDurationBackfill(ExternalPlayerResult(0, null)))
    }

    @Test
    fun fallbackRuntimePrefersTheEpisodeAndThenTheTitle() {
        val meta = meta(
            runtime = "1h 5m",
            Video("s:1:1", "One", null, null, season = 1, episode = 1, overview = null, runtime = 42),
            Video("s:1:2", "Two", null, null, season = 1, episode = 2, overview = null)
        )
        assertEquals(42, externalFallbackRuntimeMinutes(meta, 1, 1))
        assertEquals(65, externalFallbackRuntimeMinutes(meta, 1, 2))
        assertEquals(65, externalFallbackRuntimeMinutes(meta, 2, 1))
        assertEquals(65, externalFallbackRuntimeMinutes(meta, null, null))
        assertEquals(42, externalFallbackRuntimeMinutes(meta.copy(runtime = null), 1, 1))
        assertNull(externalFallbackRuntimeMinutes(meta.copy(runtime = null), 1, 2))
        assertEquals(118, externalFallbackRuntimeMinutes(meta(runtime = "118 min"), null, null))
    }

    @Test
    fun scrobblePercentIsClampedAndNeedsADuration() {
        assertEquals(50f, externalScrobblePercent(30_000, 60_000), 0.001f)
        assertEquals(100f, externalScrobblePercent(90_000, 60_000), 0.001f)
        assertEquals(0f, externalScrobblePercent(-5, 60_000), 0.001f)
        assertEquals(0f, externalScrobblePercent(30_000, 0), 0.001f)
    }

    @Test
    fun skipSegmentsUseTheEndCreditsTypePlayersKnow() {
        val intervals = listOf(
            SkipInterval(0.0, 60.0, "intro", "introdb"),
            SkipInterval(5_000.0, 5_400.0, "movie-credits", "introdb")
        )
        assertEquals(listOf("intro", "end-credits"), externalSkipIntervals(intervals).map { it.type })
    }

    @Test
    fun playerTitlesNameTheEpisode() {
        val episode = ExternalPlaybackMetadata(
            "tt1", "series", "Show", null, null, null, "tt1:2:5", 2, 5, "The Title", "2020", 1
        )
        assertEquals("Show - S02E05", episode.buildPlayerTitle())
        assertEquals("Show - S02E05 - The Title", episode.buildPlayerTitle(includeEpisodeTitle = true))
        assertEquals("Show - S02E05", episode.copy(episodeTitle = " ").buildPlayerTitle(includeEpisodeTitle = true))
        assertEquals("Film", episode.copy(contentName = "Film", season = null, episode = null).buildPlayerTitle(true))
    }

    private fun meta(runtime: String?, vararg videos: Video) = Meta(
        id = "s", type = ContentType.SERIES, name = "Show", poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList(), runtime = runtime,
        director = emptyList(), cast = emptyList(), videos = videos.toList(), country = null,
        awards = null, language = null, links = emptyList()
    )
}
