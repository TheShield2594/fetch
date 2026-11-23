package com.fetch.tv.data.website

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
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

/** Profile changes made while the website can't be reached are sent later, not pulled over (#617). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class PendingProfileChangesTest {

    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("pending_profiles_test", Context.MODE_PRIVATE)
    private lateinit var store: FetchWebsiteStore

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        store = FetchWebsiteStore(prefs)
        store.save(FetchWebsiteSession(serverUrl = "https://fetch.example", deviceToken = "token", deviceName = "TV"))
    }

    @Test
    fun `a profile change stays pending until a push that saw it gets through`() {
        store.markProfilesChanged()
        val seenByPush = store.profilesRevision
        store.markProfilesChanged()

        store.markProfilesSent(seenByPush)
        assertTrue(store.profilesRevision != 0L)

        store.markProfilesSent(store.profilesRevision)
        assertEquals(0L, store.profilesRevision)
    }

    @Test
    fun `a delete the website can't take waits, and goes once it can`() = runBlocking {
        val client = mockk<FetchWebsiteClient>(relaxed = true)
        val service = FetchWebsiteSyncService(
            store, client, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )

        coEvery { client.profiles() } throws IOException("offline")
        assertTrue(service.deleteProfile(3).isFailure)
        // Restarting doesn't forget it.
        assertEquals(setOf(3), FetchWebsiteStore(prefs).pendingProfileDeletes)

        val kid = WebsiteSlottedProfile(slot = 3, id = "website-profile-3", name = "Kid")
        coEvery { client.profiles() } returns listOf(kid)
        service.deleteProfile(3).getOrThrow()

        coVerify(exactly = 1) { client.deleteProfile("website-profile-3", any()) }
        assertTrue(store.pendingProfileDeletes.isEmpty())
    }

    @Test
    fun `signing out forgets what this account never got`() {
        store.markProfilesChanged()
        store.addPendingProfileDelete(4)

        store.clear()

        assertEquals(0L, store.profilesRevision)
        assertTrue(store.pendingProfileDeletes.isEmpty())
    }
}
