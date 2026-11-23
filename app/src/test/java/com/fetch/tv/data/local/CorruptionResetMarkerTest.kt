package com.fetch.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
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

/**
 * A synced store reset to empty after corruption must stay marked until sync refills it, across
 * restarts, so its emptiness is never pushed as "every key removed" (#615).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class CorruptionResetMarkerTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val dataStoreDir get() = File(context.filesDir, "datastore")
    private val key = stringPreferencesKey("subtitle_language")

    @Before
    fun setUp() {
        dataStoreDir.deleteRecursively()
        dataStoreDir.mkdirs()
    }

    private fun corrupt(fileName: String) {
        File(dataStoreDir, "$fileName.preferences_pb").writeBytes(byteArrayOf(-1, -1, -1, -1, 0x0a, -1))
    }

    @Test
    fun `a store reset after corruption is marked, and the mark survives a restart`() = runBlocking {
        corrupt("player_settings_p2")
        val factory = ProfileDataStoreFactory(context)

        val prefs = factory.get(2, "player_settings").data.first()

        assertTrue(prefs.asMap().isEmpty())
        assertTrue(factory.wasResetAfterCorruption(2, "player_settings"))
        assertFalse(factory.wasResetAfterCorruption(1, "player_settings"))
        assertFalse(factory.wasResetAfterCorruption(2, "layout_settings"))

        // A new process sees a valid empty file; only the marker tells it apart from a real "no settings".
        val afterRestart = ProfileDataStoreFactory(context)
        assertTrue(afterRestart.wasResetAfterCorruption(2, "player_settings"))
    }

    @Test
    fun `clearing the reset forgets it for good`() = runBlocking {
        corrupt("player_settings")
        val factory = ProfileDataStoreFactory(context)
        factory.get(1, "player_settings").data.first()

        factory.clearCorruptionReset(1, "player_settings")

        assertFalse(factory.wasResetAfterCorruption(1, "player_settings"))
        assertFalse(ProfileDataStoreFactory(context).wasResetAfterCorruption(1, "player_settings"))
        assertFalse(File(dataStoreDir, "player_settings.preferences_pb.reset").exists())
    }

    @Test
    fun `a store recovered from its shadow copy is not marked`() = runBlocking {
        val factory = ProfileDataStoreFactory(context)
        // Build a valid store file elsewhere and use its bytes as profile 3's shadow copy.
        factory.get(6, "player_settings").edit { it[key] = "en" }
        File(dataStoreDir, "player_settings_p6.preferences_pb")
            .copyTo(File(dataStoreDir, "player_settings_p3.preferences_pb.bak"), overwrite = true)
        corrupt("player_settings_p3")

        val prefs = factory.get(3, "player_settings").data.first()

        assertEquals("en", prefs[key])
        assertFalse(factory.wasResetAfterCorruption(3, "player_settings"))
    }

    @Test
    fun `deleting a profile's files removes its reset marker`() = runBlocking {
        corrupt("player_settings_p4")
        val factory = ProfileDataStoreFactory(context)
        factory.get(4, "player_settings").data.first()
        factory.clearProfile(4)

        factory.deleteProfileFiles(4)

        assertFalse(File(dataStoreDir, "player_settings_p4.preferences_pb.reset").exists())
    }
}
