package com.fetch.tv.ui.screens.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Library shrinks posters only as far as needed for full rows to show on entry (#848). */
class LibraryFittedCardWidthTest {

    private fun fitted(landscape: Boolean, viewport: Int = 540) = libraryFittedCardWidth(
        preferred = 126.dp,
        viewport = viewport.dp,
        header = 120.dp,
        topPadding = 27.dp,
        gap = 16.dp,
        labelAllowance = 32.dp,
        rows = if (landscape) LIBRARY_LANDSCAPE_FITTED_ROWS else LIBRARY_POSTER_FITTED_ROWS,
        cardHeightPerWidth = libraryCardHeightPerWidth(landscape)
    )

    @Test
    fun `portrait posters shrink so two rows fit`() {
        val width = fitted(landscape = false)
        assertTrue("posters should shrink in a 540dp window, got $width", width < 126.dp)
    }

    @Test
    fun `landscape cards are sized for three of their shorter rows`() {
        val width = fitted(landscape = true)
        val rowHeight = width.value * libraryCardHeightPerWidth(landscape = true) + 32f + 16f
        val available = 540f - 27f - 120f
        assertTrue("three landscape rows should fit, row $rowHeight in $available", rowHeight * 3 <= available + 0.5f)
    }

    @Test
    fun `a tall window keeps the set size for landscape cards`() {
        assertEquals(126.dp, fitted(landscape = true, viewport = 720))
    }

    @Test
    fun `a landscape card is shorter per unit of width than a poster`() {
        assertTrue(libraryCardHeightPerWidth(landscape = true) < libraryCardHeightPerWidth(landscape = false))
    }

    @Test
    fun `before the viewport is measured the set size is used`() {
        assertEquals(126.dp, fitted(landscape = false, viewport = 0))
    }
}
