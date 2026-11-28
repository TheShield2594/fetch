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

    @Test
    fun `a Show pick during a slow restore keeps the stored genre, on screen and on disk`() = runTest {
        val read = kotlinx.coroutines.CompletableDeferred<Unit>()
        val f = LibraryViewModelTestFixture(
            storedFilters = StoredLibraryFilters(watchedFilter = "unwatched", genre = "Western"),
            filtersRead = read
        )
        runCurrent()

        f.viewModel.onSelectWatchedFilter(LibraryWatchedFilter.WATCHED)
        runCurrent()
        read.complete(Unit)
        runCurrent()

        assertEquals(LibraryWatchedFilter.WATCHED, f.viewModel.uiState.value.selectedWatchedFilter)
        assertEquals("Western", f.viewModel.uiState.value.selectedGenre)
        assertEquals(StoredLibraryFilters(watchedFilter = "watched", genre = "Western"), f.storedState)
    }

    @Test
    fun `a restored genre waits through a list without it and comes back when one has it`() = runTest {
        val f = LibraryViewModelTestFixture(storedFilters = StoredLibraryFilters(genre = "Western"))
        fun entry(id: String, genres: List<String>) = com.fetch.tv.domain.model.LibraryEntry(
            id = id, type = "movie", name = id, poster = null, background = null, logo = null,
            description = null, releaseInfo = null, imdbRating = null, genres = genres,
            addonBaseUrl = null, listKeys = setOf(f.tab.key)
        )
        // The previous profile's titles are on screen when the restore lands.
        f.items.value = listOf(entry("old", listOf("Drama")))
        runCurrent()
        assertNull(f.viewModel.uiState.value.selectedGenre)

        // Half of the new profile's library: still no Western.
        f.items.value = listOf(entry("old", listOf("Drama")), entry("mixed", listOf("Comedy")))
        runCurrent()
        assertNull(f.viewModel.uiState.value.selectedGenre)

        f.items.value = listOf(entry("new", listOf("Western")))
        runCurrent()
        assertEquals("Western", f.viewModel.uiState.value.selectedGenre)
    }
}
