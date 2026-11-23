package com.fetch.tv.data.local

import android.content.Context
import com.squareup.moshi.Moshi
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** A corrupted profile list must not drop every profile and open the main one (#618). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class ProfileListRecoveryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `a corrupted profile list comes back from its backup`() = runBlocking {
        val dataStoreDir = File(context.filesDir, "datastore").apply { mkdirs() }
        File(dataStoreDir, "profile_settings.preferences_pb").writeBytes(byteArrayOf(-1, -1, -1, -1, 0x0a, -1))
        context.getSharedPreferences("profile_settings_backup", Context.MODE_PRIVATE).edit()
            .putString(
                "profiles_json",
                """[{"id":1,"name":"Parent","avatarColorHex":"#1E88E5"},{"id":3,"name":"Kids","avatarColorHex":"#43A047"}]"""
            )
            .putInt("active_profile_id", 3)
            .putBoolean("profile_has_ever_selected", true)
            .commit()

        val store = ProfileDataStore(context, Moshi.Builder().build())

        assertEquals(listOf(1 to "Parent", 3 to "Kids"), store.profilesList.first().map { it.id to it.name })
        assertEquals(3, store.activeProfileId.first())
        assertTrue(store.hasEverSelectedProfile.first())
    }

    @Test
    fun `without a backup the slots on disk are kept`() {
        val dir = File(context.filesDir, "slots-test").apply { deleteRecursively(); mkdirs() }
        listOf(
            "watch_progress_p3.preferences_pb",
            "player_settings_p3.preferences_pb",
            "player_settings_p5.preferences_pb.bak",
            "player_settings_p2.preferences_pb",
            "player_settings.preferences_pb",
            "profile_settings.preferences_pb"
        ).forEach { File(dir, it).writeText("") }

        // The .bak alone doesn't count: a profile whose only trace is a shadow copy was deleted.
        assertEquals(listOf(2, 3), profileSlotsOnDisk(dir))
    }
}
