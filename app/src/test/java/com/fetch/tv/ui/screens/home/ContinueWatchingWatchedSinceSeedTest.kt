package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// NuvioTV#3759: Trakt's seed stops one short of a season Fetch marked watched.
class ContinueWatchingWatchedSinceSeedTest {
    @Test
    fun `finale marked watched with the season is not up next`() {
        val watched = episodesWatchedSinceSeed(
            localWatched = (1..6).associate { (1 to it) to SEED_AT },
            seedLastWatched = SEED_AT
        )
        assertNull(resolveNextUpVideoFromMeta(seed(episode = 5), miniSeries(), false, watched))
    }

    @Test
    fun `a mark that reaches Fetch just before Trakt's watched time still counts`() {
        val watched = episodesWatchedSinceSeed(
            localWatched = mapOf((1 to 6) to SEED_AT - 2_000L),
            seedLastWatched = SEED_AT
        )
        assertEquals(setOf(1 to 6), watched)
    }

    @Test
    fun `a rewatch still goes on to the next episode watched long ago`() {
        val watched = episodesWatchedSinceSeed(
            localWatched = (1..6).associate { (1 to it) to SEED_AT - 30L * DAY_MS },
            seedLastWatched = SEED_AT
        )
        val next = resolveNextUpVideoFromMeta(seed(episode = 3), miniSeries(), false, watched)
        assertEquals(4, next?.episode)
    }

    @Test
    fun `skips only the watched episodes and keeps the first unwatched one`() {
        val watched = setOf(1 to 4, 1 to 5)
        val next = resolveNextUpVideoFromMeta(seed(episode = 3), miniSeries(), false, watched)
        assertEquals(6, next?.episode)
    }

    private fun seed(episode: Int) = WatchProgress(
        "tt1", "series", "Mini", null, null, null, "tt1:1:$episode", 1, episode, null,
        1, 1, SEED_AT, progressPercent = 100f
    )

    private fun miniSeries() = CwMetaSummary(
        "tt1", "Mini", null, null, null, null, emptyList(), null, null, null, null,
        (1..6).map { episode ->
            CwVideoSummary("tt1:1:$episode", "Episode $episode", "2020-01-01T00:00:00Z", null, 1, episode, null)
        }
    )

    private companion object {
        const val SEED_AT = 1_700_000_000_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
