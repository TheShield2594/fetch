package com.fetch.tv.data.local

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class ProfileDataFileCleanupTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val dataStoreDir get() = File(context.filesDir, "datastore")

    @Before
    fun setUp() {
        dataStoreDir.deleteRecursively()
        dataStoreDir.mkdirs()
        File(context.filesDir, "cw_enrichment").deleteRecursively()
    }

    private fun file(name: String, content: String = "data"): File =
        File(dataStoreDir, name).apply { writeText(content) }

    @Test
    fun `shadow copy is dropped when the store is cleared`() {
        val factory = ProfileDataStoreFactory(context)
        file("trakt_auth_store.preferences_pb", "")
        val backup = file("trakt_auth_store.preferences_pb.bak", "old tokens")

        factory.writeShadowCopy("trakt_auth_store", emptyPreferences())

        assertFalse(backup.exists())
    }

    @Test
    fun `shadow copy follows the store while it has data`() {
        val factory = ProfileDataStoreFactory(context)
        file("library.preferences_pb", "new")
        val backup = file("library.preferences_pb.bak", "old")

        factory.writeShadowCopy("library", preferencesOf(stringPreferencesKey("k") to "v"))

        assertEquals("new", backup.readText())
    }

    @Test
    fun `deleting a profile's files removes its shadow copies and nothing else`() {
        val factory = ProfileDataStoreFactory(context)
        val primary = file("watch_progress_p3.preferences_pb")
        val backup = file("watch_progress_p3.preferences_pb.bak")
        val otherProfile = file("watch_progress_p2.preferences_pb.bak")
        val mainProfile = file("watch_progress.preferences_pb.bak")

        factory.deleteProfileFiles(3)

        assertFalse(primary.exists())
        assertFalse(backup.exists())
        assertTrue(otherProfile.exists())
        assertTrue(mainProfile.exists())
    }

    @Test
    fun `account reset removes profile-scoped shadow copies but keeps device stores`() = runBlocking {
        val factory = ProfileDataStoreFactory(context)
        val primary = file("watch_progress_p2.preferences_pb")
        val backup = file("watch_progress_p2.preferences_pb.bak")
        val mainBackup = file("trakt_auth_store.preferences_pb.bak")
        val retained = file("auth_session_notice_store.preferences_pb")

        factory.clearProfileScopedData()

        assertFalse(primary.exists())
        assertFalse(backup.exists())
        assertFalse(mainBackup.exists())
        assertTrue(retained.exists())
    }

    @Test
    fun `account reset reports a file it could not delete instead of claiming success`() = runBlocking {
        val factory = ProfileDataStoreFactory(context)
        val deletable = file("watch_progress_p2.preferences_pb")
        // A non-empty directory under a store's name can't be removed with File.delete().
        val stuck = File(dataStoreDir, "library_p2.preferences_pb").apply {
            mkdirs()
            File(this, "child").writeText("x")
        }

        val failure = runCatching { factory.clearProfileScopedData() }.exceptionOrNull()

        assertTrue("expected the reset to report failure", failure is java.io.IOException)
        assertTrue(failure!!.message!!.contains("library_p2.preferences_pb"))
        assertFalse(deletable.exists())
        assertTrue(stuck.exists())
    }

    @Test
    fun `deleting a profile's continue watching snapshots leaves other profiles alone`() = runBlocking {
        val dir = File(context.filesDir, "cw_enrichment").apply { mkdirs() }
        val deleted = listOf("inprogress_3.json", "nextup_3.json", "nextup_3.json.tmp")
            .map { File(dir, it).apply { writeText("[]") } }
        val kept = listOf("inprogress_2.json", "nextup_1.json")
            .map { File(dir, it).apply { writeText("[]") } }

        ContinueWatchingEnrichmentCache.deleteProfileFiles(context.filesDir, 3)

        deleted.forEach { assertFalse(it.name, it.exists()) }
        kept.forEach { assertTrue(it.name, it.exists()) }
    }
}
