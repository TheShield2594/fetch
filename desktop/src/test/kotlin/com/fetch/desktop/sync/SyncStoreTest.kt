package com.fetch.desktop.sync

import com.fetch.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class SyncStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `missing or corrupt file loads as signed out`() {
        val file = File(tmp.root, "sync.json")
        assertEquals(SyncState(), SyncStore(file).load())
        file.writeText("not json")
        assertEquals(SyncState(), SyncStore(file).load())
    }

    @Test
    fun `round trip keeps server, session and origin id`() {
        val file = File(tmp.root, "nested/sync.json")
        val state = SyncState(testServer, testSession, TEST_ORIGIN)
        SyncStore(file).save(state)
        assertEquals(state, SyncStore(file).load())
        assertFalse(File(file.parentFile, "sync.json.tmp").exists())

        SyncStore(file).update { it.copy(session = null) }
        assertEquals(SyncState(testServer, null, TEST_ORIGIN), SyncStore(file).load())
    }

    @Test
    fun `file is readable by its owner only`() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val file = File(tmp.root, "sync.json")
        SyncStore(file).save(SyncState(testServer, testSession))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
    }

    @Test
    fun `origin id is created once and kept`() {
        val store = SyncStore(File(tmp.root, "sync.json"))
        val first = store.originClientId()
        assertEquals(first, store.originClientId())
        assertEquals(first, store.load().originClientId)
    }

    @Test
    fun `library items map to the shared model and back`() {
        val remote = RemoteLibraryItem("tt1", "movie", "One", "p", "LANDSCAPE", "b", "d", "2020", 7f, listOf("A"), "https://a", 5)
        val saved = remote.toSavedLibraryItem()
        assertEquals(PosterShape.LANDSCAPE, saved.posterShape)
        assertEquals(remote, saved.toRemoteLibraryItem())
    }
}
