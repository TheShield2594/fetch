package com.fetch.tv.data.repository

import android.content.Context
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.TraktSettingsDataStore
import com.fetch.tv.data.local.WatchedSeriesStateHolder
import com.fetch.tv.data.remote.api.TraktApi
import com.fetch.tv.data.remote.dto.trakt.TraktIdsDto
import com.fetch.tv.data.remote.dto.trakt.TraktLastActivitiesMediaDto
import com.fetch.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.fetch.tv.data.remote.dto.trakt.TraktMovieDto
import com.fetch.tv.data.remote.dto.trakt.TraktWatchedMovieItemDto
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** NuvioTV#3873: an expired optimistic in-progress entry hid the watched checkmark of a movie. */
class TraktProgressServiceOptimisticWatchedTest {
    private val traktApi = mockk<TraktApi>()

    @Test
    fun `expired in-progress entry no longer hides a watched movie`() = runBlocking {
        val service = loadedService()
        service.optimisticTtlMs = TTL_MS

        service.applyOptimisticProgress(inProgressMovie())
        assertFalse(MOVIE_ID in service.observeAllWatchedMovieIds().first())
        assertFalse(service.observeMovieWatched(MOVIE_ID).first())

        delay(TTL_MS + SETTLE_MS)

        assertTrue(MOVIE_ID in service.observeAllWatchedMovieIds().first())
        assertTrue(service.observeMovieWatched(MOVIE_ID).first())
    }

    @Test
    fun `poll without changes re-emits watched movies once the entry expires`() = runBlocking {
        val service = loadedService()
        service.optimisticTtlMs = TTL_MS
        // Keep the next poll a plain poll, so it takes the early return without a fetch.
        service.forceRefreshWindowMs = 0L

        service.applyOptimisticProgress(inProgressMovie())
        val watchedAgain = async {
            withTimeout(TIMEOUT_MS) {
                service.observeAllWatchedMovieIds().first { MOVIE_ID in it }
            }
        }
        delay(TTL_MS + SETTLE_MS)
        assertFalse(watchedAgain.isCompleted)

        service.refreshRemoteSnapshot()

        assertTrue(MOVIE_ID in watchedAgain.await())
    }

    private fun inProgressMovie() = WatchProgress(
        contentId = MOVIE_ID,
        contentType = "movie",
        name = "Movie",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = MOVIE_ID,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 50_000L,
        duration = 100_000L,
        lastWatched = System.currentTimeMillis()
    )

    private suspend fun loadedService(): TraktProgressService {
        val service = createService()
        withTimeout(TIMEOUT_MS) {
            service.observeAllWatchedMovieIds().first { MOVIE_ID in it }
            service.observeRemoteProgressLoaded().first { it }
        }
        delay(SETTLE_MS)
        return service
    }

    private fun createService(): TraktProgressService {
        coEvery { traktApi.getLastActivities(any()) } returns Response.success(
            TraktLastActivitiesResponseDto(
                movies = TraktLastActivitiesMediaDto(watchedAt = "2026-01-01T00:00:00.000Z"),
                episodes = TraktLastActivitiesMediaDto(watchedAt = "2026-01-01T00:00:00.000Z")
            )
        )
        coEvery { traktApi.getPlayback(any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { traktApi.getEpisodeHistory(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { traktApi.getHiddenItems(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { traktApi.getWatched(any(), any(), any(), any(), any()) } answers {
            // One page of watched movies; later pages are empty.
            if (arg<Int>(3) > 1) {
                Response.success(emptyList())
            } else {
                Response.success(
                    listOf(
                        TraktWatchedMovieItemDto(
                            plays = 1,
                            movie = TraktMovieDto(title = "Movie", ids = TraktIdsDto(trakt = 1, imdb = MOVIE_ID))
                        )
                    )
                )
            }
        }
        coEvery { traktApi.getWatchedShows(any(), any(), any(), any()) } returns Response.success(emptyList())

        val authService = mockk<TraktAuthService>()
        every { authService.isCircuitClosed() } returns true
        coEvery { authService.executeAuthorizedRequest<Any?>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Any?>>().invoke("Bearer test")
        }

        val metaRepository = mockk<MetaRepository>()
        every { metaRepository.getMetaFromAllAddons(any(), any(), any()) } returns emptyFlow()
        val settings = mockk<TraktSettingsDataStore>()
        every { settings.continueWatchingDaysCap } returns
            flowOf(TraktSettingsDataStore.DEFAULT_CONTINUE_WATCHING_DAYS_CAP)
        val layout = mockk<LayoutPreferenceDataStore>()
        every { layout.nextUpFromFurthestEpisode } returns MutableStateFlow(false)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return TraktProgressService(
            appContext = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthService = authService,
            metaRepository = metaRepository,
            tmdbService = mockk<TmdbService>(relaxed = true),
            traktSettingsDataStore = settings,
            layoutPreferenceDataStore = layout,
            traktEpisodeMappingService = mockk(relaxed = true),
            profileManager = profileManager,
            watchedSeriesStateHolder = watchedSeries
        )
    }

    private companion object {
        const val MOVIE_ID = "tt0000001"
        const val TTL_MS = 500L
        const val TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 500L
    }
}
