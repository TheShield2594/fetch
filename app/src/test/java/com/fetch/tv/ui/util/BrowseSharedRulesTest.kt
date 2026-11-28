package com.fetch.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The series check and title order the Library and Discover share (#849). */
class BrowseSharedRulesTest {

    @Test
    fun `series, tv and anime are series in any case, everything else is not`() {
        listOf("series", "TV", " Anime ").forEach { assertTrue(it, isSeriesContentType(it)) }
        listOf("movie", "channel", "", null).forEach { assertFalse("$it", isSeriesContentType(it)) }
    }

    @Test
    fun `title order drops a leading article and ignores case`() {
        val titles = listOf("zebra", "The Alpha", "bravo", "An Echo")
        assertEquals(
            listOf("The Alpha", "bravo", "An Echo", "zebra"),
            titles.sortedWith(titleSortComparator { it })
        )
    }

    @Test
    fun `accented titles sort beside their base letter, not after Z`() {
        val titles = listOf("Zorro", "Élite", "Eden", "Fargo")
        assertEquals(listOf("Eden", "Élite", "Fargo", "Zorro"), titles.sortedWith(titleSortComparator { it }))
    }
}
