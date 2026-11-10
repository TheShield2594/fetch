package com.fetch.tv.core.recommendations

import com.fetch.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramBuilderTest {

    @Test
    fun `watch next content id match does not delete prefix collisions`() {
        assertTrue(watchNextIdMatchesContentId("wn_tt1", "tt1"))
        assertTrue(watchNextIdMatchesContentId("wn_tt1_s2e3", "tt1"))

        assertFalse(watchNextIdMatchesContentId("wn_tt10", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt10_s2e3", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt1extra", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt1_series", "tt1"))
        assertFalse(watchNextIdMatchesContentId(null, "tt1"))
    }

    @Test
    fun `an unstarted next episode is published as Next`() {
        assertTrue(isNextUpEpisode(progress(contentType = "series", season = 1, episode = 4, position = 0L)))
    }

    @Test
    fun `started episodes and films stay Continue`() {
        assertFalse(isNextUpEpisode(progress(contentType = "series", season = 1, episode = 4, position = 60_000L)))
        assertFalse(
            isNextUpEpisode(progress(contentType = "series", season = 1, episode = 4, position = 0L, percent = 12f))
        )
        assertFalse(isNextUpEpisode(progress(contentType = "movie", season = null, episode = null, position = 0L)))
    }

    private fun progress(
        contentType: String,
        season: Int?,
        episode: Int?,
        position: Long,
        percent: Float? = null
    ) = WatchProgress(
        contentId = "tt1",
        contentType = contentType,
        name = "Title",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1:1:4",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = 0L,
        lastWatched = 1L,
        progressPercent = percent
    )
}
