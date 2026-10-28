package com.fetch.tv.ui.screens.profile

import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.parental.PinVerifyResult
import com.fetch.tv.core.parental.ProfilePinManager
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParentalGateViewModelTest {

    private val parent = UserProfile(id = 1, name = "Parent", avatarColorHex = "#FFFFFF")
    private val kid = UserProfile(id = 2, name = "Kid", avatarColorHex = "#000000")

    private var unlocked = false
    private var locks = 0
    private val profiles = MutableStateFlow(listOf(parent, kid))

    private val parentalControls = mockk<ParentalControls> {
        every { guardPinProfileId() } returns 1
        every { parentPinProfileId() } returns 1
        every { isGateUnlocked(any()) } answers { unlocked }
        every { markGateUnlocked(any()) } answers { unlocked = true }
        every { lockGate() } answers {
            if (unlocked) locks++
            unlocked = false
        }
        every { gateLocks } returns MutableStateFlow(0)
    }
    private val pinManager = mockk<ProfilePinManager> {
        coEvery { verifyPin(1, "1234") } returns Result.success(PinVerifyResult(unlocked = true))
    }
    private val profileManager = mockk<ProfileManager> {
        every { this@mockk.profiles } returns this@ParentalGateViewModelTest.profiles
        every { activeProfileId } returns MutableStateFlow(2)
    }

    private fun TestScope.viewModel(): ParentalGateViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ParentalGateViewModel(parentalControls, pinManager, profileManager).also { runCurrent() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stays locked while the parent profile is missing, then cancels`() = runTest {
        profiles.value = listOf(kid)
        val gate = viewModel()
        var ran = false
        var cancelled = false

        gate.runGuarded(onCancel = { cancelled = true }) { ran = true }
        advanceTimeBy(10_000)
        runCurrent()

        assertFalse(ran)
        assertTrue(cancelled)
        assertNull(gate.request.value)
    }

    @Test
    fun `asks for the PIN once a late profile list has the parent`() = runTest {
        profiles.value = emptyList()
        val gate = viewModel()
        var ran = false

        gate.runGuarded { ran = true }
        runCurrent()
        assertNull(gate.request.value)

        profiles.value = listOf(parent, kid)
        runCurrent()

        assertFalse(ran)
        assertEquals(parent, gate.request.value?.parentProfile)
    }

    @Test
    fun `a one-off action locks the gate again once it has run`() = runTest {
        val gate = viewModel()
        var ran = false

        gate.runParentGuarded { ran = true }
        gate.submit("1234")
        runCurrent()

        assertTrue(ran)
        assertFalse(unlocked)
    }

    @Test
    fun `the gate stays open across guarded screens and locks when the last one closes`() = runTest {
        val gate = viewModel()
        gate.openGuardedScreen("settings")
        gate.runGuarded {}
        gate.submit("1234")
        runCurrent()
        assertTrue(unlocked)

        gate.openGuardedScreen("addons")
        gate.closeGuardedScreen("addons")
        assertTrue(unlocked)

        var ranWithoutPin = false
        gate.runGuarded { ranWithoutPin = true }
        assertTrue(ranWithoutPin)

        gate.closeGuardedScreen("settings")
        assertFalse(unlocked)
        assertEquals(1, locks)

        gate.runGuarded {}
        assertNotNull(gate.request.value)
    }
}
