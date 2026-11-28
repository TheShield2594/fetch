package com.fetch.tv.data.website

import com.fetch.tv.data.local.LayoutPreferenceDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A Continue Watching removal never touches the website profile of a TV profile switched to meanwhile (#837). */
class FetchWebsiteRemovalProfileBindingTest {
    private val repository = mockk<FetchWebsiteTrackingRepository>(relaxed = true)
    private val client = mockk<FetchWebsiteClient>(relaxed = true)

    private fun provider() = FetchWebsiteTrackingProgressProvider(
        repository = repository,
        client = client,
        layoutPreferences = mockk<LayoutPreferenceDataStore>(relaxed = true)
    )

    @Test
    fun `a switch while the snapshot loads ends the removal`() = runTest {
        val provider = provider()
        var slot = 1
        every { repository.activeSlot() } answers { slot }
        coEvery { repository.ensureLoaded() } coAnswers { slot = 2 }

        provider.removeProgress("tt1", null, null, profileId = 1)

        verify(exactly = 0) { repository.currentSnapshot() }
        coVerify(exactly = 0) { repository.asProfile<Any?>(any(), any()) }
        verify(exactly = 0) { repository.mutateFor(any(), any()) }
    }

    @Test
    fun `a removal for a profile that is no longer active does nothing`() = runTest {
        val provider = provider()
        every { repository.activeSlot() } returns 2

        provider.removeProgress("tt1", null, null, profileId = 1)

        coVerify(exactly = 0) { repository.ensureLoaded() }
        verify(exactly = 0) { repository.mutateFor(any(), any()) }
    }

    @Test
    fun `the website delete still goes out after the optimistic removal emptied the snapshot`() = runTest {
        val provider = provider()
        var snapshot = FetchWebsiteTrackingSnapshot(
            slot = 1,
            websiteProfileId = "wp",
            playback = listOf(WebsitePlayback(id = "p1", type = "movie", imdbId = "tt1", pausedAt = "2026-01-01T00:00:00Z"))
        )
        every { repository.activeSlot() } returns 1
        every { repository.currentSnapshot() } answers { snapshot }
        every { repository.mutateFor(any(), any()) } answers {
            if (firstArg<Int>() == snapshot.slot) snapshot = secondArg<(FetchWebsiteTrackingSnapshot) -> FetchWebsiteTrackingSnapshot>()(snapshot)
        }
        coEvery { repository.asProfile<Any?>(1, any()) } coAnswers {
            secondArg<suspend (String, String?) -> Any?>()("wp", null)
        }

        provider.applyOptimisticRemoval("tt1", null, null)
        provider.removeProgress("tt1", null, null, profileId = 1)

        coVerify(exactly = 1) { client.deletePlayback("wp", null, "p1") }
        org.junit.Assert.assertTrue(snapshot.playback.isEmpty())
    }
}
