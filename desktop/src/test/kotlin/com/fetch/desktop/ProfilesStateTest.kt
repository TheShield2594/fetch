package com.fetch.desktop

import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.ProfilesFile
import com.fetch.desktop.data.addonsDir
import com.fetch.desktop.data.profileDir
import com.fetch.desktop.data.profilesFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfilesStateTest {

    private val me = DesktopProfile(1, "Me", "#E53935")
    private val kid = DesktopProfile(2, "Kid", "#1E88E5", usesPrimaryAddons = false)

    @Test
    fun `one profile never asks`() {
        assertEquals(me, ProfilesState.initialProfile(ProfilesFile(listOf(me), rememberLastProfile = false)))
    }

    @Test
    fun `several profiles open the last one, or ask when remembering is off`() {
        assertEquals(kid, ProfilesState.initialProfile(ProfilesFile(listOf(me, kid), lastProfileId = 2)))
        assertNull(ProfilesState.initialProfile(ProfilesFile(listOf(me, kid), lastProfileId = 2, rememberLastProfile = false)))
    }

    @Test
    fun `the main profile keeps the old files, others get their own folder`() {
        val root = File("/cfg")
        assertEquals(root, profileDir(me, root))
        assertEquals(File(root, "profiles/2"), profileDir(kid, root))
        assertEquals(File(root, "profiles/2"), addonsDir(kid, root))
        assertEquals(root, addonsDir(kid.copy(usesPrimaryAddons = true), root))
    }

    @Test
    fun `deleting a profile removes its files but never the main profile`() {
        val root = Files.createTempDirectory("profiles").toFile()
        val state = ProfilesState(JsonFile(File(root, "profiles.json"), ProfilesFile.serializer()) { ProfilesFile() }, root)
        val added = state.add("Kid")!!
        File(profileDir(added, root), "library.json").apply { parentFile.mkdirs(); writeText("{}") }
        state.delete(added)
        assertFalse(profileDir(added, root).exists())
        state.delete(state.profiles.first())
        assertEquals(1, state.profiles.size)
        assertTrue(File(root, "profiles.json").exists())
    }

    @Test
    fun `six profiles at most, as the sync server allows, reusing freed ids`() {
        val root = Files.createTempDirectory("profiles").toFile()
        val state = ProfilesState(JsonFile(File(root, "profiles.json"), ProfilesFile.serializer()) { ProfilesFile() }, root)
        val added = (1..5).map { state.add("P$it")!! }
        assertEquals(listOf(2, 3, 4, 5, 6), added.map { it.id })
        assertNull("a seventh is refused", state.add("Too many"))
        state.delete(added[1])
        assertEquals(3, state.add("Again")!!.id)
    }

    @Test
    fun `profiles without limits ask for the main PIN only while the lock is on and it applies`() {
        val main = DesktopProfile(1, "Parent", "#111111", pinHash = "hash")
        val kid = DesktopProfile(2, "Kid", "#222222", limits = com.fetch.desktop.data.KidsLimits)
        val guest = DesktopProfile(3, "Guest", "#333333")
        val off = ProfilesFile(listOf(main, kid, guest))
        assertFalse(off.needsParentPinToOpen(guest))

        val on = off.copy(lockUnrestrictedProfiles = true)
        assertTrue(on.needsParentPinToOpen(guest))
        assertFalse("restricted profiles open as before", on.needsParentPinToOpen(kid))
        assertFalse("the main profile never does", on.needsParentPinToOpen(main))
        assertFalse("not without a main PIN", on.copy(profiles = listOf(main.copy(pinHash = null), kid, guest)).needsParentPinToOpen(guest))
        assertFalse("not while no profile has limits", on.copy(profiles = listOf(main, guest)).needsParentPinToOpen(guest))
        assertEquals(listOf(guest), on.unlockedUnrestricted(except = kid))
    }

    @Test
    fun `a profile behind the main PIN isn't opened at start-up`() {
        val file = ProfilesFile(
            listOf(
                DesktopProfile(1, "Parent", "#111111", pinHash = "hash"),
                DesktopProfile(2, "Kid", "#222222", limits = com.fetch.desktop.data.KidsLimits),
                DesktopProfile(3, "Guest", "#333333")
            ),
            lastProfileId = 3,
            lockUnrestrictedProfiles = true
        )
        assertNull(ProfilesState.initialProfile(file))
        assertEquals(3, ProfilesState.initialProfile(file.copy(lockUnrestrictedProfiles = false))?.id)
    }

    @Test
    fun `limits owed after a settings copy are kept until they land, and replaced by new limits`() {
        val dir = Files.createTempDirectory("profiles").toFile()
        val state = ProfilesState(profilesFile(dir), dir)
        val kid = state.add("Kid")!!
        val owed = com.fetch.desktop.data.KidsLimits
        state.setPendingLimits(kid.id, owed)
        assertEquals(owed, ProfilesState(profilesFile(dir), dir).file.pendingLimitsPush[kid.id])

        state.setLimits(kid, com.fetch.desktop.data.ProfileLimits(dailyMinutes = 60))
        assertTrue("new limits supersede the owed ones", state.file.pendingLimitsPush.isEmpty())
    }
}
