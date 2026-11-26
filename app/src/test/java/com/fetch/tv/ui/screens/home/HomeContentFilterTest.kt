package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeContentFilterTest {

    private fun item(id: String, type: String) = MetaPreview(
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
        genres = emptyList()
    )

    private fun row(id: String, type: String) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon",
        catalogId = id,
        catalogName = id,
        type = ContentType.fromString(type),
        rawType = type,
        items = listOf(item("$id-1", type))
    )

    private fun input(vararg rows: CatalogRow) = ModernHomePresentationInput(
        homeRows = rows.map(HomeRow::Catalog),
        catalogRows = rows.toList(),
        continueWatchingItems = emptyList(),
        upcomingItems = emptyList(),
        useLandscapePosters = false,
        showCatalogTypeSuffix = false,
        showFullReleaseDate = false,
        showImdbRatings = false,
        localeTag = "en"
    )

    @Test
    fun `filters match addon types`() {
        assertTrue(HomeContentFilter.MOVIES.matchesType("movie"))
        assertTrue(HomeContentFilter.SHOWS.matchesType("Series"))
        assertTrue(HomeContentFilter.ANIME.matchesType("anime"))
        assertTrue(HomeContentFilter.ALL.matchesType("channel"))
        assertEquals(false, HomeContentFilter.SHOWS.matchesType("movie"))
    }

    @Test
    fun `unknown stored names fall back to all`() {
        assertEquals(HomeContentFilter.ALL, HomeContentFilter.fromStored(null))
        assertEquals(HomeContentFilter.ALL, HomeContentFilter.fromStored("SPORTS"))
        assertEquals(HomeContentFilter.SHOWS, HomeContentFilter.fromStored("SHOWS"))
    }

    @Test
    fun `anime is offered only with anime catalogs`() {
        assertEquals(
            listOf(HomeContentFilter.ALL, HomeContentFilter.MOVIES, HomeContentFilter.SHOWS),
            availableHomeContentFilters(input(row("m", "movie"), row("s", "series")))
        )
        assertEquals(
            listOf(HomeContentFilter.ALL, HomeContentFilter.MOVIES, HomeContentFilter.SHOWS, HomeContentFilter.ANIME),
            availableHomeContentFilters(input(row("m", "movie"), row("s", "series"), row("a", "anime")))
        )
    }

    @Test
    fun `a show only in upcoming still offers shows`() {
        val upcoming = ContinueWatchingItem.NextUp(NextUpInfo(
            "show", "series", "Show", null, null, null, "show:2:1", 2, 1, "Upcoming",
            thumbnail = null, released = "2999-01-01", hasAired = false, airDateLabel = "Soon",
            lastWatched = 100, sortTimestamp = 100, isReleaseAlert = true, isNewSeasonRelease = true
        ))
        val filters = availableHomeContentFilters(input(row("m", "movie")).copy(upcomingItems = listOf(upcoming)))
        assertEquals(listOf(HomeContentFilter.ALL, HomeContentFilter.MOVIES, HomeContentFilter.SHOWS), filters)
    }

    @Test
    fun `one kind of content offers no filter`() {
        assertEquals(emptyList<HomeContentFilter>(), availableHomeContentFilters(input(row("m", "movie"), row("m2", "movie"))))
    }

    @Test
    fun `a filter keeps only rows of its type`() {
        val filtered = input(row("m", "movie"), row("s", "series")).filteredBy(HomeContentFilter.MOVIES)
        assertEquals(listOf("m"), filtered.catalogRows.map { it.catalogId })
        assertEquals(listOf("m"), filtered.homeRows.map { (it as HomeRow.Catalog).row.catalogId })
    }

    @Test
    fun `all keeps everything`() {
        val all = input(row("m", "movie"), row("s", "series"))
        assertEquals(all, all.filteredBy(HomeContentFilter.ALL))
    }
}
