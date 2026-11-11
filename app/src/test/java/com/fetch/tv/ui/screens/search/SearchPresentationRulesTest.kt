package com.fetch.tv.ui.screens.search

import com.fetch.tv.domain.model.DiscoverLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPresentationRulesTest {

    @Test
    fun `one character remains in discover without becoming a submitted search`() {
        assertEquals("", submittedSearchQuery("a"))
        assertTrue(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.IN_SEARCH,
                query = "a",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `minimum query leaves discover while live search is pending`() {
        assertEquals("ab", submittedSearchQuery(" ab "))
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.IN_SEARCH,
                query = "ab",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `discover location off never enters discover mode`() {
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.OFF,
                query = "",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `stored discover type is restored across media types`() {
        val types = discoverTypes(listOf(discoverCatalog("current", "movie"), discoverCatalog("stored", "series")))

        assertEquals("series", resolveDiscoverType(types, preferredType = "series", currentType = "movie"))
    }

    @Test
    fun `current discover type is retained when stored type is unavailable`() {
        val types = listOf("movie", "series")

        assertEquals("series", resolveDiscoverType(types, preferredType = "anime", currentType = "series"))
        assertEquals("movie", resolveDiscoverType(types, preferredType = null, currentType = null))
    }

    private fun discoverCatalog(key: String, type: String) = DiscoverCatalog(
        key = key,
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://example.com",
        catalogId = key,
        catalogName = key,
        type = type,
        genres = emptyList(),
        supportsSkip = false,
        skipStep = 100
    )
}
