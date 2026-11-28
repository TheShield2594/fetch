package com.fetch.tv.ui.screens.search

import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverBrowseTest {

    // Sources

    @Test
    fun `without a genre every browsable catalog of the type is asked`() {
        val catalogs = listOf(
            catalog("popular", "movie"),
            catalog("byGenre", "movie", genres = listOf("Action"), genreRequired = true, defaultGenre = "Action"),
            catalog("shows", "series"),
            catalog("featured", "movie", genres = listOf("Drama"))
        )

        val sources = selectDiscoverSources(catalogs, type = "movie", genre = null)

        assertEquals(listOf("popular", "featured"), sources.map { it.key })
    }

    @Test
    fun `genre-required catalogs join on their default genre when nothing else can be browsed`() {
        val catalogs = listOf(
            catalog("byGenre", "movie", genres = listOf("Action"), genreRequired = true, defaultGenre = "Action"),
            catalog("noDefault", "movie", genres = listOf("Drama"), genreRequired = true)
        )

        val sources = selectDiscoverSources(catalogs, type = "movie", genre = null)

        assertEquals(listOf("byGenre"), sources.map { it.key })
        assertEquals("Action", discoverGenreArg(sources.single(), genre = null))
    }

    @Test
    fun `a genre asks only the catalogs that list it, in their own spelling`() {
        val catalogs = listOf(
            catalog("a", "movie", genres = listOf("Action", "Drama")),
            catalog("b", "movie", genres = listOf("Comedy")),
            catalog("c", "movie", genres = listOf("drama"), genreRequired = true, defaultGenre = "drama"),
            catalog("d", "series", genres = listOf("Drama"))
        )

        val sources = selectDiscoverSources(catalogs, type = "movie", genre = "Drama")

        assertEquals(listOf("a", "c"), sources.map { it.key })
        assertEquals("drama", discoverGenreArg(sources[1], genre = "Drama"))
        assertNull(discoverGenreArg(catalogs[1], genre = null))
    }

    @Test
    fun `types put movies and series first and genres merge ignoring case`() {
        val catalogs = listOf(
            catalog("anime", "anime"),
            catalog("shows", "series", genres = listOf("Drama")),
            catalog("films", "movie", genres = listOf("Action", "Drama")),
            catalog("more", "movie", genres = listOf("action", "Horror", " "))
        )

        assertEquals(listOf("movie", "series", "anime"), discoverTypes(catalogs))
        assertEquals(listOf("Action", "Drama", "Horror"), discoverGenres(catalogs, "movie"))
    }

    @Test
    fun `years catalogs list as genres stay out of the genre list`() {
        val catalogs = listOf(
            catalog("top", "movie", genres = listOf("Action", "Horror")),
            catalog("year", "movie", genres = listOf("2026", "2025", " 1999 ", "1980s", "Sci-Fi"))
        )

        assertEquals(listOf("Action", "Horror", "1980s", "Sci-Fi"), discoverGenres(catalogs, "movie"))
    }

    // Merging

    @Test
    fun `pages interleave so every catalog shows near the top`() {
        val merged = interleaveDiscoverPages(
            listOf(
                listOf(item("a1"), item("a2"), item("a3")),
                listOf(item("b1")),
                listOf(item("c1"), item("c2"))
            )
        )

        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "a3"), merged.map { it.id })
    }

    @Test
    fun `a round appends without moving what is already merged and drops duplicates`() {
        val existing = listOf(item("a1"), item("b1"))

        val merged = appendDiscoverRound(
            existing,
            listOf(
                listOf(item("a2"), item("shared")),
                listOf(item("b1"), item("shared"), item("b2"))
            )
        )

        assertEquals(listOf("a1", "b1", "a2", "shared", "b2"), merged.map { it.id })
    }

    @Test
    fun `the same id under another type is a different title`() {
        val merged = appendDiscoverRound(
            emptyList(),
            listOf(listOf(item("tt1", type = ContentType.MOVIE)), listOf(item("tt1", type = ContentType.SERIES)))
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun `a round with nothing new returns the same list`() {
        val existing = listOf(item("a1"))

        assertSame(existing, appendDiscoverRound(existing, listOf(listOf(item("a1")), emptyList())))
    }

    // Year

    @Test
    fun `year comes from release info, then the release date`() {
        assertEquals(2019, item("a", releaseInfo = "2019–2022").discoverYear())
        assertEquals(1999, item("b", releaseInfo = "1999").discoverYear())
        assertEquals(2004, item("c", releaseInfo = null, released = "2004-05-01T00:00:00.000Z").discoverYear())
        assertNull(item("d", releaseInfo = "TBA").discoverYear())
    }

    @Test
    fun `year ranges cover this year, recent decades and everything older`() {
        val ranges = discoverYearRanges(2026)

        assertEquals(DiscoverYearRange(2026, 2026), ranges.first())
        assertEquals(DiscoverYearRange(2020, 2029), ranges[1])
        assertEquals(DiscoverYearRange(1980, 1989), ranges[DISCOVER_DECADE_COUNT])
        assertEquals(DiscoverYearRange(null, 1979), ranges.last())
        assertTrue(1950 in ranges.last())
        assertFalse(1980 in ranges.last())
    }

    // Filters and sort

    @Test
    fun `no filters keep catalog order`() {
        val items = listOf(item("b", rating = 5f), item("a", rating = 9f))

        assertEquals(items, applyDiscoverFilters(items, DiscoverFilters()))
    }

    @Test
    fun `year and rating filters drop titles without the data`() {
        val items = listOf(
            item("old", releaseInfo = "1995", rating = 8f),
            item("new", releaseInfo = "2015", rating = 8f),
            item("newLow", releaseInfo = "2012", rating = 6.9f),
            item("noYear", releaseInfo = null, rating = 9f),
            item("noRating", releaseInfo = "2013", rating = null)
        )

        val filtered = applyDiscoverFilters(
            items,
            DiscoverFilters(yearRange = DiscoverYearRange(2010, 2019), minRating = 7)
        )

        assertEquals(listOf("new"), filtered.map { it.id })
    }

    @Test
    fun `rating sort puts the best first and unrated last, ties in catalog order`() {
        val items = listOf(
            item("unrated", rating = null),
            item("seven", rating = 7f),
            item("nine", rating = 9f),
            item("sevenToo", rating = 7f)
        )

        val sorted = applyDiscoverFilters(items, DiscoverFilters(sort = DiscoverSort.RATING))

        assertEquals(listOf("nine", "seven", "sevenToo", "unrated"), sorted.map { it.id })
    }

    @Test
    fun `newest sort orders by year with undated titles last`() {
        val items = listOf(
            item("undated", releaseInfo = null),
            item("y2001", releaseInfo = "2001"),
            item("y2020", releaseInfo = "2020-2023")
        )

        val sorted = applyDiscoverFilters(items, DiscoverFilters(sort = DiscoverSort.NEWEST))

        assertEquals(listOf("y2020", "y2001", "undated"), sorted.map { it.id })
    }

    @Test
    fun `title sort ignores case`() {
        val items = listOf(item("1", name = "zebra"), item("2", name = "Apple"), item("3", name = "banana"))

        val sorted = applyDiscoverFilters(items, DiscoverFilters(sort = DiscoverSort.TITLE))

        assertEquals(listOf("Apple", "banana", "zebra"), sorted.map { it.name })
    }

    @Test
    fun `a sorted batch goes below what is shown and never moves it`() {
        val shown = listOf(item("a", rating = 9f), item("b", rating = 5f))
        val batch = listOf(item("c", rating = 6f), item("d", rating = 9.5f), item("a", rating = 9f))

        val next = appendDiscoverSortedBatch(shown, batch, DiscoverFilters(sort = DiscoverSort.RATING))

        // "d" rates higher than everything, but it arrived later, so it leads the new batch only.
        assertEquals(listOf("a", "b", "d", "c"), next.map { it.id })
    }

    @Test
    fun `a sorted batch keeps only what the filters keep`() {
        val batch = listOf(item("low", rating = 4f), item("high", rating = 8f))

        val next = appendDiscoverSortedBatch(emptyList(), batch, DiscoverFilters(minRating = 7, sort = DiscoverSort.RATING))

        assertEquals(listOf("high"), next.map { it.id })
    }

    @Test
    fun `stored filters come back as they were saved`() {
        val filters = DiscoverFilters(
            yearRange = DiscoverYearRange(from = null, to = 1979),
            minRating = 7,
            sort = DiscoverSort.NEWEST
        )

        assertEquals(filters, filters.toStored().toDiscoverFilters())
        assertEquals(DiscoverFilters(), DiscoverFilters().toStored().toDiscoverFilters())
    }

    @Test
    fun `unreadable stored filters fall back to the defaults`() {
        val stored = com.fetch.tv.data.local.StoredDiscoverFilters(sort = "SHUFFLE", minRating = 3, yearRange = "soon")

        assertEquals(DiscoverFilters(), stored.toDiscoverFilters())
    }

    private fun catalog(
        key: String,
        type: String,
        genres: List<String> = emptyList(),
        genreRequired: Boolean = false,
        defaultGenre: String? = null
    ) = DiscoverCatalog(
        key = key,
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://example.com",
        catalogId = key,
        catalogName = key,
        type = type,
        genres = genres,
        supportsSkip = true,
        skipStep = 100,
        genreRequired = genreRequired,
        defaultGenre = defaultGenre
    )

    @Test
    fun `hide watched leaves out watched titles and counts as narrowing (#857)`() {
        val items = listOf(item("a"), item("b"), item("c"))
        val filters = DiscoverFilters(hideWatched = true)
        assertTrue(filters.narrowsResults)
        assertEquals(listOf("a", "c"), applyDiscoverFilters(items, filters) { it.id == "b" }.map { it.id })
    }

    @Test
    fun `include unrated keeps titles with no rating beside a minimum rating (#857)`() {
        val items = listOf(item("high", rating = 8.2f), item("low", rating = 5.1f), item("none"))
        assertEquals(listOf("high"), applyDiscoverFilters(items, DiscoverFilters(minRating = 7)).map { it.id })
        assertEquals(
            listOf("high", "none"),
            applyDiscoverFilters(items, DiscoverFilters(minRating = 7, includeUnrated = true)).map { it.id }
        )
    }

    @Test
    fun `reset shows once anything is set, sort and include unrated included (#857)`() {
        assertEquals(false, DiscoverFilters().anySet)
        assertTrue(DiscoverFilters(sort = DiscoverSort.TITLE).anySet)
        assertTrue(DiscoverFilters(includeUnrated = true).anySet)
        assertTrue(DiscoverFilters(hideWatched = true).anySet)
    }

    @Test
    fun `the toggles survive being stored (#857)`() {
        val filters = DiscoverFilters(minRating = 6, sort = DiscoverSort.RATING, hideWatched = true, includeUnrated = true)
        assertEquals(filters, filters.toStored().toDiscoverFilters())
    }

    private fun item(
        id: String,
        type: ContentType = ContentType.MOVIE,
        name: String = id,
        releaseInfo: String? = "2020",
        released: String? = null,
        rating: Float? = null
    ) = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = releaseInfo,
        imdbRating = rating,
        genres = emptyList(),
        released = released
    )
}
