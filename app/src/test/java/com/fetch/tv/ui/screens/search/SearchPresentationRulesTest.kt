package com.fetch.tv.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPresentationRulesTest {

    @Test
    fun `a submitted query needs the minimum length`() {
        assertEquals("", submittedSearchQuery("a"))
        assertEquals("ab", submittedSearchQuery(" ab "))
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
