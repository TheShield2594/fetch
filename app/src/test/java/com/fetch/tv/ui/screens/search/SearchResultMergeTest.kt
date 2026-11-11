package com.fetch.tv.ui.screens.search

import com.fetch.tv.core.tracking.TrackingExternalIds
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roadmap D3: merging search results across addons, and ranking them. */
class SearchResultMergeTest {

    @Test
    fun `a title returned by four addons appears once`() {
        val rows = listOf("Cinemeta", "TMDB", "Anime", "Torrentio").mapIndexed { index, addon ->
            row(addon, "movie", movie(id = "tt0133093", name = "The Matrix", year = "1999", poster = "p$index"))
        }

        val merged = SearchResultMerger.merge(rows, query = "matrix")

        assertEquals(1, merged.rows.size)
        assertEquals(listOf("tt0133093"), merged.rows.single().items.map { it.id })
        assertEquals(listOf("Cinemeta", "TMDB", "Anime", "Torrentio"), merged.topResult?.addonNames)
    }

    @Test
    fun `ids in different shapes merge on the IMDb id`() {
        val rows = listOf(
            row("A", "movie", movie(id = "tt0133093", name = "The Matrix", year = "1999")),
            row("B", "movie", movie(id = "tmdb:603", name = "The Matrix", year = "1999", imdbId = "tt0133093"))
        )

        assertEquals(1, SearchResultMerger.merge(rows, "matrix").rows.single().items.size)
    }

    @Test
    fun `a title without an id merges on title and year`() {
        val rows = listOf(
            row("A", "movie", movie(id = "tt0133093", name = "The Matrix", year = "1999")),
            row("B", "movie", movie(id = "kitsu:1", name = "The Matrix!", year = "1999"))
        )

        val items = SearchResultMerger.merge(rows, "matrix").rows.single().items
        assertEquals(listOf("tt0133093"), items.map { it.id })
    }

    @Test
    fun `same title and year with different ids stay apart`() {
        val rows = listOf(
            row("A", "movie", movie(id = "tt1000001", name = "Home", year = "2015")),
            row("B", "movie", movie(id = "tt1000002", name = "Home", year = "2015"))
        )

        assertEquals(2, SearchResultMerger.merge(rows, "home").rows.single().items.size)
    }

    @Test
    fun `same title in different years stays apart`() {
        val rows = listOf(
            row("A", "movie", movie(id = "x1", name = "Dune", year = "1984")),
            row("B", "movie", movie(id = "x2", name = "Dune", year = "2021"))
        )

        assertEquals(2, SearchResultMerger.merge(rows, "dune").rows.single().items.size)
    }

    @Test
    fun `exact title beats prefix beats contains`() {
        val rows = listOf(
            row(
                "A", "movie",
                movie(id = "tt1", name = "Batman Begins", year = "2005"),
                movie(id = "tt2", name = "The Lego Batman Movie", year = "2017"),
                movie(id = "tt3", name = "Batman", year = "1989")
            )
        )

        val merged = SearchResultMerger.merge(rows, "batman")

        assertEquals(listOf("tt3", "tt1", "tt2"), merged.rows.single().items.map { it.id })
        assertEquals("tt3", merged.topResult?.item?.id)
    }

    @Test
    fun `a leading article does not cost an exact match`() {
        assertEquals(1, SearchResultMerger.matchTier("the matrix", "matrix"))
        assertEquals(0, SearchResultMerger.matchTier("the matrix", "the matrix"))
        assertEquals(3, SearchResultMerger.matchTier("enter the matrix", "matrix"))
    }

    @Test
    fun `among equal matches the one more addons returned ranks first`() {
        val rows = listOf(
            row("A", "movie", movie(id = "tt1", name = "Alien", year = "1979"), movie(id = "tt2", name = "Alien", year = "2030")),
            row("B", "movie", movie(id = "tt2", name = "Alien", year = "2030")),
            row("C", "movie", movie(id = "tt2", name = "Alien", year = "2030"))
        )

        assertEquals(listOf("tt2", "tt1"), SearchResultMerger.merge(rows, "alien").rows.single().items.map { it.id })
    }

