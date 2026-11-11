package com.fetch.tv.ui.screens.library

import com.fetch.tv.MainDispatcherRule
import com.fetch.tv.data.local.BestMatchCacheDataStore
import com.fetch.tv.data.local.CachedBestMatch
import com.fetch.tv.data.mdblist.MdbListLibraryProjection
import com.fetch.tv.data.mdblist.MdbListLibrarySnapshot
import com.fetch.tv.data.mdblist.decodeMdbListLibraryPage
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryReadyFilterTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun fixture(): LibraryViewModelTestFixture {
        val f = LibraryViewModelTestFixture()
        val items = decodeMdbListLibraryPage("""[
            {"id":1,"mediatype":"movie","title":"Ready movie"},
            {"id":2,"mediatype":"movie","title":"Unchecked movie"},
            {"id":3,"mediatype":"show","title":"A series"}
        ]""").items
        f.items.value = MdbListLibraryProjection(MdbListLibrarySnapshot(itemsByList = mapOf(f.tab.key to items))).entries
        return f
    }

    private fun LibraryViewModelTestFixture.markReady(title: String, savedAtMs: Long = System.currentTimeMillis()) {
        val entry = viewModel.uiState.value.allItems.first { it.name == title }
        bestMatches.value = bestMatches.value +
            (BestMatchCacheDataStore.contentKey("movie", entry.id) to CachedBestMatch(listOf("4K"), isCached = true, savedAtMs = savedAtMs))
    }

    @Test
    fun `Ready to play keeps only movies whose Best match is ready`() = runTest {
        val f = fixture()
        runCurrent()
        f.markReady("Ready movie")
        runCurrent()

        f.viewModel.onSelectWatchedFilter(LibraryWatchedFilter.READY)
        runCurrent()

        assertEquals(listOf("Ready movie"), f.viewModel.uiState.value.visibleItems.map { it.name })
    }

    @Test
    fun `a pick older than the ready window does not count`() = runTest {
        val f = fixture()
        runCurrent()
        f.markReady("Ready movie", savedAtMs = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000)
        runCurrent()

        f.viewModel.onSelectWatchedFilter(LibraryWatchedFilter.READY)
        runCurrent()

        assertTrue(f.viewModel.uiState.value.visibleItems.isEmpty())
        assertTrue(f.viewModel.uiState.value.filtersHideAllItems)
    }

    @Test
    fun `the option shows only while something is ready or it is chosen`() {
        assertFalse(LibraryWatchedFilter.READY in libraryWatchedFilterOptions(anythingReady = false, LibraryWatchedFilter.ALL))
        assertTrue(LibraryWatchedFilter.READY in libraryWatchedFilterOptions(anythingReady = true, LibraryWatchedFilter.ALL))
        assertTrue(LibraryWatchedFilter.READY in libraryWatchedFilterOptions(anythingReady = false, LibraryWatchedFilter.READY))
    }
}
