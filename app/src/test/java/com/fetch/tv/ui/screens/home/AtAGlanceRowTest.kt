package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AtAGlanceRowTest {

    private fun cwItem(name: String, positionMin: Long, durationMin: Long = 60): ModernCarouselItem {
        val progress = WatchProgress(
            contentId = name, contentType = "series", name = name, poster = null, backdrop = null, logo = null,
            videoId = "$name:1:1", season = 1, episode = 1, episodeTitle = null,
            position = positionMin * 60_000L, duration = durationMin * 60_000L, lastWatched = 0L
        )
        return ModernCarouselItem(
            key = "cw_$name",
            title = name,
            subtitle = null,
            imageUrl = null,
            heroPreview = HeroPreview(
                title = name, logo = null, description = null, contentTypeText = null, yearText = null,
                imdbText = null, genres = emptyList<String>().asStable(), poster = null, backdrop = null, imageUrl = null
            ),
            payload = ModernPayload.ContinueWatching(ContinueWatchingItem.InProgress(progress))
        )
    }

    private fun row(key: String, items: List<ModernCarouselItem>) =
        HeroCarouselRow(key = key, title = key, globalRowIndex = -1, items = items.asStable())

    private fun presentation(vararg rows: HeroCarouselRow) =
        ModernHomePresentationState(rows = rows.toList().asStable(), lookups = buildCarouselRowLookups(rows.toList()))

    @Test
    fun `one unfinished title is not enough for a row`() {
        val state = presentation(row(MODERN_CONTINUE_WATCHING_ROW_KEY, listOf(cwItem("A", 10))), row("catalog", emptyList()))
        assertSame(state, state.withAtAGlanceRow("At a glance"))
    }

    @Test
    fun `row goes after Continue Watching and points at the closest to done`() {
        val state = presentation(
            row(MODERN_CONTINUE_WATCHING_ROW_KEY, listOf(cwItem("Recent", 10), cwItem("Nearly", 55), cwItem("Middle", 30))),
            row("catalog", emptyList())
        )
        val result = state.withAtAGlanceRow("At a glance")

        assertEquals(listOf(MODERN_CONTINUE_WATCHING_ROW_KEY, MODERN_AT_A_GLANCE_ROW_KEY, "catalog"), result.rows.list.map { it.key })
        val tiles = result.rows.list[1].items.list
        assertEquals(listOf("Recent", "Nearly"), tiles.map { it.title })
        assertEquals(HomeWidget.FinishBy(50 * 60_000L), tiles[0].widget)
        assertEquals(HomeWidget.AlmostDone(5), tiles[1].widget)
        // Tile keys must not clash with the Continue Watching cards they stand for.
        assertTrue(tiles.none { tile -> tile.key in result.rows.list[0].items.list.map { it.key } })
        assertEquals(1, result.lookups.rowIndexByKey.map[MODERN_AT_A_GLANCE_ROW_KEY])
    }
}
