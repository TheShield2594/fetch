package com.fetch.tv.ui.screens.home

import com.fetch.tv.data.local.NextUpDismissal
import com.fetch.tv.data.local.NextUpDismissals
import com.fetch.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingNextUpReconciliationTest {
    @Test
    fun `transient resolution failure retains the existing next up`() {
        val existing = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)

        val result = reconcileConclusiveNextUpItems(
            currentItems = listOf(existing),
            resolvedItems = emptyList(),
            conclusivelyProcessedContentIds = emptySet(),
            dismissedNextUps = NextUpDismissals.EMPTY
        )

        assertEquals(listOf(existing), result)
    }

    @Test
    fun `conclusive completion removes stale next up and preserves unrelated items`() {
        val stale = nextUp("completed", season = 3, episode = 2, seedSeason = 3, seedEpisode = 1)
        val unrelated = nextUp("watching", season = 2, episode = 4, seedSeason = 2, seedEpisode = 3)
        val inProgress = inProgress("movie")

        val result = reconcileConclusiveNextUpItems(
            currentItems = listOf(stale, unrelated, inProgress),
            resolvedItems = emptyList(),
            conclusivelyProcessedContentIds = setOf("completed"),
            dismissedNextUps = NextUpDismissals.EMPTY
        )

        assertFalse(result.contains(stale))
        assertTrue(result.contains(unrelated))
        assertTrue(result.contains(inProgress))
    }

    @Test
    fun `conclusive resolution replaces stale episode for the same series`() {
        val stale = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)
        val resolved = nextUp("series", season = 5, episode = 17, seedSeason = 5, seedEpisode = 16)

        val result = reconcileConclusiveNextUpItems(
            currentItems = listOf(stale),
            resolvedItems = listOf(resolved),
            conclusivelyProcessedContentIds = setOf("series"),
            dismissedNextUps = NextUpDismissals.EMPTY
        )

        assertEquals(listOf(resolved), result)
    }

    @Test
    fun `in progress episode continues to suppress resolved next up`() {
        val inProgress = inProgress("series")
        val resolved = nextUp("series", season = 2, episode = 4, seedSeason = 2, seedEpisode = 3)

        val result = reconcileConclusiveNextUpItems(
            currentItems = listOf(inProgress),
            resolvedItems = listOf(resolved),
            conclusivelyProcessedContentIds = setOf("series"),
            dismissedNextUps = NextUpDismissals.EMPTY
        )

        assertEquals(listOf(inProgress), result)
    }

    @Test
    fun `dismissed resolved next up removes the existing card without adding another`() {
        val stale = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)
        val resolved = nextUp("series", season = 1, episode = 3, seedSeason = 1, seedEpisode = 2)

        val result = reconcileConclusiveNextUpItems(
            currentItems = listOf(stale),
            resolvedItems = listOf(resolved),
            conclusivelyProcessedContentIds = setOf("series"),
            dismissedNextUps = dismissed(
                NextUpDismissal("series", seedSeason = 1, seedEpisode = 2, season = 1, episode = 3, hadAired = true)
            )
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `dismissed show comes back when newer history moves the seed`() {
        val resolved = nextUp("series", season = 1, episode = 5, seedSeason = 1, seedEpisode = 4)

        val result = reconcileConclusiveNextUpItems(
            currentItems = emptyList(),
            resolvedItems = listOf(resolved),
            conclusivelyProcessedContentIds = setOf("series"),
            dismissedNextUps = dismissed(
                NextUpDismissal("series", seedSeason = 1, seedEpisode = 2, season = 1, episode = 3, hadAired = true)
            )
        )

        assertEquals(listOf(resolved), result)
    }

    private fun dismissed(vararg entries: NextUpDismissal) =
        NextUpDismissals(entries.associateBy { it.contentId })

    @Test
    fun `late enrichment does not resurrect a removed stale card`() {
        val stale = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)
        val enrichedStale = stale.copy(info = stale.info.copy(name = "Enriched stale"))
        val unrelated = nextUp("other", season = 1, episode = 3, seedSeason = 1, seedEpisode = 2)

        val result = mergeConcurrentContinueWatchingEnrichment(
            currentItems = listOf(unrelated),
            originalItems = listOf(stale),
            enrichedItems = listOf(enrichedStale)
        )

        assertEquals(listOf(unrelated), result)
    }

    @Test
    fun `late enrichment preserves a newer replacement for the same series`() {
        val stale = nextUp("series", season = 1, episode = 2, seedSeason = 1, seedEpisode = 1)
        val enrichedStale = stale.copy(info = stale.info.copy(name = "Enriched stale"))
        val replacement = nextUp("series", season = 8, episode = 7, seedSeason = 8, seedEpisode = 6)

        val result = mergeConcurrentContinueWatchingEnrichment(
            currentItems = listOf(replacement),
            originalItems = listOf(stale),
            enrichedItems = listOf(enrichedStale)
        )

        assertEquals(listOf(replacement), result)
    }

    @Test
    fun `persisted snapshot excludes rejected items and broken thumbnails`() {
        val stale = nextUp("completed", season = 3, episode = 2, seedSeason = 3, seedEpisode = 1)
        val retained = nextUp(
            contentId = "watching",
            season = 2,
            episode = 4,
            seedSeason = 2,
            seedEpisode = 3,
            thumbnail = "broken"
        )
        val reconciled = reconcileConclusiveNextUpItems(
            currentItems = listOf(stale, retained, inProgress("movie")),
            resolvedItems = emptyList(),
            conclusivelyProcessedContentIds = setOf("completed"),
            dismissedNextUps = NextUpDismissals.EMPTY
        )

        val snapshot = reconciled.toCachedNextUpSnapshot(setOf("broken"))

        assertEquals(listOf("watching"), snapshot.map { item -> item.contentId })
        assertEquals(null, snapshot.single().thumbnail)
    }

    private fun nextUp(
        contentId: String,
        season: Int,
        episode: Int,
        seedSeason: Int,
        seedEpisode: Int,
        thumbnail: String? = null
    ) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = contentId,
            contentType = "series",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = null,
            thumbnail = thumbnail,
            lastWatched = 100L,
            sortTimestamp = 100L,
            seedSeason = seedSeason,
            seedEpisode = seedEpisode
        )
    )

    private fun inProgress(contentId: String) = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:1:1",
            season = 1,
            episode = 1,
            episodeTitle = null,
            position = 40L,
            duration = 100L,
            lastWatched = 100L
        )
    )
}
