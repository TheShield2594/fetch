package com.fetch.tv.core.search

import com.fetch.tv.core.deeplink.DeepLinkParser
import com.fetch.tv.domain.deeplink.AppDeepLink
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CatalogExtra
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlobalSearchTest {
    private fun meta(id: String, type: ContentType = ContentType.MOVIE, name: String = id, year: String? = "2021") =
        MetaPreview(
            id = id,
            type = type,
            name = name,
            poster = "https://img.example/$id.jpg",
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = "About $id",
            releaseInfo = year,
            imdbRating = null,
            genres = emptyList()
        )

    private fun addon(vararg catalogs: CatalogDescriptor) = Addon(
        id = "addon",
        name = "Addon",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.example",
        catalogs = catalogs.toList(),
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )

    @Test
    fun `query comes from the selection argument or the path after search_suggest_query`() {
        assertEquals("dune", globalSearchQuery(listOf("search_suggest_query", "dune"), null))
        assertEquals("dune", globalSearchQuery(listOf("search_suggest_query"), " dune "))
        assertNull(globalSearchQuery(listOf("search_suggest_query"), null))
        assertNull(globalSearchQuery(listOf("search_suggest_query", "  "), null))
    }

    @Test
    fun `only catalogs a search alone can fill are searched`() {
        val search = CatalogDescriptor(
            type = ContentType.MOVIE, id = "search", name = "Search",
            extra = listOf(CatalogExtra("search", isRequired = true))
        )
        val needsGenre = CatalogDescriptor(
            type = ContentType.MOVIE, id = "genre", name = "Genre",
            extra = listOf(CatalogExtra("search"), CatalogExtra("genre", isRequired = true))
        )
        val noSearch = CatalogDescriptor(type = ContentType.MOVIE, id = "top", name = "Top")
        assertEquals(listOf("search"), globalSearchTargets(listOf(addon(search, needsGenre, noSearch))).map { it.second.id })
    }

    @Test
    fun `results interleave catalogs, drop duplicates and channels, and stop at the limit`() {
        val first = listOf(meta("tt1"), meta("tt2"), meta("tt3"))
        val second = listOf(meta("tt1"), meta("ch", type = ContentType.CHANNEL), meta("tt4", type = ContentType.SERIES))
        assertEquals(
            listOf("tt1", "tt2", "tt3", "tt4"),
            mergeGlobalSearchResults(listOf(first, second), limit = 10).map { it.id }
        )
        assertEquals(listOf("tt1", "tt2"), mergeGlobalSearchResults(listOf(first, second), limit = 2).map { it.id })
    }

    @Test
    fun `a result opens its title through a fetch meta link`() {
        val result = meta("tmdb:42 x", type = ContentType.SERIES, year = "2019–2023").toGlobalSearchResult()!!
        assertEquals(2019, result.year)
        assertEquals(false, result.isMovie)
        assertEquals(AppDeepLink.Meta(type = "series", id = "tmdb:42 x"), DeepLinkParser.parse(result.deepLink))
    }

    @Test
    fun `titles with a blank imdb id are told apart by their own id`() {
        val rows = listOf(listOf(meta("kitsu:1").copy(imdbId = ""), meta("kitsu:2").copy(imdbId = "")))
        assertEquals(listOf("kitsu:1", "kitsu:2"), mergeGlobalSearchResults(rows, limit = 10).map { it.id })
    }

    @Test
    fun `a result's link opens it from the addon that found it`() {
        val result = meta("tt1").copy(sourceAddonBaseUrl = "https://addon.example/abc").toGlobalSearchResult()!!
        assertEquals(
            AppDeepLink.Meta(type = "movie", id = "tt1", addonBaseUrl = "https://addon.example/abc"),
            DeepLinkParser.parse(result.deepLink)
        )
    }

    @Test
    fun `the cache key changes when the catalogs searched change`() {
        val search = CatalogDescriptor(
            type = ContentType.MOVIE, id = "search", name = "Search",
            extra = listOf(CatalogExtra("search", isRequired = true))
        )
        val one = globalSearchTargets(listOf(addon(search)))
        val other = globalSearchTargets(listOf(addon(search).copy(baseUrl = "https://other.example")))
        assertEquals(globalSearchCacheKey(1, "Dune", one), globalSearchCacheKey(1, "dune", one))
        assertNotEquals(globalSearchCacheKey(1, "dune", one), globalSearchCacheKey(1, "dune", other))
        assertNotEquals(globalSearchCacheKey(1, "dune", one), globalSearchCacheKey(1, "dune", emptyList()))
    }

    @Test
    fun `a poster that is not a web address is left out`() {
        val result = meta("tt1").copy(poster = "file:///data/poster.jpg").toGlobalSearchResult()!!
        assertNull(result.posterUrl)
    }
}
