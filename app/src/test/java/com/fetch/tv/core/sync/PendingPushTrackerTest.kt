package com.fetch.tv.core.sync

import android.content.Context
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.backend.SyncBackend
import com.fetch.tv.core.sync.backend.SyncBackends
import com.fetch.tv.data.local.CollectionsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
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

/** A local change that couldn't be sent must not be lost to the next pull (#616). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class PendingPushTrackerTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var tracker: PendingPushTracker

    @Before
    fun setUp() {
        context.getSharedPreferences("sync_pending_pushes", Context.MODE_PRIVATE).edit().clear().commit()
        tracker = PendingPushTracker(context)
    }

    @Test
    fun `a change stays pending until a push that saw it succeeds`() {
        val domain = PendingPushTracker.Domain.COLLECTIONS
        assertFalse(tracker.isDirty(domain, 2))

        tracker.markDirty(domain, 2)
        val seenByPush = tracker.revision(domain, 2)
        // Another edit lands while that push is on its way.
        tracker.markDirty(domain, 2)
        tracker.markSent(domain, 2, seenByPush)
        assertTrue("the newer edit is still unsent", tracker.isDirty(domain, 2))

        tracker.markSent(domain, 2, tracker.revision(domain, 2))
        assertFalse(tracker.isDirty(domain, 2))
    }

    @Test
    fun `marks are per domain and profile, survive a restart, and go with the profile or account`() {
        tracker.markDirty(PendingPushTracker.Domain.ADDONS, 1)
        tracker.markDirty(PendingPushTracker.Domain.HOME_LAYOUT, 3)

        val afterRestart = PendingPushTracker(context)
        assertTrue(afterRestart.isDirty(PendingPushTracker.Domain.ADDONS, 1))
        assertFalse(afterRestart.isDirty(PendingPushTracker.Domain.ADDONS, 3))
        assertFalse(afterRestart.isDirty(PendingPushTracker.Domain.COLLECTIONS, 1))

        afterRestart.clearProfile(3)
        assertFalse(afterRestart.isDirty(PendingPushTracker.Domain.HOME_LAYOUT, 3))
        assertTrue(afterRestart.isDirty(PendingPushTracker.Domain.ADDONS, 1))

        afterRestart.clearAll()
        assertFalse(afterRestart.isDirty(PendingPushTracker.Domain.ADDONS, 1))
    }

    @Test
    fun `a pull sends a pending collections change first, and keeps the local copy when it can't`() = runBlocking {
        val backend = mockk<SyncBackend>(relaxed = true)
        val backends = mockk<SyncBackends> {
            every { active } returns backend
            every { canSync } returns true
        }
        val profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val collections = mockk<CollectionsDataStore>(relaxed = true) {
            coEvery { exportCurrentProfileJson() } returns "[]"
        }
        val service = CollectionSyncService(collections, profileManager, backends, tracker)
        tracker.markDirty(PendingPushTracker.Domain.COLLECTIONS, 1)

        // The website is unreachable: nothing is pulled over the unsent edit, and it stays pending.
        coEvery { backend.pushCollections(1, any()) } returns Result.failure(IOException("offline"))
        assertTrue(service.pullFromRemote().isFailure)
        coVerify(exactly = 0) { backend.pullCollections(any(), any()) }
        coVerify(exactly = 0) { collections.setCollections(any()) }
        assertTrue(tracker.isDirty(PendingPushTracker.Domain.COLLECTIONS, 1))

        // Back online: the edit goes up, then the pull runs.
        coEvery { backend.pushCollections(1, any()) } returns Result.success(null)
        coEvery { backend.pullCollections(1, any()) } returns Result.success(JsonArray(emptyList()))
        service.pullFromRemote().getOrThrow()
        coVerify(exactly = 2) { backend.pushCollections(1, any()) }
        coVerify(exactly = 1) { backend.pullCollections(1, any()) }
        assertEquals(false, tracker.isDirty(PendingPushTracker.Domain.COLLECTIONS, 1))
    }
}
