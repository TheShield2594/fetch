package com.fetch.tv.ui.screens.search

import android.content.Context
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.SearchHistoryDataStore
import com.fetch.tv.data.local.WatchedSeriesStateHolder
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CatalogExtra
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val FAST_CATALOG = "fast"
private const val SLOW_CATALOG = "slow"
private const val ADDON_ID = "addon"

// SearchViewModel's private LIVE_SEARCH_DEBOUNCE_MS and FIRST_RESULTS_SETTLE_MS.
private const val LIVE_SEARCH_DEBOUNCE_MS = 350L
private const val FIRST_RESULTS_SETTLE_MS = 800L

/**
 * A search's first results wait for its catalogs to answer, so the fastest addon's order is not
 * painted and then re-ranked under the user a moment later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelFirstResultsTest {

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
    fun `first results wait for every catalog, then show together`() = runTest {
        val viewModel = newViewModel(DelayedCatalogRepository(slowDelayMs = 300))

        viewModel.onEvent(SearchEvent.QueryChanged("landman"))
        advanceTimeBy(LIVE_SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(
            "the fast catalog was painted before the slow one answered: ${realTitles(viewModel)}",
            realTitles(viewModel).isEmpty()
        )

        advanceTimeBy(301)
        runCurrent()
        assertEquals(setOf("fast landman", "slow landman"), realTitles(viewModel).toSet())
    }

    @Test
    fun `a slow catalog holds the first results back no longer than the settle window`() = runTest {
        val viewModel = newViewModel(DelayedCatalogRepository(slowDelayMs = 10_000))

        viewModel.onEvent(SearchEvent.QueryChanged("landman"))
        advanceTimeBy(LIVE_SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(realTitles(viewModel).isEmpty())

        advanceTimeBy(FIRST_RESULTS_SETTLE_MS + 1)
        runCurrent()
        assertEquals(listOf("fast landman"), realTitles(viewModel))
    }

    private fun realTitles(viewModel: SearchViewModel): List<String> =
        viewModel.uiState.value.catalogRows
            .flatMap { row -> row.items }
            .filterNot { it.id.startsWith("__placeholder_") }
            .map { it.name }

    /** Answers the fast catalog at once and the slow one after [slowDelayMs]. */
    private class DelayedCatalogRepository(private val slowDelayMs: Long) : CatalogRepository {
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
            val query = extraArgs["search"].orEmpty()
            emit(NetworkResult.Loading)
            if (catalogId == SLOW_CATALOG) delay(slowDelayMs)
            emit(
                NetworkResult.Success(
                    CatalogRow(
                        addonId = ADDON_ID,
                        addonName = "Addon",
                        addonBaseUrl = "https://example.test",
                        catalogId = catalogId,
                        catalogName = catalogId,
                        type = ContentType.MOVIE,
                        items = listOf(
                            MetaPreview(
                                id = "${catalogId}_$query",
                                type = ContentType.MOVIE,
                                name = "$catalogId $query",
                                poster = null,
                                posterShape = PosterShape.POSTER,
                                background = null,
                                logo = null,
                                description = null,
                                releaseInfo = null,
                                imdbRating = null,
                                genres = emptyList()
                            )
                        ),
                        hasMore = false
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

    private fun newViewModel(repository: CatalogRepository): SearchViewModel {
        val addon = Addon(
            id = ADDON_ID,
            name = "Addon",
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://example.test",
            catalogs = listOf(FAST_CATALOG, SLOW_CATALOG).map { id ->
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = id,
                    name = id,
                    extra = listOf(CatalogExtra(name = "search"))
                )
            },
            types = listOf(ContentType.MOVIE),
            resources = emptyList()
        )

        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.posterCardWidthDp } returns flowOf(126)
        every { layoutPreferences.posterLabelsEnabled } returns flowOf(true)
        every { layoutPreferences.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferences.posterCardCornerRadiusDp } returns flowOf(12)
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.searchSystemKeyboard } returns flowOf(false)
        every { layoutPreferences.hideUnreleasedContent } returns flowOf(false)

        val history = mockk<SearchHistoryDataStore>(relaxed = true)
        every { history.recentSearches } returns flowOf(emptyList())

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns flowOf(emptySet())

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
