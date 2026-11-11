package com.fetch.tv.data.repository

import com.fetch.tv.core.tracking.LocalList
import com.fetch.tv.core.tracking.localListKey
import com.fetch.tv.domain.model.LibraryEntry
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.SavedLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit C3: with a tracking service connected, the profile's lists on this TV still show. */
class LocalListsBesideProviderTest {

    @Test
    fun `a local list's titles join the provider's, a shared title gaining the list's key`() {
        val provider = listOf(entry("tt1", "trakt:watchlist"))
        val list = LocalList(id = "a", name = "Kids", items = listOf(saved("tt1"), saved("tt2")))

        val merged = withLocalListEntries(provider, listOf(list)).associateBy { it.id }

        assertEquals(setOf("trakt:watchlist", localListKey("a")), merged.getValue("tt1").listKeys)
        assertEquals(setOf(localListKey("a")), merged.getValue("tt2").listKeys)
        assertEquals(2, merged.size)
    }

    @Test
    fun `no local lists leaves the provider's titles as they are`() {
        val provider = listOf(entry("tt1", "trakt:watchlist"))
        assertEquals(provider, withLocalListEntries(provider, emptyList()))
    }

    @Test
    fun `a local list is a read-only tab beside the provider's own lists`() {
        val tab = LocalList(id = "a", name = "Kids").toProviderSideTab("Kids (this TV)")

        assertEquals(localListKey("a"), tab.key)
        assertEquals("Kids (this TV)", tab.title)
        // Not PERSONAL: the provider's Manage lists dialog only edits its own lists.
        assertEquals(LibraryListTab.Type.EXTERNAL, tab.type)
    }

    private fun entry(id: String, listKey: String) = LibraryEntry(
        id = id, type = "movie", name = id, poster = null, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList(),
        addonBaseUrl = null, listKeys = setOf(listKey)
    )

    private fun saved(id: String) = SavedLibraryItem(
        id = id, type = "movie", name = id, poster = null, posterShape = PosterShape.POSTER,
        background = null, description = null, releaseInfo = null, imdbRating = null,
        genres = emptyList(), addonBaseUrl = null, addedAt = 5L
    )
}
