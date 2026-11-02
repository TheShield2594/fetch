package com.fetch.desktop

import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.ProfilesFile
import com.fetch.desktop.data.addonsDir
import com.fetch.desktop.data.profileDir
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
        val added = state.add("Kid")
        File(profileDir(added, root), "library.json").apply { parentFile.mkdirs(); writeText("{}") }
        state.delete(added)
        assertFalse(profileDir(added, root).exists())
        state.delete(state.profiles.first())
        assertEquals(1, state.profiles.size)
        assertTrue(File(root, "profiles.json").exists())
    }
}
