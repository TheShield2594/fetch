package com.fetch.desktop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class PrivateFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun perms(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun `files are written owner-only in owner-only folders, without leftovers`() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val file = File(temp.root, "fetch/profiles/a/secrets.json")
        PrivateFiles.writeText(file, "one")
        PrivateFiles.writeText(file, "two")
        assertEquals("two", file.readText())
        assertEquals("rw-------", perms(file))
        assertEquals("rwx------", perms(file.parentFile))
        assertEquals(listOf("secrets.json"), file.parentFile.list()!!.toList())
    }

    @Test
    fun `json stores and the addon list go through it`() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val addons = File(temp.root, "fetch/addons.json")
        AddonStore(addons).save(listOf("https://addon.example/key=secret"))
        assertEquals("rw-------", perms(addons))
        val window = File(temp.root, "fetch/window.json")
        JsonFile(window, SavedWindow.serializer()) { SavedWindow() }.save(SavedWindow(maximized = true))
        assertEquals("rw-------", perms(window))
        assertTrue(savedWindowFile(File(temp.root, "fetch")).load().maximized)
    }

    @Test
    fun `an existing folder is tightened on request`() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val dir = temp.newFolder("cache")
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        PrivateFiles.ensureDir(dir, restrictExisting = true)
        assertEquals("rwx------", perms(dir))
    }

    @Test
    fun `the window opens at its saved size, clamped to the screen and the minimum`() {
        assertEquals(1280f to 800f, SavedWindow().sizeFor(null, null))
        assertEquals(1024f to 700f, SavedWindow(width = 1600f, height = 1000f).sizeFor(1024f, 700f))
        assertEquals(960f to 600f, SavedWindow(width = 400f, height = 300f).sizeFor(1920f, 1080f))
        assertEquals(800f to 500f, SavedWindow(width = 400f, height = 300f).sizeFor(800f, 500f))
    }

    @Test
    fun `the saved place is used only while it is on a screen`() {
        val screen = listOf(floatArrayOf(0f, 0f, 1920f, 1080f))
        val size = 1280f to 800f
        assertEquals(100f to 50f, SavedWindow(x = 100f, y = 50f).positionOn(screen, size))
        assertNull("monitor gone", SavedWindow(x = 2500f, y = 50f).positionOn(screen, size))
        assertNull("never placed", SavedWindow().positionOn(screen, size))
    }
}
