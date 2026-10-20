package com.fetch.tv.core.parental

import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.core.sync.SetProfilePinResult
import com.fetch.tv.data.local.ProfileLockStateDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePinManagerTest {
    private class FakeRemote(
        override var isAvailable: Boolean = false,
        var remotePins: MutableMap<Int, String> = mutableMapOf()
    ) : RemoteProfilePins {
        val calls = mutableListOf<String>()

        override suspend fun pullStates(): Result<Map<Int, Boolean>> =
            Result.success(remotePins.keys.associateWith { true })

        override suspend fun setPin(profileId: Int, pin: String, currentPin: String?): SetProfilePinResult {
            calls += "set:$profileId"
            remotePins[profileId] = pin
            return SetProfilePinResult.Success
        }

        override suspend fun clearPin(profileId: Int, currentPin: String?): Result<Unit> {
            calls += "clear:$profileId"
            remotePins.remove(profileId)
            return Result.success(Unit)
        }

        override suspend fun verifyPin(profileId: Int, pin: String): Result<PinVerifyResult> {
            calls += "verify:$profileId"
            return Result.success(PinVerifyResult(unlocked = remotePins[profileId] == pin))
        }
    }

    private var now = 1_000_000L
    private val store = ProfileLockStateDataStore(TestPreferencesStore())

    private fun manager(remote: RemoteProfilePins = FakeRemote()) = ProfilePinManager(store, remote) { now }

    @Test
    fun `sets and verifies a PIN with no sync server`() = runTest {
        val remote = FakeRemote(isAvailable = false)
        val pins = manager(remote)

        assertEquals(SetProfilePinResult.Success, pins.setPin(2, "4321"))

        assertTrue(pins.hasPin(2))
        assertTrue(pins.verifyPin(2, "4321").getOrThrow().unlocked)
        assertFalse(pins.verifyPin(2, "0000").getOrThrow().unlocked)
        assertTrue(remote.calls.isEmpty())
        assertFalse(store.localPinHash(2).orEmpty().contains("4321"))
    }

    @Test
    fun `profiles without a PIN are always unlocked`() = runTest {
        assertTrue(manager().verifyPin(3, "9999").getOrThrow().unlocked)
    }

    @Test
    fun `changing a PIN needs the current one`() = runTest {
        val pins = manager()
        pins.setPin(2, "1111")

        assertEquals(SetProfilePinResult.CurrentPinRequired, pins.setPin(2, "2222"))
        assertEquals(SetProfilePinResult.CurrentPinRequired, pins.setPin(2, "2222", currentPin = "9999"))
        assertEquals(SetProfilePinResult.Success, pins.setPin(2, "2222", currentPin = "1111"))
        assertTrue(pins.verifyPin(2, "2222").getOrThrow().unlocked)
    }

    @Test
    fun `locks out after repeated wrong PINs and recovers after the delay`() = runTest {
        val pins = manager()
        pins.setPin(2, "1111")

        repeat(PinAttemptPolicy.FREE_ATTEMPTS - 1) {
            assertEquals(0, pins.verifyPin(2, "0000").getOrThrow().retryAfterSeconds)
        }
        val locked = pins.verifyPin(2, "0000").getOrThrow()
        assertFalse(locked.unlocked)
        assertEquals(30, locked.retryAfterSeconds)

        // Even the right PIN is refused while locked.
        assertFalse(pins.verifyPin(2, "1111").getOrThrow().unlocked)

        now += PinAttemptPolicy.BASE_LOCKOUT_MS
        assertTrue(pins.verifyPin(2, "1111").getOrThrow().unlocked)
        assertEquals(0, store.attemptState(2).failures)
    }

    @Test
    fun `clearing needs the PIN and removes it`() = runTest {
        val pins = manager()
        pins.setPin(2, "1111")

        assertFalse(pins.clearPin(2, "2222").getOrThrow().unlocked)
        assertTrue(pins.hasPin(2))

        assertTrue(pins.clearPin(2, "1111").getOrThrow().unlocked)
        assertFalse(pins.hasPin(2))
        assertNull(store.localPinHash(2))
    }

    @Test
    fun `mirrors changes to the sync server when signed in`() = runTest {
        val remote = FakeRemote(isAvailable = true)
        val pins = manager(remote)

        pins.setPin(2, "1111")
        pins.clearPin(2, "1111")

        assertEquals(listOf("set:2", "clear:2"), remote.calls)
    }

    @Test
    fun `adopts a PIN that only exists on the sync server`() = runTest {
        val remote = FakeRemote(isAvailable = true, remotePins = mutableMapOf(2 to "5555"))
        val pins = manager(remote)
        pins.refreshRemoteStates()

        assertTrue(pins.hasPin(2))
        assertFalse(pins.verifyPin(2, "1234").getOrThrow().unlocked)
        assertTrue(pins.verifyPin(2, "5555").getOrThrow().unlocked)

        // Now stored locally, so it works offline.
        remote.isAvailable = false
        assertTrue(pins.verifyPin(2, "5555").getOrThrow().unlocked)
        assertEquals(2, remote.calls.count { it == "verify:2" })
    }

    @Test
    fun `local PINs survive a sync refresh that reports none`() = runTest {
        val remote = FakeRemote(isAvailable = true)
        val pins = manager(remote)
        pins.setPin(2, "1111")
        remote.remotePins.clear()

        pins.refreshRemoteStates()

        assertEquals(true, pins.pinEnabled.first()[2])
    }

    @Test
    fun `removing a profile forgets its PIN`() = runTest {
        val pins = manager()
        pins.setPin(4, "1111")

        store.removeProfile(4)

        assertFalse(pins.hasPin(4))
    }
}