    @Test
    fun `rows are per type, movies then series then others`() {
        val rows = listOf(
            row("A", "series", series(id = "tt10", name = "Fargo", year = "2014")),
            row("A", "anime", MetaPreview(
                id = "kitsu:9", type = ContentType.fromString("anime"), rawType = "anime", name = "Fargo Anime",
                poster = null, posterShape = PosterShape.POSTER, background = null, logo = null,
                description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
            )),
            row("B", "movie", movie(id = "tt11", name = "Fargo", year = "1996"))
        )

        val merged = SearchResultMerger.merge(rows, "fargo", rowTitle = { it.uppercase() })

        assertEquals(listOf("movie", "series", "anime"), merged.rows.map { it.catalogId })
        assertEquals(listOf("MOVIE", "SERIES", "ANIME"), merged.rows.map { it.catalogName })
        assertTrue(merged.rows.all { it.addonId == MERGED_ROW_ADDON_ID })
    }

    @Test
    fun `live tv channels keep their own row`() {
        val rows = listOf(
            row("A", "series", series(id = "tt10", name = "Fargo", year = "2014")),
            row("B", "tv", series(id = "fargo-live", name = "Fargo", year = "2014", rawType = "tv"))
        )

        val merged = SearchResultMerger.merge(rows, "fargo")
        assertEquals(listOf("series", "tv"), merged.rows.map { it.catalogId })
    }

