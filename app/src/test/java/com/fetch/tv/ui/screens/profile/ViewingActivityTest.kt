package com.fetch.tv.ui.screens.profile

import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewingActivityTest {

    private fun progress(
        contentId: String,
        lastWatched: Long,
        position: Long,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null
    ) = WatchProgress(
        contentId = contentId,
        contentType = if (season == null) "movie" else "series",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = position,
        duration = 100,
        lastWatched = lastWatched
    )

    private fun watched(contentId: String, watchedAt: Long, season: Int? = null, episode: Int? = null) =
        WatchedItem(
            contentId = contentId,
            contentType = if (season == null) "movie" else "series",
            title = contentId,
            season = season,
            episode = episode,
            watchedAt = watchedAt
        )

    @Test
    fun `newest first, with how far each was watched`() {
        val activity = viewingActivity(
            watched = listOf(watched("finished", 200)),
            progress = listOf(progress("started", 300, position = 40), progress("older", 100, position = 10))
        )

        assertEquals(listOf("started", "finished", "older"), activity.map { it.contentId })
        assertEquals(40, activity[0].percentWatched)
        assertNull(activity[1].percentWatched)
    }

    @Test
    fun `an episode in both lists appears once, as whichever came last`() {
        val activity = viewingActivity(
            watched = listOf(watched("show", 500, season = 1, episode = 2)),
            progress = listOf(progress("show", 400, position = 30, season = 1, episode = 2, episodeTitle = "Pilot"))
        )

        val entry = activity.single()
        assertEquals(500L, entry.watchedAt)
        assertNull(entry.percentWatched)
        // The finished entry keeps the progress entry's episode title.
        assertEquals("Pilot", entry.episodeTitle)
    }

    @Test
    fun `progress past the completion threshold counts as finished`() {
        val entry = viewingActivity(emptyList(), listOf(progress("movie", 100, position = 95))).single()

        assertNull(entry.percentWatched)
    }

    @Test
    fun `entries without a time are left out, and the list is capped`() {
        val activity = viewingActivity(
            watched = listOf(watched("undated", 0)) + (1..5).map { watched("m$it", it.toLong()) },
            progress = emptyList(),
            limit = 3
        )

        assertEquals(listOf("m5", "m4", "m3"), activity.map { it.contentId })
    }
}
