package com.fetch.tv.data.repository

import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.core.tracking.TrackingHistoryWriterRegistry
import com.fetch.tv.core.tracking.TrackingProgressProviderRegistry
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

    private fun harness(activeProfileId: Int): Harness {
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
            trackingProgressProviders = TrackingProgressProviderRegistry(emptySet()),
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
