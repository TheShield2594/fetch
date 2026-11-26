package com.fetch.tv.ui.screens.home

import com.fetch.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModernHomeSeeAllFocusTest {

    @Test
    fun `a catalog row with a See all card can focus one past its last item`() {
        val row = row(itemCount = 5, catalogId = "top", addonId = "addon", apiType = "movie")
        assertEquals(5, row.maxFocusIndex())
    }

    @Test
    fun `a row without a See all card stops at its last item`() {
        val row = row(itemCount = 5, catalogId = null, addonId = null, apiType = null)
        assertEquals(4, row.maxFocusIndex())
    }

    @Test
    fun `a loading row hides See all, so focus stops at its last item`() {
        val row = row(itemCount = 5, catalogId = "top", addonId = "addon", apiType = "movie", isLoading = true)
        assertEquals(4, row.maxFocusIndex())
    }

    @Test
    fun `an empty row focuses index zero`() {
        val row = row(itemCount = 0, catalogId = "top", addonId = "addon", apiType = "movie")
        assertEquals(0, row.maxFocusIndex())
    }

    @Test
    fun `See all focus is saved under its own key and restored to its index`() {
        val row = row(itemCount = 5, catalogId = "top", addonId = "addon", apiType = "movie")
        assertEquals(SEE_ALL_FOCUS_KEY, row.savedFocusKeyAt(5))
        assertEquals(5, row.focusIndexForSavedKey(SEE_ALL_FOCUS_KEY))
    }

    @Test
    fun `a saved See all key restores nothing when the row no longer shows See all`() {
        val row = row(itemCount = 5, catalogId = "top", addonId = "addon", apiType = "movie", isLoading = true)
        assertNull(row.focusIndexForSavedKey(SEE_ALL_FOCUS_KEY))
    }

    @Test
    fun `ordinary cards still save and restore by item key`() {
        val row = row(itemCount = 5, catalogId = "top", addonId = "addon", apiType = "movie")
        assertEquals("item_3", row.savedFocusKeyAt(3))
        assertEquals(3, row.focusIndexForSavedKey("item_3"))
        assertNull(row.focusIndexForSavedKey("missing"))
        assertNull(row.focusIndexForSavedKey(""))
    }

    private fun row(
        itemCount: Int,
        catalogId: String?,
        addonId: String?,
        apiType: String?,
        isLoading: Boolean = false
    ): HeroCarouselRow =
        HeroCarouselRow(
            key = "row",
            title = "Row",
            globalRowIndex = 0,
            items = StableList((0 until itemCount).map { item("item_$it") }),
            catalogId = catalogId,
            addonId = addonId,
            apiType = apiType,
            isLoading = isLoading
        )

    private fun item(key: String): ModernCarouselItem =
        ModernCarouselItem(
            key = key,
            title = key,
            subtitle = null,
            imageUrl = "https://example.test/$key.jpg",
            heroPreview = HeroPreview(
                title = key,
                logo = null,
                description = null,
                contentTypeText = null,
                yearText = null,
                imdbText = null,
                genres = StableList(emptyList()),
                poster = null,
                backdrop = null,
                imageUrl = null
            ),
            payload = ModernPayload.Catalog(
                focusKey = key,
                itemId = key,
                itemType = "movie",
                addonBaseUrl = "https://example.test/manifest.json",
                trailerTitle = key,
                trailerReleaseInfo = null,
                trailerApiType = "movie"
            )
        )
}
