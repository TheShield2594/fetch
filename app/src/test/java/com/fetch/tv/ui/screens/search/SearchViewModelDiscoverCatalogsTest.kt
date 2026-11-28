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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Discover's catalogs follow addon changes, stale results are fetched again on entry, a failed
 * load offers Retry (#845), and a Rating filter leaves catalogs without ratings out of paging (#864).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelDiscoverCatalogsTest {

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
    fun `a rating filter stops paging catalogs that send no ratings (#864)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        val viewModel = newViewModel(catalogs, MutableStateFlow(listOf(addon("rated", "unrated"))))
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertEquals(1, catalogs.requests("unrated"))

        viewModel.onEvent(SearchEvent.SelectDiscoverMinRating(7))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("the rated catalog keeps paging", catalogs.requests("rated") > 1)
        assertEquals("the unrated catalog is not asked again", 1, catalogs.requests("unrated"))
        assertEquals(1, state.discoverUnratedSourceCount)
        assertTrue(state.discoverResults.all { (it.imdbRating ?: 0f) >= 7f })
        assertTrue(state.discoverHasMore)

        // Clearing the filter brings the unrated catalog back into paging.
        viewModel.onEvent(SearchEvent.SelectDiscoverMinRating(null))
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        advanceUntilIdle()
        assertEquals(2, catalogs.requests("unrated"))
    }

    @Test
    fun `include unrated keeps paging catalogs without ratings (#864)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        val viewModel = newViewModel(catalogs, MutableStateFlow(listOf(addon("rated", "unrated"))))
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.SelectDiscoverMinRating(7))
        viewModel.onEvent(SearchEvent.ToggleDiscoverIncludeUnrated)
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        advanceUntilIdle()

        assertTrue(catalogs.requests("unrated") > 1)
    }

    @Test
    fun `an addon change rebuilds the catalogs without a restart (#845)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        val installed = MutableStateFlow(listOf(addon("rated")))
        val viewModel = newViewModel(catalogs, installed)
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.discoverSourceCount)
        assertEquals(0, catalogs.requests("unrated"))

        installed.value = listOf(addon("rated", "unrated"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.discoverCatalogs.size)
        assertEquals(2, state.discoverSourceCount)
        assertEquals(1, catalogs.requests("unrated"))
        assertTrue(state.discoverResults.any { it.id.startsWith("unrated") })
    }

    @Test
    fun `an unchanged catalog list does not reload (#845)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        val installed = MutableStateFlow(listOf(addon("rated")))
        val viewModel = newViewModel(catalogs, installed)
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        val requests = catalogs.requests("rated")

        installed.value = listOf(addon("rated"))
        advanceUntilIdle()

        assertEquals(requests, catalogs.requests("rated"))
    }

    @Test
    fun `a failed catalog load shows an error and Retry reads the addons again (#845)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        var fail = true
        val addons = flow {
            if (fail) throw IllegalStateException("database closed")
            emit(listOf(addon("rated")))
        }
        val viewModel = newViewModel(catalogs, addons)
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        val failed = viewModel.uiState.value
        assertNotNull(failed.discoverError)
        assertTrue(failed.discoverCatalogs.isEmpty())

        fail = false
        viewModel.onEvent(SearchEvent.RetryDiscover)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.discoverError)
        assertEquals(1, state.discoverCatalogs.size)
        assertTrue(state.discoverResults.isNotEmpty())
    }

    @Test
    fun `stale results are fetched again on entry, keeping the grid (#845)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        val viewModel = newViewModel(catalogs, MutableStateFlow(listOf(addon("rated"))))
        var now = 0L
        viewModel.discoverClockMs = { now }
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        val requests = catalogs.requests("rated")

        now = 10 * 60 * 1000L
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertEquals("fresh results are kept", requests, catalogs.requests("rated"))

        now = 31 * 60 * 1000L
        viewModel.ensureDiscoverLoaded(allowRefresh = false)
        advanceUntilIdle()
        assertEquals("no refetch when coming back from a title", requests, catalogs.requests("rated"))

        viewModel.ensureDiscoverLoaded()
        // The grid stays on screen while the first page is fetched again.
        assertTrue(viewModel.uiState.value.discoverResults.isNotEmpty())
        advanceUntilIdle()
        assertTrue(catalogs.requests("rated") > requests)
        assertTrue(viewModel.uiState.value.discoverResults.isNotEmpty())
    }

    @Test
    fun `a refetch that fails everywhere keeps the grid (#845)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        val viewModel = newViewModel(catalogs, MutableStateFlow(listOf(addon("rated"))))
        var now = 0L
        viewModel.discoverClockMs = { now }
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        val before = viewModel.uiState.value.discoverResults.map { it.id }

        catalogs.failing = true
        now = 31 * 60 * 1000L
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(before, state.discoverResults.map { it.id })
        assertNull(state.discoverError)
        assertFalse(state.discoverLoading)
    }

    @Test
    fun `a refresh replacing a refresh in flight still puts the grid back (#845)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        val hideUnreleased = MutableStateFlow(false)
        val viewModel = newViewModel(catalogs, MutableStateFlow(listOf(addon("rated"))), hideUnreleased)
        var now = 0L
        viewModel.discoverClockMs = { now }
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        val before = viewModel.uiState.value.discoverResults.map { it.id }

        catalogs.failing = true
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        catalogs.hold = hold
        now = 31 * 60 * 1000L
        viewModel.ensureDiscoverLoaded()
        runCurrent()
        // A second keep-grid refresh starts while the first waits on its page.
        hideUnreleased.value = true
        runCurrent()
        hold.complete(Unit)
        advanceUntilIdle()

        val results = viewModel.uiState.value.discoverResults
        val released = before.filterNot { id -> id.removePrefix("rated").toInt() % 10 == 5 }
        assertEquals(released, results.map { it.id })
        assertNull(viewModel.uiState.value.discoverError)
    }

    @Test
    fun `a filter set during a failed refresh applies to the grid put back (#845)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        val viewModel = newViewModel(catalogs, MutableStateFlow(listOf(addon("rated"))))
        var now = 0L
        viewModel.discoverClockMs = { now }
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()

        catalogs.failing = true
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        catalogs.hold = hold
        now = 31 * 60 * 1000L
        viewModel.ensureDiscoverLoaded()
        runCurrent()
        viewModel.onEvent(SearchEvent.SelectDiscoverMinRating(7))
        hold.complete(Unit)
        advanceUntilIdle()

        val results = viewModel.uiState.value.discoverResults
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { (it.imdbRating ?: 0f) >= 7f })
    }

    @Test
    fun `a failed rebuild after a catalog is removed does not page it again (#845)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        val installed = MutableStateFlow(listOf(addon("rated", "unrated")))
        val viewModel = newViewModel(catalogs, installed)
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        val unratedRequests = catalogs.requests("unrated")

        catalogs.failing = true
        installed.value = listOf(addon("rated"))
        advanceUntilIdle()
        catalogs.failing = false
        viewModel.onEvent(SearchEvent.LoadNextDiscoverResults)
        viewModel.onEvent(SearchEvent.RetryDiscover)
        advanceUntilIdle()

        assertEquals(unratedRequests, catalogs.requests("unrated"))
        assertTrue(viewModel.uiState.value.discoverResults.none { it.id.startsWith("unrated") })
    }

    @Test
    fun `turning on Hide unreleased reloads Discover (#845)`() = runTest(mainDispatcher) {
        val catalogs = FakeCatalogs()
        val hideUnreleased = MutableStateFlow(false)
        val viewModel = newViewModel(catalogs, MutableStateFlow(listOf(addon("rated"))), hideUnreleased)
        viewModel.ensureDiscoverLoaded()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.discoverResults.any { it.releaseInfo == NEXT_YEAR })

        hideUnreleased.value = true
        advanceUntilIdle()

        val results = viewModel.uiState.value.discoverResults
        assertTrue(results.isNotEmpty())
        assertTrue(results.none { it.releaseInfo == NEXT_YEAR })
    }

    private companion object {
        /** A release year that is still to come, which Hide unreleased leaves out. */
        val NEXT_YEAR = (java.time.LocalDate.now().year + 1).toString()
    }

    /**
     * Catalogs of 20 titles a page and 50 pages. Titles from "unrated" come without ratings; in
     * "rated" one title in ten is rated 8, the rest 5. Every tenth title is unreleased.
     */
    private class FakeCatalogs : CatalogRepository {
        private val counts = mutableMapOf<String, Int>()
        var failing = false
        /** While set, pages wait for it, so a test can act while a load is in flight. */
        var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        fun requests(catalogId: String): Int = counts[catalogId] ?: 0

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
            counts[catalogId] = requests(catalogId) + 1
            hold?.await()
            if (failing) {
                emit(NetworkResult.Error("offline"))
                return@flow
            }
            val pageSize = 20
            val total = pageSize * 50
            val items = (skip until minOf(skip + pageSize, total)).map { index ->
                MetaPreview(
                    id = "$catalogId$index",
                    type = ContentType.MOVIE,
                    name = "Title $index",
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = if (index % 10 == 5) NEXT_YEAR else "2020",
                    imdbRating = if (catalogId == "unrated") null else if (index % 10 == 0) 8f else 5f,
                    genres = emptyList()
                )
            }
            emit(
                NetworkResult.Success(
                    CatalogRow(
                        addonId = addonId,
                        addonName = addonName,
                        addonBaseUrl = addonBaseUrl,
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

    private class FlowAddonRepository(private val addons: Flow<List<Addon>>) : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = addons
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
        override suspend fun addAddon(url: String) = error("unused")
        override suspend fun removeAddon(url: String) = error("unused")
        override suspend fun setAddonOrder(urls: List<String>) = error("unused")
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
    }

    private fun addon(vararg catalogIds: String) = Addon(
        id = "addon",
        name = "Addon",
        version = "1",
        description = null,
        logo = null,
        baseUrl = "https://example.test",
        catalogs = catalogIds.map { CatalogDescriptor(type = ContentType.MOVIE, id = it, name = it) },
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )

    private fun newViewModel(
        repository: CatalogRepository,
        addons: Flow<List<Addon>>,
        hideUnreleased: Flow<Boolean> = flowOf(false)
    ): SearchViewModel {
        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.discoverLocation } returns flowOf(DiscoverLocation.IN_SIDEBAR)
        every { layoutPreferences.posterCardWidthDp } returns flowOf(126)
        every { layoutPreferences.posterLabelsEnabled } returns flowOf(true)
        every { layoutPreferences.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferences.posterCardHeightDp } returns flowOf(189)
        every { layoutPreferences.posterCardCornerRadiusDp } returns flowOf(12)
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.searchSystemKeyboard } returns flowOf(false)
        every { layoutPreferences.hideUnreleasedContent } returns hideUnreleased

        val history = mockk<SearchHistoryDataStore>(relaxed = true)
        every { history.recentSearches } returns flowOf(emptyList())

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns flowOf(emptySet())

        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return SearchViewModel(
            addonRepository = FlowAddonRepository(addons),
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