    @Test
    fun `filters narrow the rows and the top result`() {
        val rows = listOf(
            row("A", "movie", movie(id = "tt11", name = "Fargo", year = "1996")),
            row("A", "series", series(id = "tt10", name = "Fargo", year = "2014"))
        )

        val series = SearchResultMerger.merge(rows, "fargo", filter = SearchTypeFilter.SERIES)
        assertEquals(listOf("tt10"), series.rows.flatMap { it.items }.map { it.id })
        assertEquals("tt10", series.topResult?.item?.id)

        val library = libraryIdSet(listOf(LibraryIds("tt11", "movie")))
        val saved = SearchResultMerger.merge(
            rows, "fargo",
            filter = SearchTypeFilter.IN_LIBRARY,
            isInLibrary = { isLibraryItem(it, library) }
        )
        assertEquals(listOf("tt11"), saved.rows.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `a filter that matches nothing gives no rows and no top result`() {
        val rows = listOf(row("A", "movie", movie(id = "tt11", name = "Fargo", year = "1996")))

        val merged = SearchResultMerger.merge(rows, "fargo", filter = SearchTypeFilter.SERIES)

        assertTrue(merged.isEmpty)
        assertNull(merged.topResult)
    }

    @Test
    fun `placeholders are left out`() {
        val rows = listOf(row("A", "movie", movie(id = "__placeholder_x_0", name = " ", year = " ")))

        assertTrue(SearchResultMerger.merge(rows, "fargo").isEmpty)
    }

    @Test
    fun `each merged title keeps the addon to open it from`() {
        val rows = listOf(
            row("A", "movie", movie(id = "tt11", name = "Fargo", year = "1996")),
            row("B", "movie", movie(id = "tt12", name = "Fargo 2", year = "1998"))
        )

        val items = SearchResultMerger.merge(rows, "fargo").rows.single().items
        assertEquals(listOf("https://a.example", "https://b.example"), items.map { it.sourceAddonBaseUrl })
    }

    @Test
    fun `the copy with artwork is the one shown`() {
        val rows = listOf(
            row("A", "movie", movie(id = "tt11", name = "Fargo", year = "1996", poster = null)),
            row("B", "movie", movie(id = "tt11", name = "Fargo", year = "1996", poster = "poster.jpg"))
        )

        val item = SearchResultMerger.merge(rows, "fargo").rows.single().items.single()
        assertEquals("poster.jpg", item.poster)
        // Opened from the first addon that returned it, as before merging.
        assertEquals("https://a.example", item.sourceAddonBaseUrl)
    }

    @Test
    fun `library keys match by id and by IMDb id`() {
        val library = libraryIdSet(listOf(LibraryIds("tt0133093", "movie"), LibraryIds(" ", "movie")))

        assertTrue(isLibraryItem(movie(id = "tt0133093", name = "x", year = null), library))
        assertTrue(isLibraryItem(movie(id = "tmdb:603", name = "x", year = null, imdbId = "tt0133093"), library))
        assertFalse(isLibraryItem(movie(id = "tmdb:604", name = "x", year = null), library))
        assertFalse(isLibraryItem(movie(id = "tt0133093", name = "x", year = null), emptySet()))
    }

    @Test
    fun `a saved title matches through its alternate TMDB id, per type`() {
        // Saved under its IMDb id, with the TMDB id alongside (as MDBList entries are).
        val library = libraryIdSet(listOf(LibraryIds("tt0133093", "movie", tmdbId = 603)))

        assertTrue(isLibraryItem(movie(id = "tmdb:603", name = "x", year = null), library))
        assertFalse(isLibraryItem(series(id = "tmdb:603", name = "x", year = null), library))
    }

    @Test
    fun `a saved raw TMDB id only matches the same type`() {
        val library = libraryIdSet(listOf(LibraryIds("tmdb:603", "movie")))

        assertTrue(isLibraryItem(movie(id = "tmdb:603", name = "x", year = null), library))
        assertFalse(isLibraryItem(series(id = "tmdb:603", name = "x", year = null), library))
    }

    @Test
    fun `other types are named as acronyms when short`() {
        assertEquals("TV", addonTypeDisplayName("tv"))
        assertEquals("Anime", addonTypeDisplayName("anime"))
    }

    @Test
    fun `available filters follow the result types and the library`() {
        assertEquals(
            listOf(SearchTypeFilter.ALL, SearchTypeFilter.MOVIES),
            availableTypeFilters(setOf("movie"), hasLibrary = false)
        )
        assertEquals(
            listOf(SearchTypeFilter.ALL, SearchTypeFilter.MOVIES, SearchTypeFilter.SERIES, SearchTypeFilter.IN_LIBRARY),
            availableTypeFilters(setOf("series", "movie"), hasLibrary = true)
        )
    }

    @Test
    fun `a title an addon and a server both return appears once, from the server`() {
        val serverItem = movie(id = "srv1:plex:42", name = "The Matrix", year = "1999", poster = null)
        val rows = listOf(
            row("Cinemeta", "movie", movie(id = "tt0133093", name = "The Matrix", year = "1999", poster = "addon")),
            serverRow("Plex", serverItem)
        )

        val merged = SearchResultMerger.merge(
            rows,
            query = "matrix",
            externalIds = { item -> if (item.id == serverItem.id) TrackingExternalIds(imdb = "tt0133093") else null }
        )

        val item = merged.rows.single().items.single()
        assertEquals("srv1:plex:42", item.id)
        assertEquals(SERVER_URL, item.sourceAddonBaseUrl)
        // The server had no artwork; the addon's is kept.
        assertEquals("addon", item.poster)
        assertEquals(listOf("Cinemeta", "Plex"), merged.topResult?.addonNames)
    }

    @Test
    fun `server titles merge on the TMDB id`() {
        val serverItem = movie(id = "srv1:jf:7", name = "Matrix", year = null)
        val rows = listOf(
            row("TMDB", "movie", movie(id = "tmdb:603", name = "The Matrix", year = "1999")),
            serverRow("Jellyfin", serverItem)
        )

        val merged = SearchResultMerger.merge(
            rows,
            query = "matrix",
            externalIds = { item -> if (item.id == serverItem.id) TrackingExternalIds(tmdb = 603) else null }
        )

        assertEquals(listOf("srv1:jf:7"), merged.rows.single().items.map { it.id })
    }

    @Test
    fun `server titles without ids merge on title and year, server first`() {
        val rows = listOf(
            serverRow("Plex", movie(id = "srv1:plex:1", name = "Dune", year = "2021")),
            row("Cinemeta", "movie", movie(id = "tt1160419", name = "Dune", year = "2021"))
        )

        val item = SearchResultMerger.merge(rows, query = "dune").rows.single().items.single()
        assertEquals("srv1:plex:1", item.id)
        assertEquals(SERVER_URL, item.sourceAddonBaseUrl)
    }

    @Test
    fun `a server title another addon lacks stays on its own`() {
        val rows = listOf(
            row("Cinemeta", "movie", movie(id = "tt1160419", name = "Dune", year = "2021")),
            serverRow("Plex", movie(id = "srv1:plex:9", name = "Dune", year = "1984"))
        )

        assertEquals(2, SearchResultMerger.merge(rows, query = "dune").rows.single().items.size)
    }

    @Test
    fun `a server title with both ids meets an addon title known only by TMDB`() {
        val serverItem = movie(id = "srv1:plex:42", name = "The Matrix", year = "1999")
        val rows = listOf(
            row("TMDB", "movie", movie(id = "tmdb:603", name = "The Matrix", year = "1999")),
            serverRow("Plex", serverItem)
        )

        val merged = SearchResultMerger.merge(
            rows,
            query = "matrix",
            externalIds = { item ->
                if (item.id == serverItem.id) TrackingExternalIds(imdb = "tt0133093", tmdb = 603) else null
            }
        )

        assertEquals(listOf("srv1:plex:42"), merged.rows.single().items.map { it.id })
    }

    @Test
    fun `the library filter keeps a title saved under the addon's id when the server copy shows`() {
        val rows = listOf(
            row("Cinemeta", "movie", movie(id = "tt0133093", name = "The Matrix", year = "1999")),
            serverRow("Plex", movie(id = "srv1:plex:42", name = "The Matrix", year = "1999"))
        )

        val merged = SearchResultMerger.merge(
            rows,
            query = "matrix",
            filter = SearchTypeFilter.IN_LIBRARY,
            isInLibrary = { it.id == "tt0133093" }
        )

        assertEquals(listOf("srv1:plex:42"), merged.rows.single().items.map { it.id })
    }

    private fun serverRow(name: String, vararg items: MetaPreview) = CatalogRow(
        addonId = "server.${name.lowercase()}",
        addonName = name,
        addonBaseUrl = SERVER_URL,
        catalogId = "search.movie",
        catalogName = "Search",
        type = ContentType.MOVIE,
        rawType = "movie",
        items = items.toList()
    )

    private fun row(addon: String, type: String, vararg items: MetaPreview) = CatalogRow(
        addonId = addon.lowercase(),
        addonName = addon,
        addonBaseUrl = "https://${addon.lowercase()}.example",
        catalogId = "search",
        catalogName = "Search",
        type = ContentType.fromString(type),
        rawType = type,
        items = items.toList()
    )

    private fun movie(id: String, name: String, year: String?, poster: String? = "poster", imdbId: String? = null) =
        MetaPreview(
            id = id, type = ContentType.MOVIE, rawType = "movie", name = name, poster = poster,
            posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
            releaseInfo = year, imdbRating = null, genres = emptyList(), imdbId = imdbId
        )

    private fun series(id: String, name: String, year: String?, rawType: String = "series") =
        MetaPreview(
            id = id, type = ContentType.fromString(rawType), rawType = rawType, name = name, poster = "poster",
            posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
            releaseInfo = year, imdbRating = null, genres = emptyList()
        )

    private companion object {
        const val SERVER_URL = "fetch-server://plex"
    }
}
