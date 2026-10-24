package com.fetch.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class TraktEpisodeMappingTest {
    @Test
    fun `an episode keeps its season when both sides number the same seasons`() {
        // NuvioTV#3439: the addon has 5 more episodes in early seasons than Trakt, and the
        // titles are only "Episode N", so the old whole-show count put S28E1 on S28E6.
        val trakt = show(seasons = 28, episodesPerSeason = { 10 })
        val addon = show(seasons = 28, episodesPerSeason = { season -> if (season <= 5) 9 else 10 })

        val mapped = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = 28,
            requestedEpisode = 1,
            addonEpisodes = addon,
            traktEpisodes = trakt
        )

        assertEquals(EpisodeMappingEntry(28, 1, "Episode 1"), mapped)
    }

    @Test
    fun `split seasons still map by position across the show`() {
        // Trakt keeps one 24-episode season; the addon splits it into two of 12.
        val trakt = show(seasons = 1, episodesPerSeason = { 24 })
        val addon = show(seasons = 2, episodesPerSeason = { 12 })

        val mapped = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 13,
            addonEpisodes = addon,
            traktEpisodes = trakt
        )

        assertEquals(EpisodeMappingEntry(2, 1, "Episode 1"), mapped)
    }

    @Test
    fun `a unique title match still wins`() {
        val trakt = listOf(EpisodeMappingEntry(1, 1, "Pilot"), EpisodeMappingEntry(1, 2, "The Heist"))
        val addon = listOf(
            EpisodeMappingEntry(1, 1, "Pilot"),
            EpisodeMappingEntry(1, 2, "Recap"),
            EpisodeMappingEntry(1, 3, "The Heist")
        )

        val mapped = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 2,
            addonEpisodes = addon,
            traktEpisodes = trakt
        )

        assertEquals(EpisodeMappingEntry(1, 3, "The Heist"), mapped)
    }

    private fun show(seasons: Int, episodesPerSeason: (Int) -> Int): List<EpisodeMappingEntry> =
        (1..seasons).flatMap { season ->
            (1..episodesPerSeason(season)).map { episode ->
                EpisodeMappingEntry(season, episode, "Episode $episode")
            }
        }
}
