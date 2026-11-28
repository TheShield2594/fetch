package com.fetch.tv.ui.screens.search

import android.content.Context
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.SearchHistoryDataStore
import com.fetch.tv.data.local.WatchedSeriesStateHolder
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.DiscoverLocation
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.domain.repository.CatalogRepository
import com.fetch.tv.domain.repository.WatchProgressRepository
import com.fetch.tv.ui.components.posteroptions.PosterOptionsController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A Rating, Newest or Title sort orders a fixed batch of loaded titles once, and more titles only
 * ever arrive below it (#843): a page landing never moves what is already on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelDiscoverSortTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a sort loads a full batch before it orders the grid`() = runTest(mainDispatcher) {
        val viewModel = newViewModel(PagedCatalog(pageSize = 50, pages = 10))
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertEquals(DiscoverSort.CATALOG, viewModel.uiState.value.discoverFilters.sort)

        viewModel.onEvent(SearchEvent.SelectDiscoverSort(DiscoverSort.RATING))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(300, state.discoverSortedTitleCount)
        assertEquals(300, state.discoverResults.size)
        val ratings = state.discoverResults.map { it.imdbRating ?: 0f }
        assertEquals(ratings.sortedDescending(), ratings)
        assertTrue(state.discoverHasMore)
        assertFalse(state.discoverLoading || state.discoverLoadingMore)
    }

    @Test
    fun `load more appends a sorted batch below and leaves the first in place`() = runTest(mainDispatcher) {
        val viewModel = newViewModel(PagedCatalog(pageSize = 50, pages = 10))
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.SelectDiscoverSort(DiscoverSort.RATING))
        advanceUntilIdle()
        val firstBatch = viewModel.uiState.value.discoverResults.map { it.id }

        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(500, state.discoverSortedTitleCount)
        assertEquals(firstBatch, state.discoverResults.take(firstBatch.size).map { it.id })
        val secondBatch = state.discoverResults.drop(firstBatch.size).map { it.imdbRating ?: 0f }
        assertEquals(200, secondBatch.size)
        assertEquals(secondBatch.sortedDescending(), secondBatch)
        assertFalse(state.discoverHasMore)
    }

    @Test
    fun `back to catalog order shows everything loaded in catalog order`() = runTest(mainDispatcher) {
        val viewModel = newViewModel(PagedCatalog(pageSize = 50, pages = 10))
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.SelectDiscoverSort(DiscoverSort.TITLE))
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.SelectDiscoverSort(DiscoverSort.CATALOG))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.discoverSortedTitleCount)
        assertEquals((0 until state.discoverResults.size).map { "tt$it" }, state.discoverResults.map { it.id })
    }

    @Test
    fun `a round larger than a batch keeps the rest for the next batch`() = runTest(mainDispatcher) {
        // One page of 400 titles: more than a batch arrives at once.
        val viewModel = newViewModel(PagedCatalog(pageSize = 400, pages = 1))
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.SelectDiscoverSort(DiscoverSort.RATING))
        advanceUntilIdle()

        val first = viewModel.uiState.value
        assertEquals(300, first.discoverSortedTitleCount)
        assertEquals(300, first.discoverResults.size)
        assertTrue("the last 100 titles wait for Load more", first.discoverHasMore)

        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        advanceUntilIdle()

        val second = viewModel.uiState.value
        assertEquals(400, second.discoverSortedTitleCount)
        assertEquals(first.discoverResults.map { it.id }, second.discoverResults.take(300).map { it.id })
        assertEquals(400, second.discoverResults.size)
        assertFalse(second.discoverHasMore)
    }

    @Test
    fun `a watched change keeps partial sorted batches apart (#857)`() = runTest(mainDispatcher) {
        // Small pages close each sorted batch early, at the round limit, so both batches are partial.
        val watchedMovies = MutableStateFlow(emptySet<String>())
        val viewModel = newViewModel(PagedCatalog(pageSize = 10, pages = 100), watchedMovies)
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.ToggleDiscoverHideWatched)
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.SelectDiscoverSort(DiscoverSort.RATING))
        advanceUntilIdle()
        val firstBatchSize = viewModel.uiState.value.discoverResults.size
        assertTrue("the first batch should be partial, got $firstBatchSize", firstBatchSize in 1 until 300)
        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        advanceUntilIdle()
        val before = viewModel.uiState.value
        assertTrue("a second batch should follow", before.discoverResults.size > firstBatchSize)

        // Watching a title from the first batch drops just that title; nothing from the second
        // batch moves up into the first, as one merged re-sort would do.
        val watched = before.discoverResults[firstBatchSize / 2].id
        watchedMovies.value = setOf(watched)
        advanceUntilIdle()
        val after = viewModel.uiState.value
        assertEquals(before.discoverResults.map { it.id } - watched, after.discoverResults.map { it.id })
        assertEquals(before.discoverSortedTitleCount, after.discoverSortedTitleCount)

        // Marking it unwatched puts it back where it was.
        watchedMovies.value = emptySet()
        advanceUntilIdle()
        assertEquals(before.discoverResults.map { it.id }, viewModel.uiState.value.discoverResults.map { it.id })
    }

    /** One browsable catalog: [pages] pages of [pageSize] titles, with ratings in no order. */
    private class PagedCatalog(private val pageSize: Int, private val pages: Int) : CatalogRepository {
        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean,
            posterScreen: com.fetch.tv.core.poster.CustomPosterScreen,
            cachedFirst: Boolean
        ): Flow<NetworkResult<CatalogRow>> = flow {
            emit(NetworkResult.Loading)
            val total = pageSize * pages
            val items = (skip until minOf(skip + pageSize, total)).map { index ->
                MetaPreview(
                    id = "tt$index",
                    type = ContentType.MOVIE,
                    name = "Title $index",
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = "2020",
                    // Spread over 1.0..9.9 without following the catalog order.
                    imdbRating = 1f + (index * 37 % 90) / 10f,
                    genres = emptyList()
                )
            }
            emit(
                NetworkResult.Success(
                    CatalogRow(
                        addonId = "addon",
                        addonName = "Addon",
                        addonBaseUrl = "https://example.test",
                        catalogId = catalogId,
                        catalogName = catalogName,
                        type = ContentType.MOVIE,
                        items = items,
                        hasMore = skip + pageSize < total,
                        nextSkip = skip + pageSize,
                        supportsSkip = true
                    )
                )
            )
        }
    }

    private class SingleAddonRepository(private val addon: Addon) : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(listOf(addon))
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
        override suspend fun addAddon(url: String) = error("unused")
        override suspend fun removeAddon(url: String) = error("unused")
        override suspend fun setAddonOrder(urls: List<String>) = error("unused")
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
    }

    private fun newViewModel(
        repository: CatalogRepository,
        watchedMovies: Flow<Set<String>> = flowOf(emptySet())
    ): SearchViewModel {
        val addon = Addon(
            id = "addon",
            name = "Addon",
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://example.test",
            catalogs = listOf(CatalogDescriptor(type = ContentType.MOVIE, id = "top", name = "Top")),
            types = listOf(ContentType.MOVIE),
            resources = emptyList()
        )

        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.discoverLocation } returns flowOf(DiscoverLocation.IN_SIDEBAR)
        every { layoutPreferences.posterCardWidthDp } returns flowOf(126)
        every { layoutPreferences.posterLabelsEnabled } returns flowOf(true)
        every { layoutPreferences.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferences.posterCardHeightDp } returns flowOf(189)
        every { layoutPreferences.posterCardCornerRadiusDp } returns flowOf(12)
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.searchSystemKeyboard } returns flowOf(false)
        every { layoutPreferences.hideUnreleasedContent } returns flowOf(false)

        val history = mockk<SearchHistoryDataStore>(relaxed = true)
        every { history.recentSearches } returns flowOf(emptyList())

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns watchedMovies

        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return SearchViewModel(
            addonRepository = SingleAddonRepository(addon),
            catalogRepository = repository,
            metaRepository = mockk(relaxed = true),
            discoverSelectionDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = layoutPreferences,
            searchHistoryDataStore = history,
            watchProgressRepository = watchProgress,
            watchedSeriesStateHolder = watchedSeries,
            posterOptions = mockk<PosterOptionsController>(relaxed = true),
            serverCatalog = mockk { every { searchAddons } returns flowOf(emptyList()) },
            parentalControls = com.fetch.tv.unrestrictedParentalControls(),
            remoteCommandBus = com.fetch.tv.core.remote.RemoteCommandBus(),
            context = mockk<Context>(relaxed = true),
            peopleSearch = mockk { io.mockk.coEvery { search(any()) } returns emptyList() },
            libraryRepository = mockk { every { libraryItems } returns flowOf(emptyList()) },
            titleVisibility = com.fetch.tv.unfilteredTitleVisibility()
        )
    }
}
