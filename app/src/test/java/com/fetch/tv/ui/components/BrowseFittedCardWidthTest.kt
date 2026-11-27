package com.fetch.tv.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Browse grids shrink posters only as far as needed for full rows to show on entry (#848, #850). */
class BrowseFittedCardWidthTest {

    private fun fitted(landscape: Boolean, viewport: Int = 540) = browseFittedCardWidth(
        preferred = 126.dp,
        viewport = viewport.dp,
        header = 120.dp,
        topPadding = 27.dp,
        gap = 16.dp,
        labelAllowance = 32.dp,
        rows = if (landscape) BrowseDefaults.LANDSCAPE_FITTED_ROWS else BrowseDefaults.POSTER_FITTED_ROWS,
        cardHeightPerWidth = browseCardHeightPerWidth(landscape)
    )

    @Test
    fun `portrait posters shrink so two rows fit`() {
        val width = fitted(landscape = false)
        assertTrue("posters should shrink in a 540dp window, got $width", width < 126.dp)
    }

    @Test
    fun `landscape cards are sized for three of their shorter rows`() {
        val width = fitted(landscape = true)
        val rowHeight = width.value * browseCardHeightPerWidth(landscape = true) + 32f + 16f
        val available = 540f - 27f - 120f
        assertTrue("three landscape rows should fit, row $rowHeight in $available", rowHeight * 3 <= available + 0.5f)
    }

    @Test
    fun `a tall window keeps the set size for landscape cards`() {
        assertEquals(126.dp, fitted(landscape = true, viewport = 720))
    }

    @Test
    fun `a landscape card is shorter per unit of width than a poster`() {
        assertTrue(browseCardHeightPerWidth(landscape = true) < browseCardHeightPerWidth(landscape = false))
    }

    @Test
    fun `before the viewport is measured the set size is used`() {
        assertEquals(126.dp, fitted(landscape = false, viewport = 0))
    }

    @Test
    fun `with the header outside the grid only the grid's own height counts`() {
        val inGrid = browseFittedCardWidth(126.dp, viewport = 420.dp, topPadding = 8.dp, gap = 16.dp, labelAllowance = 32.dp)
        assertTrue("two poster rows should fit in 420dp, got $inGrid", (inGrid.value * 1.5f + 32f + 16f) * 2 <= 420f - 8f + 0.5f)
    }

    @Test
    fun `a fast scroll lands in the column it started in`() {
        val cards = listOf<Pair<Any, Int>>("a" to 0, "b" to 1, "c" to 0, "d" to 1)
        assertEquals("d", browseFastScrollLanding(cards, startColumn = 1, sign = 1))
        assertEquals("a", browseFastScrollLanding(cards, startColumn = 0, sign = -1))
        assertEquals("d", browseFastScrollLanding(cards, startColumn = 5, sign = 1))
        assertEquals(null, browseFastScrollLanding(emptyList(), startColumn = 0, sign = 1))
    }
}
