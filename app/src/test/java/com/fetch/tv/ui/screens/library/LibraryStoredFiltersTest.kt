package com.fetch.tv.ui.screens.library

import com.fetch.tv.MainDispatcherRule
import com.fetch.tv.data.local.StoredLibraryFilters
import com.fetch.tv.data.mdblist.MdbListLibraryProjection
import com.fetch.tv.data.mdblist.MdbListLibrarySnapshot
import com.fetch.tv.data.mdblist.decodeMdbListLibraryPage
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The Library's Show, Genre and Year filters are remembered per profile, as Discover's are (#851). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryStoredFiltersTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun LibraryViewModelTestFixture.withItems(): LibraryViewModelTestFixture {
        val entries = decodeMdbListLibraryPage("""[
            {"id":1,"mediatype":"movie","title":"One"},
            {"id":2,"mediatype":"movie","title":"Two"}
        ]""").items
        items.value = MdbListLibraryProjection(MdbListLibrarySnapshot(itemsByList = mapOf(tab.key to entries))).entries
        return this
    }

    @Test
    fun `the Show filter last used on the profile comes back`() = runTest {
        val f = LibraryViewModelTestFixture(storedFilters = StoredLibraryFilters(watchedFilter = "unwatched"))
        runCurrent()

        assertEquals(LibraryWatchedFilter.UNWATCHED, f.viewModel.uiState.value.selectedWatchedFilter)
    }

    @Test
    fun `a stored genre is kept while the list loads and dropped once the list lacks it`() = runTest {
        val f = LibraryViewModelTestFixture(storedFilters = StoredLibraryFilters(genre = "Western"))
        runCurrent()
        assertEquals("Western", f.viewModel.uiState.value.selectedGenre)

        f.withItems()
        runCurrent()

        assertNull(f.viewModel.uiState.value.selectedGenre)
    }

    @Test
    fun `picks are saved, and Ready to play is not`() = runTest {
        val f = LibraryViewModelTestFixture().withItems()
        runCurrent()

        f.viewModel.onSelectWatchedFilter(LibraryWatchedFilter.WATCHED)
        runCurrent()
        assertEquals("watched", f.savedFilters?.watchedFilter)

        f.viewModel.onSelectWatchedFilter(LibraryWatchedFilter.READY)
        runCurrent()
        assertNull(f.savedFilters?.watchedFilter)

        f.viewModel.onClearFilters()
        runCurrent()
        assertEquals(StoredLibraryFilters(), f.savedFilters)
    }
}
