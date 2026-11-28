package com.fetch.tv.data.repository

import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.core.tracking.TrackingHistoryWriterRegistry
import com.fetch.tv.core.tracking.TrackingProgressProvider
import com.fetch.tv.core.tracking.TrackingProgressProviderRegistry
import com.fetch.tv.core.tracking.TrackingProviderId
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.TraktSettingsDataStore
import com.fetch.tv.data.local.WatchProgressPreferences
import com.fetch.tv.data.local.WatchProgressSource
import com.fetch.tv.data.local.WatchedItemsPreferences
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WatchProgressRepositoryProfileIsolationTest {
    @Test
    fun `explicit save writes only its playback profile`() = runTest {
        val harness = harness(activeProfileId = 2)
        val progress = progress("item", position = 2_000L, duration = 10_000L)

        harness.repository.saveProgress(progress, profileId = 1, syncRemote = true)

        coVerify(exactly = 1) {
            harness.progressPreferences.saveProgress(progress, profileId = 1)
        }
        coVerify(exactly = 0) {
            harness.progressPreferences.saveProgress(progress, profileId = 2)
        }
    }

    @Test
    fun `completion marks one profile watched`() = runTest {
        val harness = harness(activeProfileId = 2)
        val progress = progress("item", position = 2_000L, duration = 10_000L)

        harness.repository.markAsCompleted(
            progress = progress,
            profileId = 1,
            broadcastTrackingHistory = false
        )

        coVerify(exactly = 1) {
            harness.progressPreferences.saveProgress(
                match { it.contentId == "item" && it.position == 10_000L && it.duration == 10_000L },
                profileId = 1
            )
        }
        coVerify(exactly = 1) {
            harness.watchedPreferences.markAsWatched(
                match { it.contentId == "item" },
                profileId = 1
            )
        }
    }

    @Test
    fun `removal deletes from the profile active when it started`() = runTest {
        val harness = harness(activeProfileId = 1)

        harness.repository.removeProgress("item")

        coVerify(exactly = 1) {
            harness.progressPreferences.removeProgress("item", null, null, 1)
        }
        coVerify(exactly = 0) {
            harness.progressPreferences.removeProgress("item", null, null, 2)
        }
    }

    @Test
    fun `explicit removal deletes its own profile and skips the other profile's trackers`() = runTest {
        val provider = trackingProvider()
        val harness = harness(activeProfileId = 2, providers = setOf(provider))

        harness.repository.removeProgress("item", null, null, profileId = 1)

        coVerify(exactly = 1) {
            harness.progressPreferences.removeProgress("item", null, null, 1)
        }
        coVerify(exactly = 0) {
            harness.progressPreferences.removeProgress("item", null, null, 2)
        }
        coVerify(exactly = 0) { provider.removeProgress(any(), any(), any()) }
    }

    @Test
    fun `a profile switch during the tracker call does not redirect the local delete`() = runTest {
        val provider = trackingProvider()
        val harness = harness(activeProfileId = 1, providers = setOf(provider))
        coEvery { provider.removeProgress("item", null, null) } coAnswers {
            harness.activeProfile.value = 2
        }

        harness.repository.removeProgress("item", null, null, profileId = 1)

        coVerify(exactly = 1) { provider.removeProgress("item", null, null) }
        coVerify(exactly = 1) {
            harness.progressPreferences.removeProgress("item", null, null, 1)
        }
        coVerify(exactly = 0) {
            harness.progressPreferences.removeProgress("item", null, null, 2)
        }
    }

    private fun trackingProvider(): TrackingProgressProvider {
        val provider = mockk<TrackingProgressProvider>(relaxed = true)
        every { provider.providerId } returns TrackingProviderId.entries.first()
        every { provider.isAuthenticated } returns flowOf(true)
        return provider
    }

    private fun harness(
        activeProfileId: Int,
        providers: Set<TrackingProgressProvider> = emptySet()
    ): Harness {
        val activeProfile = MutableStateFlow(activeProfileId)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile

        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true)
        coEvery { progressPreferences.getAllRawEntries(any()) } returns emptyMap()
        val watchedPreferences = mockk<WatchedItemsPreferences>(relaxed = true)
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true)
        every { traktSettings.watchProgressSource } returns MutableStateFlow(WatchProgressSource.FETCH_SYNC)

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = progressPreferences,
            traktSettingsDataStore = traktSettings,
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            watchedItemsPreferences = watchedPreferences,
            metaRepository = mockk<MetaRepository>(relaxed = true),
            tmdbService = mockk<TmdbService>(relaxed = true),
            profileManager = profileManager,
            trackingProgressProviders = TrackingProgressProviderRegistry(providers),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(emptySet()),
            serverWatched = mockk(relaxed = true)
        )
        return Harness(
            repository = repository,
            activeProfile = activeProfile,
            progressPreferences = progressPreferences,
            watchedPreferences = watchedPreferences
        )
    }

    private fun progress(contentId: String, position: Long, duration: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = 100L
    )

    private data class Harness(
        val repository: WatchProgressRepositoryImpl,
        val activeProfile: MutableStateFlow<Int>,
        val progressPreferences: WatchProgressPreferences,
        val watchedPreferences: WatchedItemsPreferences
    )
}
