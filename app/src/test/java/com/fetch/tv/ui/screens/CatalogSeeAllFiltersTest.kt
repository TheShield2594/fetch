package com.fetch.tv.ui.screens

import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CatalogExtra
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.ui.screens.search.DiscoverFilters
import com.fetch.tv.ui.screens.search.DiscoverSort
import com.fetch.tv.ui.screens.search.DiscoverYearRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSeeAllFiltersTest {

    @Test
    fun `genre options come from the catalog genre extra, blanks and repeats dropped`() {
        val catalog = CatalogDescriptor(
            type = ContentType.MOVIE,
            id = "top",
            name = "Top",
            extra = listOf(
                CatalogExtra(name = "skip"),
                CatalogExtra(name = "Genre", options = listOf("Action", " ", "action", "Drama"))
            )
        )
        assertEquals(listOf("Action", "Drama"), seeAllGenreOptions(catalog))
    }

    @Test
    fun `a catalog with no genre extra offers no genres`() {
        val catalog = CatalogDescriptor(type = ContentType.MOVIE, id = "top", name = "Top")
        assertTrue(seeAllGenreOptions(catalog).isEmpty())
    }

    @Test
    fun `no filter returns the same list`() {
        val items = listOf(item("a"), item("b"))
        assertSame(items, seeAllVisibleItems(items, DiscoverFilters(), hideWatched = false) { true })
    }

    @Test
    fun `hide watched drops watched titles after the other filters`() {
        val items = listOf(
            item("old", year = "1990"),
            item("watched", year = "2021"),
            item("new", year = "2022")
        )
        val visible = seeAllVisibleItems(
            items,
            DiscoverFilters(yearRange = DiscoverYearRange(2020, 2029)),
            hideWatched = true
        ) { it.id == "watched" }
        assertEquals(listOf("new"), visible.map { it.id })
    }

    @Test
    fun `sort applies to the loaded titles`() {
        val items = listOf(item("b", rating = 6f), item("a", rating = 8f), item("c", rating = null))
        val visible = seeAllVisibleItems(items, DiscoverFilters(sort = DiscoverSort.RATING), hideWatched = false) { false }
        assertEquals(listOf("a", "b", "c"), visible.map { it.id })
    }

    private fun item(id: String, year: String? = "2020", rating: Float? = null) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = year,
        imdbRating = rating,
        genres = emptyList()
    )
}
