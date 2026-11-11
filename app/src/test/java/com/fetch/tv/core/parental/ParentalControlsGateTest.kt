package com.fetch.tv.core.parental

import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.LocalListsDataStore
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentalControlsGateTest {

    private val activeProfileId = MutableStateFlow(2)

    private fun controls(): ParentalControls {
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns this@ParentalControlsGateTest.activeProfileId
        }
        val restrictionsDataStore = mockk<ProfileRestrictionsDataStore> {
            every { restrictions } returns emptyFlow()
            every { titleLists } returns emptyFlow()
            every { kidsRules } returns emptyFlow()
            every { lockUnrestrictedProfiles } returns emptyFlow()
        }
        val pinManager = mockk<ProfilePinManager> {
            every { pinEnabled } returns emptyFlow()
        }
        val resolver = mockk<CertificationResolver> {
            every { revision } returns MutableStateFlow(0L)
        }
        val localLists = mockk<LocalListsDataStore> {
            every { listsFor(any()) } returns emptyFlow()
        }
        return ParentalControls(
            profileManager = profileManager,
            restrictionsDataStore = restrictionsDataStore,
            pinManager = pinManager,
            resolver = resolver,
            websiteSync = mockk(relaxed = true),
            localLists = localLists
        )
    }

    @Test
    fun `an unlock times out after the window when no guarded screen is open`() {
        val controls = controls()
        controls.markGateUnlocked(nowMs = 0L)

        assertTrue(controls.isGateUnlocked(nowMs = 60_000L))
        assertFalse(controls.isGateUnlocked(nowMs = 10 * 60_000L))
    }

    @Test
    fun `an unlock stays open past the window while a guarded screen is open`() {
        val controls = controls()
        controls.markGateUnlocked(nowMs = 0L)
        controls.holdGateOpen(true)

        assertTrue(controls.isGateUnlocked(nowMs = 60 * 60_000L))

        controls.holdGateOpen(false)
        assertFalse(controls.isGateUnlocked(nowMs = 60 * 60_000L))
    }

    @Test
    fun `holding the gate open never unlocks a locked gate`() {
        val controls = controls()
        controls.holdGateOpen(true)
        assertFalse(controls.isGateUnlocked(nowMs = 0L))

        controls.markGateUnlocked(nowMs = 0L)
        controls.lockGate()
        assertFalse(controls.isGateUnlocked(nowMs = 0L))
    }

    @Test
    fun `a held unlock doesn't carry over to another profile`() {
        val controls = controls()
        controls.markGateUnlocked(nowMs = 0L)
        controls.holdGateOpen(true)

        activeProfileId.value = 3

        assertFalse(controls.isGateUnlocked(nowMs = 0L))
    }
}
