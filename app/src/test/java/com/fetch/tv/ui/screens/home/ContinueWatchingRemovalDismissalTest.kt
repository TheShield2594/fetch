package com.fetch.tv.ui.screens.home

import com.fetch.tv.data.local.NextUpDismissals
import com.fetch.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TheShield2594/fetch#801: removing an in-progress episode must not return as Up Next. */
class ContinueWatchingRemovalDismissalTest {

    private fun progress(
        contentType: String = "series",
        season: Int? = 2,
        episode: Int? = 5
    ) = WatchProgress(
        contentId = "tt123",
        contentType = contentType,
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt123:2:5",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 600_000L,
        duration = 2_400_000L,
        lastWatched = 1L
    )

    private fun dismissalsFor(progress: WatchProgress): NextUpDismissals {
        val dismissal = requireNotNull(inProgressRemovalDismissal(progress, nowMs = 42L))
        return NextUpDismissals(mapOf(dismissal.contentId to dismissal))
    }

    @Test
    fun `removed episode is not rebuilt as Up Next from earlier watched episodes`() {
        val dismissals = dismissalsFor(progress())
        // E1-E4 watched: Up Next seeds from S2E4 and offers S2E5.
        assertTrue(
            dismissals.hides("tt123", seedSeason = 2, seedEpisode = 4, season = 2, episode = 5, hasAired = true)
        )
    }

    @Test
    fun `show comes back once the user watches past the removed episode`() {
        val dismissals = dismissalsFor(progress())
        assertFalse(
            dismissals.hides("tt123", seedSeason = 2, seedEpisode = 5, season = 2, episode = 6, hasAired = true)
        )
    }

    @Test
    fun `dismissal records the removed episode and title`() {
        val dismissal = requireNotNull(inProgressRemovalDismissal(progress(), nowMs = 42L))
        assertEquals("tt123", dismissal.contentId)
        assertEquals(2, dismissal.season)
        assertEquals(5, dismissal.episode)
        assertEquals(42L, dismissal.dismissedAtMs)
        assertEquals("Show", dismissal.title)
        assertFalse(dismissal.isLegacy)
    }

    @Test
    fun `movies record no dismissal`() {
        assertNull(inProgressRemovalDismissal(progress(contentType = "movie", season = null, episode = null), 1L))
    }
}
