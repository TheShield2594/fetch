package com.fetch.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.domain.model.WatchedMutationKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedItemsPreferencesSyncTest {
    @Test
    fun `snapshot is authoritative for non-pending items`() = runTest {
        val harness = harness()
        val local = item("local", 100L)
        val remote = item("remote", 200L)
        harness.preferences.markAsWatched(local, 1)

        harness.preferences.replaceWithRemoteItems(listOf(remote), profileId = 1)

        assertEquals(listOf(remote), harness.preferences.getAllItems(1))
    }

    @Test
    fun `empty snapshot clears non-pending watched items`() = runTest {
        val harness = harness()
        harness.preferences.markAsWatched(item("local", 100L), 1)

        harness.preferences.replaceWithRemoteItems(emptyList(), profileId = 1)

        assertTrue(harness.preferences.getAllItems(1).isEmpty())
    }

    @Test
    fun `pending watched upsert survives snapshot`() = runTest {
        val harness = harness()
        val local = item("local", 100L)
        val key = WatchedMutationKey("local", 1, 2)
        harness.preferences.markAsWatched(local, 1)

        val preserved = harness.preferences.replaceWithRemoteItems(
            remoteItems = emptyList(),
            pendingUpsertKeys = setOf(key),
            profileId = 1
        )

        assertTrue(preserved)
        assertEquals(listOf(local), harness.preferences.getAllItems(1))
    }

    @Test
    fun `pending watched delete suppresses snapshot upsert`() = runTest {
        val harness = harness()
        val remote = item("remote", 100L)
        val key = WatchedMutationKey("remote", 1, 2)

        harness.preferences.replaceWithRemoteItems(
            remoteItems = listOf(remote),
            pendingDeleteKeys = setOf(key),
            profileId = 1
        )

        assertTrue(harness.preferences.getAllItems(1).isEmpty())
    }

    @Test
    fun `remote delete cannot remove pending watched upsert`() = runTest {
        val harness = harness()
        val local = item("local", 100L)
        val key = WatchedMutationKey("local", 1, 2)
        harness.preferences.markAsWatched(local, 1)

        harness.preferences.applyRemoteChanges(
            upserts = emptyList(),
            deletes = listOf(Triple("local", 1, 2)),
            pendingUpsertKeys = setOf(key),
            profileId = 1
        )

        assertEquals(listOf(local), harness.preferences.getAllItems(1))
    }

    @Test
    fun `remote upsert cannot restore pending watched delete`() = runTest {
        val harness = harness()
        val remote = item("remote", 100L)
        val key = WatchedMutationKey("remote", 1, 2)

        harness.preferences.applyRemoteChanges(
            upserts = listOf(remote),
            deletes = emptyList(),
            pendingDeleteKeys = setOf(key),
            profileId = 1
        )

        assertTrue(harness.preferences.getAllItems(1).isEmpty())
    }

    @Test
    fun `explicit watched flow remains bound after active profile changes`() = runTest {
        val harness = harness()
        val firstProfileItem = item("first", 100L)
        val secondProfileItem = item("second", 200L)
        harness.preferences.markAsWatched(firstProfileItem, 1)
        harness.preferences.markAsWatched(secondProfileItem, 2)
        val fixedProfileFlow = harness.preferences.observeAllItems(1)

        harness.activeProfile.value = 2

        assertEquals(listOf(firstProfileItem), fixedProfileFlow.first())
        assertFalse(fixedProfileFlow.first().contains(secondProfileItem))
    }

    @Test
    fun `unchanged entries survive later writes`() = runTest {
        val harness = harness()
        val first = item("first", 100L)
        val second = item("second", 200L)
        harness.preferences.markAsWatched(first, 1)
        val before = harness.preferences.getAllItems(1).single()

        harness.preferences.markAsWatched(second, 1)
        harness.preferences.advanceLastSuccessfulPushMs(300L, 1)
        val after = harness.preferences.getAllItems(1)

        assertEquals(setOf(first, second), after.toSet())
        assertEquals(before, after.single { it.contentId == "first" })
        assertEquals(after.toSet(), harness.preferences.observeAllItems(1).first().toSet())
    }

    @Test
    fun `decoded items follow updates to the same entry`() = runTest {
        val harness = harness()
        harness.preferences.markAsWatched(item("show", 100L), 1)
        harness.preferences.getAllItems(1)

        harness.preferences.markAsWatched(item("show", 200L), 1)

        assertEquals(listOf(item("show", 200L)), harness.preferences.getAllItems(1))
    }

    @Test
    fun `a legacy set moves to the store once, skipping entries that fail to parse`() = runTest {
        val store = TestPreferencesStore()
        val valid = item("valid", 100L)
        val key = stringSetPreferencesKey("watched_items")
        store.edit { prefs -> prefs[key] = setOf(com.google.gson.Gson().toJson(valid), "{not json") }
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val preferences = WatchedItemsPreferences(factory, profileManager, InMemoryWatchedItemStore())

        assertEquals(listOf(valid), preferences.getAllItems(1))
        assertEquals(null, store.data.first()[key])
        assertEquals(listOf(valid), preferences.getAllItems(1))
    }

    @Test
    fun `an entry already in the store wins over the legacy one`() = runTest {
        val store = TestPreferencesStore()
        val key = stringSetPreferencesKey("watched_items")
        store.edit { prefs -> prefs[key] = setOf(com.google.gson.Gson().toJson(item("show", 100L))) }
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val rows = InMemoryWatchedItemStore()
        // A move interrupted after the insert: the newer mark is already in the store.
        rows.upsert(1, listOf(item("show", 200L)))
        val preferences = WatchedItemsPreferences(factory, profileManager, rows)

        assertEquals(listOf(item("show", 200L)), preferences.getAllItems(1))
    }

    private fun harness(): Harness {
        val activeProfile = MutableStateFlow(1)
        val stores = mutableMapOf<Int, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg()) { TestPreferencesStore() }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile
        return Harness(
            preferences = WatchedItemsPreferences(factory, profileManager, InMemoryWatchedItemStore()),
            activeProfile = activeProfile
        )
    }

    private fun item(contentId: String, watchedAt: Long) = WatchedItem(
        contentId = contentId,
        contentType = "series",
        title = contentId,
        season = 1,
        episode = 2,
        watchedAt = watchedAt
    )

    private data class Harness(
        val preferences: WatchedItemsPreferences,
        val activeProfile: MutableStateFlow<Int>
    )
}
