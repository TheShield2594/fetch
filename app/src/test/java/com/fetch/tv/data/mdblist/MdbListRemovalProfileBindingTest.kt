package com.fetch.tv.data.mdblist

import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A Continue Watching removal never clears the MDBList account of a profile switched to meanwhile (#837). */
class MdbListRemovalProfileBindingTest {
    private val sync = mockk<MdbListSyncRepository>(relaxed = true)
    private val scrobbles = mockk<MdbListScrobbleService>(relaxed = true)

    private fun provider(): MdbListTrackingProgressProvider {
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.activeProfileId } returns MutableStateFlow(2)
        return MdbListTrackingProgressProvider(
            sync = sync,
            scrobbles = scrobbles,
            auth = mockk(relaxed = true),
            profiles = profiles,
            layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        )
    }

    @Test
    fun `a scope that now belongs to another profile clears nothing`() = runTest {
        val provider = provider()
        every { sync.currentScope() } returns MdbListAuthScope(profileId = 2, generation = 1L)

        provider.removeProgress("tt1", null, null, profileId = 1)

        coVerify(exactly = 0) { scrobbles.clear(any(), any(), any(), any()) }
    }

    @Test
    fun `the requested profile's scope is cleared`() = runTest {
        val provider = provider()
        val scope = MdbListAuthScope(profileId = 1, generation = 1L)
        every { sync.currentScope() } returns scope

        provider.removeProgress("tt1", null, null, profileId = 1)

        coVerify(exactly = 1) { scrobbles.clear(scope, "tt1", null, null) }
    }
}
