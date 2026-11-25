package com.fetch.tv.core.profile

import com.fetch.tv.core.parental.PinVerifyResult
import com.fetch.tv.core.parental.ProfilePinManager
import com.fetch.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** PIN-protected profiles are in a backup once their PIN, or the main profile's, is given (#671). */
class TvBackupUnlockTest {
    private val main = UserProfile(id = 1, name = "Alex", avatarColorHex = "#1E88E5")
    private val sam = UserProfile(id = 2, name = "Sam", avatarColorHex = "#1E88E5")
    private val kids = UserProfile(id = 3, name = "Kids", avatarColorHex = "#1E88E5")

    private val profileManager = mockk<ProfileManager> {
        every { profiles } returns MutableStateFlow(listOf(main, sam, kids))
    }
    private val pins = mockk<ProfilePinManager>()
    private val transfer = mockk<ProfileTransfer>()

    private fun backup(vararg open: UserProfile): TvBackup {
        coEvery { transfer.exportableProfiles() } returns open.toList()
        return TvBackup(profileManager, pins, transfer, mockk(relaxed = true), mockk(relaxed = true))
    }

    private fun pin(profileId: Int, pin: String, unlocked: Boolean, retryAfter: Int = 0) {
        coEvery { pins.verifyPin(profileId, pin) } returns Result.success(PinVerifyResult(unlocked, retryAfter))
    }

    @Test
    fun `each locked profile opens with its own PIN`() = runBlocking {
        val tvBackup = backup(main)
        pin(2, "1111", unlocked = true)
        pin(3, "2222", unlocked = false)
        val result = tvBackup.unlock(mapOf(2 to "1111", 3 to "2222"))
        assertEquals(setOf(2), result.unlocked)
        assertEquals(listOf("Kids"), result.wrong)
    }

    @Test
    fun `the main profile's PIN opens every locked profile`() = runBlocking {
        val tvBackup = backup()
        pin(1, "9999", unlocked = true)
        val result = tvBackup.unlock(mapOf(1 to "9999"))
        assertEquals(setOf(1, 2, 3), result.unlocked)
        assertEquals(emptyList<String>(), result.wrong)
    }

    @Test
    fun `blank PINs are skipped and nothing is checked when nothing is locked`() = runBlocking {
        assertEquals(TvBackupUnlock(emptySet()), backup(main, sam, kids).unlock(mapOf(2 to "1234")))
        val tvBackup = backup(main)
        assertEquals(TvBackupUnlock(emptySet()), tvBackup.unlock(mapOf(2 to "  ")))
        coVerify(exactly = 0) { pins.verifyPin(any(), any()) }
    }

    @Test
    fun `a lockout is reported`() = runBlocking {
        val tvBackup = backup(main)
        pin(2, "0000", unlocked = false, retryAfter = 30)
        assertEquals(TvBackupUnlock(emptySet(), listOf("Sam"), 30), tvBackup.unlock(mapOf(2 to "0000")))
    }
}
