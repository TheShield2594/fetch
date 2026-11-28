package com.fetch.tv.data.repository

import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.core.tracking.TrackingHistoryWriter
import com.fetch.tv.core.tracking.TrackingHistoryWriterRegistry
import com.fetch.tv.core.tracking.TrackingMutationResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confirmed writes the calendar uses: they wait for every connected tracker, say whether all
 * of them took the change, and put this TV's own record back when one didn't.
 */
class WatchProgressRepositoryConfirmedWritesTest {
    @Test
    fun `a completion every tracker takes is confirmed and kept`() = runTest {
        val harness = harness(writerFails = false)

        val saved = harness.repository.markAsCompletedConfirmed(episode())

        assertTrue(saved)
        coVerify(exactly = 1) { harness.writer.addToHistory(1, any()) }
        coVerify(exactly = 0) { harness.watchedPreferences.unmarkAsWatched(any(), any(), any(), profileId = any()) }
    }

    @Test
    fun `a completion a tracker rejects is reported and taken back on this TV`() = runTest {
        val harness = harness(writerFails = true)

        val saved = harness.repository.markAsCompletedConfirmed(episode())

        assertFalse(saved)
        coVerify(exactly = 1) { harness.watchedPreferences.unmarkAsWatched("tt1", 2, 5, profileId = 1) }
        coVerify(exactly = 1) { harness.progressPreferences.removeProgress("tt1", 2, 5, 1) }
    }

    @Test
    fun `a removal a tracker rejects is reported and marked watched again on this TV`() = runTest {
        val harness = harness(writerFails = true)

        val removed = harness.repository.removeFromHistoryConfirmed("tt1", "tt1:2:5", 2, 5)

        assertFalse(removed)
        coVerify(exactly = 1) {
            harness.watchedPreferences.markAsWatched(
                match { it.contentId == "tt1" && it.season == 2 && it.episode == 5 },
                profileId = 1
            )
        }
    }

    @Test
    fun `a removal every tracker takes is confirmed`() = runTest {
        val harness = harness(writerFails = false)

        assertTrue(harness.repository.removeFromHistoryConfirmed("tt1", "tt1:2:5", 2, 5))
        coVerify(exactly = 0) { harness.watchedPreferences.markAsWatched(any(), profileId = any()) }
    }

    @Test
    fun `the unconfirmed completion still keeps the mark when a tracker rejects it`() = runTest {
        val harness = harness(writerFails = true)

        harness.repository.markAsCompleted(episode())

        coVerify(exactly = 0) { harness.watchedPreferences.unmarkAsWatched(any(), any(), any(), profileId = any()) }
    }

    private fun harness(writerFails: Boolean): Harness {
        val providerId = TrackingProviderId.entries.first()
        val provider = mockk<TrackingProgressProvider>(relaxed = true)
        every { provider.providerId } returns providerId
        every { provider.isAuthenticated } returns flowOf(true)

        val writer = mockk<TrackingHistoryWriter>()
        every { writer.providerId } returns providerId
        if (writerFails) {
            coEvery { writer.addToHistory(any(), any()) } throws IllegalStateException("rejected")
            coEvery { writer.removeFromHistory(any(), any()) } throws IllegalStateException("rejected")
        } else {
            coEvery { writer.addToHistory(any(), any()) } returns TrackingMutationResult(1)
            coEvery { writer.removeFromHistory(any(), any()) } returns TrackingMutationResult(1)
        }

        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
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
            trackingProgressProviders = TrackingProgressProviderRegistry(setOf(provider)),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(setOf(writer)),
            serverWatched = mockk(relaxed = true)
        )
        return Harness(repository, writer, progressPreferences, watchedPreferences)
    }

    private fun episode() = WatchProgress(
        contentId = "tt1",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1:2:5",
        season = 2,
        episode = 5,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = 100L
    )

    private data class Harness(
        val repository: WatchProgressRepositoryImpl,
        val writer: TrackingHistoryWriter,
        val progressPreferences: WatchProgressPreferences,
        val watchedPreferences: WatchedItemsPreferences
    )
}
