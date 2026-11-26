package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** TheShield2594/fetch#816: a removal is held for its undo window and only hides the card. */
class ContinueWatchingUndoTest {

    private fun inProgress(id: String) = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = id,
            contentType = "series",
            name = "Show $id",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$id:1:2",
            season = 1,
            episode = 2,
            episodeTitle = null,
            position = 600_000L,
            duration = 2_400_000L,
            lastWatched = 1L
        )
    )

    private fun nextUp(id: String) = ContinueWatchingItem.NextUp(NextUpInfo(
        id, "series", "Show $id", null, null, null, "$id:1:3", 1, 3, null,
        thumbnail = null, lastWatched = 1, sortTimestamp = 1
    ))

    private fun pending(id: String, isNextUp: Boolean) = PendingContinueWatchingRemoval(
        contentId = id,
        season = null,
        episode = null,
        isNextUp = isNextUp,
        title = "Show $id",
        token = 1L,
        shownItems = emptyList()
    )

    @Test
    fun `no hold leaves the state untouched`() {
        val state = HomeUiState(continueWatchingItems = listOf(inProgress("a")))
        assertSame(state, hidePendingContinueWatchingRemoval(state, null))
    }

    @Test
    fun `held in-progress removal hides the card and keeps its neighbours in order`() {
        val state = HomeUiState(
            continueWatchingItems = listOf(inProgress("a"), inProgress("b"), inProgress("c"))
        )
        val shown = hidePendingContinueWatchingRemoval(state, pending("b", isNextUp = false))
        assertEquals(listOf("a", "c"), shown.continueWatchingItems.map { it.contentId() })
        // Undo is dropping the hold: the underlying state still has the card where it was.
        assertEquals(listOf("a", "b", "c"), state.continueWatchingItems.map { it.contentId() })
    }

    @Test
    fun `held Next Up dismissal hides only the Next Up card`() {
        val state = HomeUiState(
            continueWatchingItems = listOf(inProgress("a")),
            upcomingItems = listOf(nextUp("a"))
        )
        val shown = hidePendingContinueWatchingRemoval(state, pending("a", isNextUp = true))
        assertEquals(listOf("a"), shown.continueWatchingItems.map { it.contentId() })
        assertEquals(emptyList<String>(), shown.upcomingItems.map { it.contentId() })
    }

    @Test
    fun `held in-progress removal also hides a Next Up card of the same title`() {
        val state = HomeUiState(upcomingItems = listOf(nextUp("a"), nextUp("b")))
        val shown = hidePendingContinueWatchingRemoval(state, pending(" a ", isNextUp = false))
        assertEquals(listOf("b"), shown.upcomingItems.map { it.contentId() })
    }
}

/** TheShield2594/fetch#799: a failed Home row is retried with a growing backoff, then on resume. */
class HomeCatalogRetryBackoffTest {

    @Test
    fun `backoff grows and then stops`() {
        assertEquals(15_000L, homeCatalogRetryDelayMs(1))
        assertEquals(60_000L, homeCatalogRetryDelayMs(2))
        assertEquals(240_000L, homeCatalogRetryDelayMs(3))
        assertNull(homeCatalogRetryDelayMs(4))
    }
}
