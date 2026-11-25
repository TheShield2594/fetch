package com.fetch.tv.core.profile

import android.content.Context
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.local.ProfileDataStoreFactory
import com.fetch.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A deleted profile's slot is only given to a new profile once all of its old data is gone. A
 * cleanup cut short is remembered on disk and finished later, through the real deletion order:
 * the stores below [ProfileManager] are mocked, ProfileManager itself is not.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class ProfileCleanupRetryTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("profile_cleanup_retry_test", Context.MODE_PRIVATE)

    private val list = MutableStateFlow(listOf(profile(1), profile(2), profile(3)))
    private val profileDataStore = mockk<ProfileDataStore>(relaxed = true)
    private val factory = mockk<ProfileDataStoreFactory>(relaxed = true)
    private val profileDatabaseData = mockk<com.fetch.tv.data.local.db.ProfileDatabaseData>(relaxed = true)
    private lateinit var pendingCleanup: PendingProfileCleanup

    private fun profile(id: Int) = UserProfile(id = id, name = "Profile $id", avatarColorHex = "#1E88E5")

    private fun manager() = ProfileManager(
        profileDataStore = profileDataStore,
        factory = factory,
        credentialStores = emptySet(),
        profileLockStateDataStore = mockk(relaxed = true),
        profileRestrictionsDataStore = mockk(relaxed = true),
        screenTimeStore = mockk(relaxed = true),
        profileDatabaseData = profileDatabaseData,
        pendingPushes = mockk(relaxed = true),
        pendingCleanup = pendingCleanup,
        context = context
    )

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        pendingCleanup = PendingProfileCleanup { prefs }
        every { profileDataStore.profilesList } returns list
        every { profileDataStore.activeProfileId } returns MutableStateFlow(1)
        every { profileDataStore.hasEverSelectedProfile } returns MutableStateFlow(true)
        every { profileDataStore.rememberLastProfileEnabled } returns MutableStateFlow(false)
        every { profileDataStore.confirmExitEnabled } returns MutableStateFlow(false)
        every { profileDataStore.startupSplashEnabled } returns MutableStateFlow(true)
        coEvery { profileDataStore.deleteProfile(any()) } answers {
            val id = firstArg<Int>()
            list.value = list.value.filter { it.id != id }
        }
        coEvery { profileDataStore.upsertProfile(any()) } answers {
            val profile = firstArg<UserProfile>()
            list.value = list.value.filter { it.id != profile.id } + profile
        }
        every { factory.deleteProfileFiles(any()) } returns true
    }

    @Test
    fun `a full cleanup leaves nothing pending and the slot free`() = runBlocking {
        val profiles = manager()

        assertTrue(profiles.deleteProfile(3))

        assertEquals(emptySet<Int>(), pendingCleanup.slots)
        assertEquals(3, profiles.createProfile("New", "#1E88E5")?.id)
    }

    @Test
    fun `calls made before the profile list has loaded wait for it`() = runBlocking {
        // On a busy device the stored list arrives after ProfileManager is built; until then
        // profiles holds a primary-only placeholder. Deleting or creating must not act on it.
        every { profileDataStore.profilesList } returns flow {
            delay(LIST_LOAD_DELAY_MS)
            emitAll(list)
        }
        val profiles = manager()

        assertTrue(profiles.deleteProfile(3))
        assertEquals(listOf(1, 2), list.value.map { it.id })

        // Slot 2 is taken: a create that read the placeholder would overwrite it.
        assertEquals(3, profiles.createProfile("New", "#1E88E5")?.id)
        assertEquals(listOf(1, 2, 3), list.value.map { it.id })
    }

    @Test
    fun `a file that won't delete keeps the slot from a new profile until a retry removes it`() = runBlocking {
        // The profile's DataStore files won't delete until the disk problem clears.
        var filesStuck = true
        every { factory.deleteProfileFiles(3) } answers { !filesStuck }
        val profiles = manager()

        assertTrue(profiles.deleteProfile(3))
        assertEquals(listOf(1, 2), list.value.map { it.id })
        assertEquals(setOf(3), pendingCleanup.slots)

        // Its slot isn't handed out while the old data is still there: the new profile gets 4.
        assertEquals(4, profiles.createProfile("New", "#1E88E5")?.id)
        assertEquals(setOf(3), pendingCleanup.slots)

        // Once the files can go, the next retry removes them and frees the slot.
        filesStuck = false
        assertEquals(emptySet<Int>(), profiles.retryPendingCleanups())
        assertEquals(3, profiles.createProfile("Next", "#1E88E5")?.id)
    }

    @Test
    fun `a cleanup killed part way is finished on the next start`() = runBlocking {
        // A previous run marked slot 3 and died before its cleanup finished; the list no longer has it.
        list.value = listOf(profile(1), profile(2))
        pendingCleanup.mark(3)

        // A new ProfileManager (the app starting) finishes it.
        val profiles = manager()
        assertEquals(emptySet<Int>(), profiles.retryPendingCleanups())

        coVerify { factory.clearProfile(3) }
        coVerify { profileDatabaseData.deleteProfile(3) }
        assertEquals(3, profiles.createProfile("New", "#1E88E5")?.id)
    }

    @Test
    fun `a step that throws before the profile leaves the list keeps it marked and listed`() = runBlocking {
        coEvery { profileDatabaseData.deleteProfile(3) } throws IOException("disk full")
        val profiles = manager()

        val result = runCatching { profiles.deleteProfile(3) }

        assertTrue(result.isFailure)
        assertEquals(listOf(1, 2, 3), list.value.map { it.id })
        assertEquals(setOf(3), pendingCleanup.slots)
        // Still a profile, so a retry leaves its data alone; deleting it again finishes the job.
        profiles.retryPendingCleanups()
        coVerify(exactly = 1) { profileDatabaseData.deleteProfile(3) }

        coEvery { profileDatabaseData.deleteProfile(3) } returns Unit
        assertTrue(profiles.deleteProfile(3))
        assertEquals(emptySet<Int>(), pendingCleanup.slots)
    }

    private companion object {
        const val LIST_LOAD_DELAY_MS = 200L
    }
}
