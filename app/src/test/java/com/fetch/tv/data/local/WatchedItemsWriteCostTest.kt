package com.fetch.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.WatchedItem
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit G5: marking an episode watched must write a bounded amount however long the history is.
 * The DataStore set this replaced rewrote every entry (as JSON) on each mark, then copied the file
 * again as a shadow backup. Checks entries written rather than timing, so the result doesn't depend
 * on the machine; the bytes the old layout rewrote are printed alongside for comparison.
 */
class WatchedItemsWriteCostTest {

    @Test
    fun `marking an episode watched writes one entry at 100, 1,000 and 5,000 watched items`() = runBlocking {
        for (size in listOf(100, 1_000, 5_000)) {
            val (preferences, store) = preferences()
            preferences.markAsWatchedBatch((0 until size).map(::item), PROFILE_ID)
            store.resetWriteCount()

            preferences.markAsWatched(item(size), PROFILE_ID)

            assertEquals("entries written by one mark with $size watched items", 1, store.entriesWritten)
            assertEquals(size + 1, preferences.getAllItems(PROFILE_ID).size)
            println(
                "G5 write cost at $size watched items: 1 entry now; " +
                    "${legacyRewriteBytes(size + 1)} bytes rewritten (plus a shadow copy) before"
            )
        }
    }

    @Test
    fun `unmarking and re-marking stay bounded too`() = runBlocking {
        val (preferences, store) = preferences()
        preferences.markAsWatchedBatch((0 until 5_000).map(::item), PROFILE_ID)
        store.resetWriteCount()

        preferences.unmarkAsWatched(item(42).contentId, item(42).season, item(42).episode, PROFILE_ID)
        preferences.markAsWatched(item(42).copy(watchedAt = 1L), PROFILE_ID)

        assertEquals(2, store.entriesWritten)
    }

    @Test
    fun `a remote change set writes only what it changes`() = runBlocking {
        val (preferences, store) = preferences()
        val history = (0 until 5_000).map(::item)
        preferences.markAsWatchedBatch(history, PROFILE_ID)
        store.resetWriteCount()

        // The remote sends back three entries the TV already has, one new one and one delete.
        preferences.applyRemoteChanges(
            upserts = history.take(3) + item(5_000),
            deletes = listOf(Triple(history[10].contentId, history[10].season, history[10].episode)),
            profileId = PROFILE_ID
        )

        assertEquals(2, store.entriesWritten)
    }

    @Test
    fun `push bookkeeping writes do not re-emit the watched list`() = runBlocking {
        val (preferences, _) = preferences()
        preferences.markAsWatchedBatch((0 until 1_000).map(::item), PROFILE_ID)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val emissions = MutableStateFlow(0)
            scope.launch { preferences.observeAllItems(PROFILE_ID).collect { emissions.update { it + 1 } } }
            withTimeout(AWAIT_TIMEOUT_MS) { emissions.first { it >= 1 } }

            preferences.advanceLastSuccessfulPushMs(1_000L, PROFILE_ID)
            preferences.setDeltaState(cursor = 5L, profileId = PROFILE_ID)
            delay(QUIET_WINDOW_MS)

            assertEquals(1, emissions.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a legacy history is moved once, and later marks stay bounded`() = runBlocking {
        val dataStore = TestPreferencesStore()
        val legacy = (0 until 5_000).map(::item)
        dataStore.edit { it[LEGACY_KEY] = legacy.map { item -> Gson().toJson(item) }.toSet() }
        val (preferences, store) = preferences(dataStore)

        assertEquals(5_000, preferences.getAllItems(PROFILE_ID).size)
        assertTrue("the legacy set is removed once moved", dataStore.data.first()[LEGACY_KEY] == null)

        store.resetWriteCount()
        preferences.markAsWatched(item(5_000), PROFILE_ID)
        assertEquals(1, store.entriesWritten)
    }

    private fun preferences(
        dataStore: TestPreferencesStore = TestPreferencesStore()
    ): Pair<WatchedItemsPreferences, InMemoryWatchedItemStore> {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns dataStore
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(PROFILE_ID)
        val store = InMemoryWatchedItemStore()
        return WatchedItemsPreferences(factory, profileManager, store) to store
    }

    /** What one mark rewrote in the old layout: every entry's JSON. */
    private fun legacyRewriteBytes(size: Int): Int {
        val gson = Gson()
        return (0 until size).sumOf { gson.toJson(item(it)).toByteArray().size }
    }

    private fun item(index: Int) = WatchedItem(
        contentId = "tt${1_000_000 + index / EPISODES_PER_SHOW}",
        contentType = "series",
        title = "Show ${index / EPISODES_PER_SHOW}",
        season = 1,
        episode = index % EPISODES_PER_SHOW + 1,
        watchedAt = 1_700_000_000_000L + index,
        poster = "https://example.com/poster/${index / EPISODES_PER_SHOW}.jpg"
    )

    private companion object {
        const val PROFILE_ID = 1
        const val EPISODES_PER_SHOW = 20
        const val QUIET_WINDOW_MS = 500L
        const val AWAIT_TIMEOUT_MS = 30_000L
        val LEGACY_KEY = stringSetPreferencesKey("watched_items")
    }
}
