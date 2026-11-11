package com.fetch.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Shadow copies are debounced to one per store per window and written through a temp file (G5 step 1). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class ShadowCopyDebounceTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val dataStoreDir get() = File(context.filesDir, "datastore")
    private val key = stringPreferencesKey("k")

    @Before
    fun setUp() {
        dataStoreDir.deleteRecursively()
        dataStoreDir.mkdirs()
    }

    /** An in-memory store whose every write also rewrites the file the factory copies. */
    private inner class FileBackedFakeStore(private val fileName: String) : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            File(dataStoreDir, "$fileName.preferences_pb").writeText(next.asMap().toString())
            return next
        }
    }

    private fun TestScope.shadowStore(fileName: String): ShadowCopyDataStore {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ShadowCopyDataStore(
            delegate = FileBackedFakeStore(fileName),
            fileName = fileName,
            scope = backgroundScope,
            factory = ProfileDataStoreFactory(context),
            intervalMs = 30_000L,
            nowMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher
        )
    }

    private fun backup(fileName: String) = File(dataStoreDir, "$fileName.preferences_pb.bak")

    @Test
    fun `a burst of edits writes one copy at once and one at the end of the window`() = runTest {
        val store = shadowStore("watched_items_preferences")

        store.edit { it[key] = "0" }
        runCurrent()
        assertEquals("the first edit after a quiet window copies at once", 1, store.copiesWritten)

        repeat(99) { index ->
            store.edit { it[key] = "${index + 1}" }
            advanceTimeBy(100)
        }
        assertEquals("edits inside the window share one copy", 1, store.copiesWritten)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(2, store.copiesWritten)
        assertTrue(backup("watched_items_preferences").readText().contains("99"))
    }

    @Test
    fun `flush writes the waiting copy now`() = runTest {
        val store = shadowStore("library")
        store.edit { it[key] = "a" }
        runCurrent()
        store.edit { it[key] = "b" }
        runCurrent()
        assertEquals(1, store.copiesWritten)

        store.flush()

        assertEquals(2, store.copiesWritten)
        assertTrue(backup("library").readText().contains("b"))
        // Nothing left waiting, so the scheduled copy at the window's end has nothing to write.
        advanceTimeBy(31_000L)
        runCurrent()
        assertEquals(2, store.copiesWritten)
    }

    @Test
    fun `clearing the store drops the copy without waiting for the window`() = runTest {
        val store = shadowStore("trakt_auth_store")
        store.edit { it[key] = "token" }
        runCurrent()
        assertTrue(backup("trakt_auth_store").exists())

        store.edit { it.clear() }
        runCurrent()

        assertFalse(backup("trakt_auth_store").exists())
    }

    @Test
    fun `a copy leaves no temp file behind`() {
        val factory = ProfileDataStoreFactory(context)
        File(dataStoreDir, "library.preferences_pb").writeText("new")

        factory.writeShadowCopy("library", androidx.datastore.preferences.core.preferencesOf(key to "v"))

        assertEquals("new", backup("library").readText())
        assertFalse(File(dataStoreDir, "library.preferences_pb.bak.tmp").exists())
    }

    @Test
    fun `profile deletion and account reset remove interrupted temp copies`() = kotlinx.coroutines.runBlocking {
        val factory = ProfileDataStoreFactory(context)
        val profileTemp = File(dataStoreDir, "watch_progress_p3.preferences_pb.bak.tmp").apply { writeText("x") }
        val otherTemp = File(dataStoreDir, "library_p2.preferences_pb.bak.tmp").apply { writeText("x") }
        val deviceTemp = File(dataStoreDir, "screen_time.preferences_pb.bak.tmp").apply { writeText("x") }

        factory.deleteProfileFiles(3)
        assertFalse(profileTemp.exists())
        assertTrue(otherTemp.exists())

        factory.clearProfileScopedData()
        assertFalse(otherTemp.exists())
        assertTrue("device-wide stores keep their copies", deviceTemp.exists())
    }
}
