package com.fetch.tv.ui.screens.library

import com.fetch.tv.MainDispatcherRule
import com.fetch.tv.domain.model.LibraryEntry
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.PosterShape
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Audit C3: the Watchlist tab shows the watchlist, the Lists tab every other list. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryListScopeTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val watchlist = LibraryListTab("trakt:watchlist", "Watchlist", LibraryListTab.Type.WATCHLIST)
    private val personal = LibraryListTab("trakt:list:1", "Favourites", LibraryListTab.Type.PERSONAL)
    private val onThisTv = LibraryListTab("local:list:a", "Kids (this TV)", LibraryListTab.Type.EXTERNAL)

    @Test
    fun `each scope offers its own lists`() {
        val tabs = listOf(watchlist, personal, onThisTv)
        assertEquals(listOf(watchlist), tabs.inScope(LibraryListScope.WATCHLIST))
        assertEquals(listOf(personal, onThisTv), tabs.inScope(LibraryListScope.LISTS))
    }

    @Test
    fun `selection keeps the current list, then the remembered one, then the first`() {
        val tabs = listOf(watchlist, personal, onThisTv)
        assertEquals(onThisTv.key, libraryListSelection(tabs, LibraryListScope.LISTS, onThisTv.key, null))
        assertEquals(onThisTv.key, libraryListSelection(tabs, LibraryListScope.LISTS, watchlist.key, onThisTv.key))
        assertEquals(personal.key, libraryListSelection(tabs, LibraryListScope.LISTS, watchlist.key, null))
        assertEquals(watchlist.key, libraryListSelection(tabs, LibraryListScope.WATCHLIST, personal.key, personal.key))
        assertNull(libraryListSelection(listOf(watchlist), LibraryListScope.LISTS, null, null))
    }

    @Test
    fun `switching tabs switches the grid between the watchlist and a list`() = runTest {
        val f = LibraryViewModelTestFixture(scope = LibraryListScope.WATCHLIST)
        f.tabs.value = listOf(watchlist, personal)
        f.items.value = listOf(entry("tt1", "On the watchlist", watchlist.key), entry("tt2", "On a list", personal.key))
        runCurrent()

        assertEquals(listOf("On the watchlist"), f.viewModel.uiState.value.visibleItems.map { it.name })
        assertEquals(listOf(watchlist), f.viewModel.uiState.value.scopedListTabs)

        f.viewModel.onSelectListScope(LibraryListScope.LISTS)
        runCurrent()
        assertEquals(listOf("On a list"), f.viewModel.uiState.value.visibleItems.map { it.name })
        assertEquals(listOf(personal), f.viewModel.uiState.value.scopedListTabs)

        f.viewModel.onSelectListScope(LibraryListScope.WATCHLIST)
        runCurrent()
        assertEquals(listOf("On the watchlist"), f.viewModel.uiState.value.visibleItems.map { it.name })
    }

    @Test
    fun `the Lists tab with no lists shows nothing rather than the watchlist`() = runTest {
        val f = LibraryViewModelTestFixture(scope = LibraryListScope.LISTS)
        f.tabs.value = listOf(watchlist)
        f.items.value = listOf(entry("tt1", "On the watchlist", watchlist.key))
        runCurrent()

        assertTrue(f.viewModel.uiState.value.visibleItems.isEmpty())
        assertTrue(f.viewModel.uiState.value.scopedListTabs.isEmpty())
    }

    private fun entry(id: String, name: String, listKey: String) = LibraryEntry(
        id = id,
        type = "movie",
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listKeys = setOf(listKey),
        listAddedAt = mapOf(listKey to 1L)
    )
}
