package com.fetch.desktop.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryViewTest {

    private val items = listOf(
        LibraryEntry(id = "a", type = "movie", name = "The Matrix", releaseInfo = "1999", genres = listOf("Action", "Sci-Fi"), addedAt = 1),
        LibraryEntry(id = "b", type = "series", name = "Andor", releaseInfo = "2022–2025", genres = listOf("Action"), addedAt = 3),
        LibraryEntry(id = "c", type = "movie", name = "Arrival", releaseInfo = "2016", genres = listOf("Sci-Fi"), addedAt = 2)
    )

    @Test
    fun `recently added first by default`() {
        assertEquals(listOf("b", "c", "a"), LibraryView(items, emptySet(), LibraryQuery()).results.map { it.id })
    }

    @Test
    fun `title sort ignores leading articles`() {
        val view = LibraryView(items, emptySet(), LibraryQuery(sort = LibrarySort.TITLE_ASC))
        assertEquals(listOf("Andor", "Arrival", "The Matrix"), view.results.map { it.name })
    }

    @Test
    fun `counts are for the other filters, so options never dead-end`() {
        val view = LibraryView(items, emptySet(), LibraryQuery(type = "movie", genre = "Action"))
        assertEquals(listOf("a"), view.results.map { it.id })
        // Type counts ignore the type filter but keep the genre one.
        assertEquals(listOf(FacetOption("movie", 1), FacetOption("series", 1)), view.types)
        // Genre counts ignore the genre filter but keep the type one.
        assertEquals(listOf(FacetOption("Action", 1), FacetOption("Sci-Fi", 2)), view.genres)
    }

    @Test
    fun `year comes from the release info and watched filters by id`() {
        val view = LibraryView(items, setOf("a"), LibraryQuery(year = "2022", watched = WatchedFilter.UNWATCHED))
        assertEquals(listOf("b"), view.results.map { it.id })
        assertEquals(listOf("2022", "2016", "1999"), LibraryView(items, emptySet(), LibraryQuery()).years.map { it.value })
    }
}
