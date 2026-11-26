package com.fetch.tv.core.hidden

import com.fetch.tv.data.local.HiddenTitle
import com.fetch.tv.data.local.HiddenTitlesState
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.ui.screens.detail.MetaDetailsUiState
import com.fetch.tv.ui.screens.detail.withoutNotInterested
import com.fetch.tv.ui.screens.search.SearchUiState
import com.fetch.tv.ui.screens.search.withoutNotInterested
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which titles Home rows, See all, Search and More like this leave out (#650). */
class TitleVisibilityTest {

    private fun preview(id: String, type: String = "movie", imdbId: String? = null) = MetaPreview(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        imdbId = imdbId
    )

    private fun row(vararg items: MetaPreview, addonId: String = "addon") = CatalogRow(
        addonId = addonId,
        addonName = "Addon",
        addonBaseUrl = "https://addon",
        catalogId = "top",
        catalogName = "Top",
        type = ContentType.MOVIE,
        items = items.toList()
    )

    private val notInterested = HiddenTitle(ids = listOf("tt1", "tmdb:1"), type = "movie", name = "One")

    private fun visibility(
        hideWatched: Boolean = false,
        movies: Set<String> = emptySet(),
        series: Set<String> = emptySet()
    ) = TitleVisibility.of(HiddenTitlesState(listOf(notInterested), hideWatched), movies, series)

    @Test
    fun `a not interested title goes by any of its ids, but not a show sharing one`() {
        val items = listOf(
            preview("tt1"),
            preview("tmdb:1"),
            preview("other", imdbId = "tt1"),
            preview("tmdb:1", type = "series"),
            preview("tt2")
        )

        val kept = visibility().filterSuggestions(items)

        assertEquals(listOf("tmdb:1" to "series", "tt2" to "movie"), kept.map { it.id to it.apiType })
    }

    @Test
    fun `hide watched drops watched movies and finished shows from catalog rows only`() {
        val v = visibility(hideWatched = true, movies = setOf("tt5"), series = setOf("tt6"))
        val items = listOf(preview("tt5"), preview("tt6", type = "series"), preview("tt7"), preview("tt6"))

        assertEquals(listOf("tt7", "tt6"), v.filterCatalogItems(items).map { it.id })
        // Search and More like this keep watched titles.
        assertEquals(items, v.filterSuggestions(items))
    }

    @Test
    fun `watched titles stay while hide watched is off`() {
        val v = visibility(hideWatched = false, movies = setOf("tt5"))
        val row = row(preview("tt5"))
        assertSame(row, v.filterCatalogRow(row))
        assertTrue(v.watchedMovieIds.isEmpty())
    }

    @Test
    fun `nothing to hide returns the same row`() {
        val row = row(preview("tt5"))
        assertSame(row, TitleVisibility.None.filterCatalogRow(row))
        assertSame(row, visibility().filterCatalogRow(row))
    }

    @Test
    fun `search leaves not interested titles out of addon rows and Discover but not server rows`() {
        val state = SearchUiState(
            catalogRows = listOf(
                row(preview("tt1"), preview("tt2")),
                row(preview("tt1"), addonId = "server.c123"),
                // A row the filter empties goes once it has no more pages (#789).
                row(preview("tt1")).copy(hasMore = false)
            ),
            discoverResults = listOf(preview("tt1"), preview("tt3"))
        )

        val filtered = state.withoutNotInterested(visibility(hideWatched = true, movies = setOf("tt2")))

        assertEquals(listOf(listOf("tt2"), listOf("tt1")), filtered.catalogRows.map { r -> r.items.map { it.id } })
        assertEquals(listOf("tt3"), filtered.discoverResults.map { it.id })
    }

    @Test
    fun `a search row the filter empties stays while it has more pages, so See all can page on`() {
        val state = SearchUiState(
            catalogRows = listOf(
                row(preview("tt1")).copy(catalogId = "paged", hasMore = true),
                row(preview("tt1")).copy(catalogId = "done", hasMore = false)
            )
        )

        val filtered = state.withoutNotInterested(visibility())

        assertEquals(listOf("paged"), filtered.catalogRows.map { it.catalogId })
        assertTrue(filtered.catalogRows.single().items.isEmpty())
    }

    @Test
    fun `more like this leaves them out and the collection keeps them`() {
        val state = MetaDetailsUiState(
            moreLikeThis = listOf(preview("tt1"), preview("tt2")),
            collection = listOf(preview("tt1"))
        )

        val filtered = state.withoutNotInterested(visibility())

        assertEquals(listOf("tt2"), filtered.moreLikeThis.map { it.id })
        assertEquals(listOf("tt1"), filtered.collection.map { it.id })
    }
}
