package com.fetch.tv.data.simkl

import com.fetch.tv.data.local.LayoutPreferenceDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A Continue Watching removal never reaches the Simkl account of a profile switched to meanwhile (#837). */
class SimklRemovalProfileBindingTest {
    private val syncRepository = mockk<SimklSyncRepository>(relaxed = true)
    private val apiClient = mockk<SimklApiClient>(relaxed = true)
    private val authStorage = mockk<SimklAuthStorage>(relaxed = true)

    private fun provider(): SimklTrackingProgressProvider {
        every { syncRepository.state } returns MutableStateFlow(SimklSyncState())
        return SimklTrackingProgressProvider(
            syncRepository = syncRepository,
            apiClient = apiClient,
            authStorage = authStorage,
            layoutPreferences = mockk<LayoutPreferenceDataStore>(relaxed = true)
        )
    }

    @Test
    fun `a switch while the sessions load ends the removal`() = runTest {
        val provider = provider()
        var scope = SimklAuthScope(profileId = 1, generation = 1L)
        every { authStorage.currentScope() } answers { scope }
        coEvery { syncRepository.ensureLoaded() } coAnswers { scope = SimklAuthScope(profileId = 2, generation = 2L) }

        provider.removeProgress("tt1", null, null, profileId = 1)

        coVerify(exactly = 0) { apiClient.execute(any(), any()) }
        coVerify(exactly = 0) { syncRepository.removePlaybackSessions(any(), any()) }
    }

    @Test
    fun `a removal for a profile that is no longer active does nothing`() = runTest {
        val provider = provider()
        every { authStorage.currentScope() } returns SimklAuthScope(profileId = 2, generation = 1L)

        provider.removeProgress("tt1", null, null, profileId = 1)

        coVerify(exactly = 0) { syncRepository.ensureLoaded() }
        coVerify(exactly = 0) { apiClient.execute(any(), any()) }
    }
}
