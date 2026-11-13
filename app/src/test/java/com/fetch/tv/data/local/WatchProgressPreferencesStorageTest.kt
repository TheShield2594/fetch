package com.fetch.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.gson.Gson
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressPreferencesStorageTest {
    private val gson = Gson()

    @Test
    fun `empty legacy storage moves nothing and writes no file`() = runTest {
        val harness = harness(emptyMap())

        val result = harness.preferences.getAllRawEntries(profileId = 1)

        assertTrue(result.isEmpty())
        assertEquals(0, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
        assertEquals(0, harness.metadata.updateCount)
        assertEquals(0, harness.rows.entriesWritten)
    }

    @Test
    fun `a legacy single map moves to the database and leaves the file`() = runTest {
        val harness = harness(legacyEntries())

        val result = harness.preferences.getAllRawEntries(profileId = 1)

        assertEquals(250, result.size)
        assertEquals(legacyEntries(), result)
        assertNull(harness.metadata.value[watchProgressEntriesKey])
    }

    @Test
    fun `recent and archive buckets move together, the recent one winning a shared key`() = runTest {
        val archived = progress("item", lastWatched = 1L)
        val recent = archived.copy(position = 9_000L, lastWatched = 2L)
        val harness = harness(
            entries = emptyMap(),
            recent = mapOf("item" to recent, "new" to progress("new", 3L)),
            archive = mapOf("item" to archived, "old" to progress("old", 0L))
        )

        val result = harness.preferences.getAllRawEntries(profileId = 1)

        assertEquals(setOf("item", "new", "old"), result.keys)
        assertEquals(recent, result["item"])
        assertNull(harness.recent.value[watchProgressEntriesKey])
        assertNull(harness.archive.value[watchProgressEntriesKey])
    }

    @Test
    fun `the move runs once`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries(profileId = 1)
        harness.resetUpdateCounts()
        val second = WatchProgressPreferences(harness.factory, harness.profileManager, harness.rows)

        val result = second.getAllRawEntries(profileId = 1)

        assertEquals(250, result.size)
        assertEquals(0, harness.rows.entriesWritten)
        assertEquals(0, harness.metadata.updateCount)
    }

    @Test
    fun `saving progress writes one entry at 100, 1,000 and 15,000 entries`() = runTest {
        for (size in listOf(100, 1_000, 15_000)) {
            val entries = (0 until size).associate { "large$it" to progress("large$it", lastWatched = it.toLong()) }
            val harness = harness(entries)
            harness.preferences.getAllRawEntries(profileId = 1)
            harness.resetUpdateCounts()

            harness.preferences.saveProgress(entries.getValue("large0").copy(position = 5_000L, lastWatched = 20_000L), profileId = 1)

            assertEquals("entries written by one save with $size entries", 1, harness.rows.entriesWritten)
            assertEquals(0, harness.recent.updateCount + harness.archive.updateCount + harness.metadata.updateCount)
            assertEquals(5_000L, harness.preferences.getAllRawEntries(profileId = 1).getValue("large0").position)
        }
    }

    @Test
    fun `an episode save replaces the series-level entry`() = runTest {
        val seriesLevel = progress("show", 1L).copy(contentType = "series")
        val harness = harness(mapOf("show" to seriesLevel))
        harness.preferences.getAllRawEntries(profileId = 1)
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(seriesLevel.copy(season = 1, episode = 2, lastWatched = 2L), profileId = 1)

        assertEquals(setOf("show_s1e2"), harness.preferences.getAllRawEntries(profileId = 1).keys)
        assertEquals(2, harness.rows.entriesWritten)
    }

    @Test
    fun `a save keeps artwork the saved entry has and the new one lacks`() = runTest {
        val first = progress("item", 1L).copy(poster = "poster.jpg", name = "Name")
        val harness = harness(mapOf("item" to first))

        harness.preferences.saveProgress(first.copy(poster = null, name = "", position = 2_000L, lastWatched = 2L), profileId = 1)

        val saved = harness.preferences.getAllRawEntries(profileId = 1).getValue("item")
        assertEquals("poster.jpg", saved.poster)
        assertEquals("Name", saved.name)
        assertEquals(2_000L, saved.position)
    }

    @Test
    fun `removing a title removes its entries and nothing else`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries(profileId = 1)
        harness.resetUpdateCounts()

        harness.preferences.removeProgress("item0", profileId = 1)

        assertEquals(1, harness.rows.entriesWritten)
        assertFalse("item0" in harness.preferences.getAllRawEntries(profileId = 1))
        assertEquals(249, harness.preferences.getAllRawEntries(profileId = 1).size)
    }

    @Test
    fun `removing a series removes every episode entry`() = runTest {
        val episode = { s: Int, e: Int -> progress("show", 1L).copy(contentType = "series", season = s, episode = e) }
        val harness = harness(
            mapOf("show_s1e1" to episode(1, 1), "show_s1e2" to episode(1, 2), "other" to progress("other", 1L))
        )

        harness.preferences.removeProgress("show", profileId = 1)

        assertEquals(setOf("other"), harness.preferences.getAllRawEntries(profileId = 1).keys)
    }

    @Test
    fun `batch saves write only the entries they save`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries(profileId = 1)
        harness.resetUpdateCounts()
        val updates = (0 until 5).map { index ->
            entries.getValue("item$index").copy(lastWatched = 2_000L + index)
        }

        harness.preferences.saveProgressBatch(updates, profileId = 1)

        assertEquals(5, harness.rows.entriesWritten)
        assertEquals(250, harness.preferences.getAllRawEntries(profileId = 1).size)
    }

    @Test
    fun `remote merge adds the new entry and writes nothing else`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries(profileId = 1)
        harness.resetUpdateCounts()
        val remote = progress("remote", lastWatched = 2_000L)

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to remote),
            removeMissingRemoteEntries = false,
            profileId = 1
        )

        val result = harness.preferences.getAllRawEntries(profileId = 1)
        assertEquals(251, result.size)
        assertEquals(remote, result["remote"])
        assertEquals(1, harness.rows.entriesWritten)
    }

    @Test
    fun `remote delta deletes and adds entries`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries(profileId = 1)
        harness.resetUpdateCounts()
        val remote = progress("remote", lastWatched = 2_000L)

        harness.preferences.applyRemoteChanges(
            upserts = mapOf("remote" to remote),
            deletes = listOf("item0"),
            profileId = 1
        )

        val result = harness.preferences.getAllRawEntries(profileId = 1)
        assertEquals(250, result.size)
        assertFalse("item0" in result)
        assertEquals(remote, result["remote"])
        assertEquals(2, harness.rows.entriesWritten)
    }

    @Test
    fun `a deleted profile refuses writes and reads as empty`() = runTest {
        val harness = harness(emptyMap())
        every { harness.factory.get(2, any()) } returns DeletedProfileDataStore("deleted")

        assertTrue(harness.preferences.getAllRawEntries(profileId = 2).isEmpty())
        val refused = runCatching { harness.preferences.saveProgress(progress("x", 1L), profileId = 2) }
        assertTrue(refused.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(0, harness.rows.entriesWritten)
    }

    @Test
    fun `remote delta delete cannot remove a pending progress upsert`() = runTest {
        val local = progress("item", 100L)
        val harness = harness(mapOf("item" to local))
        harness.preferences.getAllRawEntries(profileId = 1)

        val preserved = harness.preferences.applyRemoteChanges(
            upserts = emptyMap(),
            deletes = listOf("item"),
            pendingUpsertKeys = setOf("item"),
            profileId = 1
        )

        assertTrue(preserved)
        assertEquals(local, harness.preferences.getAllRawEntries(profileId = 1)["item"])
    }

    @Test
    fun `remote delta upsert cannot restore a pending progress delete`() = runTest {
        val harness = harness(emptyMap())

        harness.preferences.applyRemoteChanges(
            upserts = mapOf("item" to progress("item", 100L)),
            deletes = emptyList(),
            pendingDeleteKeys = setOf("item"),
            profileId = 1
        )

        assertTrue(harness.preferences.getAllRawEntries(profileId = 1).isEmpty())
    }

    @Test
    fun `explicit progress flow remains bound after active profile changes`() = runTest {
        val harness = multiProfileHarness()
        val first = progress("first", 100L)
        val second = progress("second", 200L)
        harness.preferences.saveProgress(first, profileId = 1)
        harness.preferences.saveProgress(second, profileId = 2)
        val fixedProfileFlow = harness.preferences.getProgress("first", profileId = 1)

        harness.activeProfile.value = 2

        assertEquals(first, fixedProfileFlow.first())
    }

    @Test
    fun `clear preserving non trakt ids keeps only those entries`() = runTest {
        val entries = legacyEntries().toMutableMap().apply {
            this["custom:recent"] = progress("custom:recent", lastWatched = 2_000L)
            this["custom:archive"] = progress("custom:archive", lastWatched = -1L)
        }
        val harness = harness(entries)
        harness.preferences.getAllRawEntries(profileId = 1)

        harness.preferences.clearAllPreservingNonTraktIds(profileId = 1) { it.startsWith("custom:") }

        val result = harness.preferences.getAllRawEntries(profileId = 1)
        assertEquals(setOf("custom:recent", "custom:archive"), result.keys)
    }

    @Test
    fun `progress flow lists every moved entry, latest first`() = runTest {
        val harness = harness(legacyEntries())

        val result = harness.preferences.allRawProgress.first()

        assertEquals(250, result.size)
        assertEquals("item249", result.first().contentId)
        assertEquals("item0", result.last().contentId)
    }

    @Test
    fun `progress flow does not expose an intermediate bucket write`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.allRawProgress.first()
        val initialEmission = CompletableDeferred<Unit>()
        val nextEmission = async {
            var initialSeen = false
            harness.preferences.allRawProgress.first {
                if (!initialSeen) {
                    initialSeen = true
                    initialEmission.complete(Unit)
                    false
                } else {
                    true
                }
            }
        }
        initialEmission.await()

        harness.preferences.saveProgress(
            entries.getValue("item0").copy(position = 5_000L, lastWatched = 1_000L),
            profileId = 1
        )
        val result = nextEmission.await()

        assertEquals(250, result.size)
        assertEquals(5_000L, result.first { it.contentId == "item0" }.position)
    }

    // A pull deletes local entries the remote does not return, so the sync point decides
    // what counts as "already pushed". These pin that boundary, because a sync point taken
    // later than the data it describes silently deletes everything saved in between.

    @Test
    fun `progress saved after the sync point survives a pull that omits it`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.saveProgress(progress("unsynced", lastWatched = 200L), profileId = 1)

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to progress("remote", lastWatched = 50L)),
            lastSuccessfulPushMs = 100L,
            profileId = 1
        )

        assertTrue(harness.preferences.getAllRawEntries(profileId = 1).containsKey("unsynced"))
    }

    @Test
    fun `progress older than the sync point is dropped when the remote omits it`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.saveProgress(progress("synced", lastWatched = 50L), profileId = 1)

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to progress("remote", lastWatched = 50L)),
            lastSuccessfulPushMs = 100L,
            profileId = 1
        )

        assertFalse(harness.preferences.getAllRawEntries(profileId = 1).containsKey("synced"))
    }

    @Test
    fun `an entry saved exactly at the sync point counts as pushed`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.saveProgress(progress("boundary", lastWatched = 100L), profileId = 1)

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to progress("remote", lastWatched = 50L)),
            lastSuccessfulPushMs = 100L,
            profileId = 1
        )

        assertFalse(harness.preferences.getAllRawEntries(profileId = 1).containsKey("boundary"))
    }

    private fun harness(
        entries: Map<String, WatchProgress>,
        recent: Map<String, WatchProgress> = emptyMap(),
        archive: Map<String, WatchProgress> = emptyMap()
    ): Harness {
        val metadata = TestPreferencesDataStore(if (entries.isEmpty()) emptyPreferences() else preferences(entries))
        val recentStore = TestPreferencesDataStore(if (recent.isEmpty()) emptyPreferences() else preferences(recent))
        val archiveStore = TestPreferencesDataStore(if (archive.isEmpty()) emptyPreferences() else preferences(archive))
        val stores = mapOf(
            WATCH_PROGRESS_METADATA_FEATURE to metadata,
            WATCH_PROGRESS_RECENT_FEATURE to recentStore,
            WATCH_PROGRESS_ARCHIVE_FEATURE to archiveStore
        )
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getValue(secondArg<String>())
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val rows = InMemoryKeyedEntryStore()
        return Harness(
            preferences = WatchProgressPreferences(factory, profileManager, rows),
            factory = factory,
            profileManager = profileManager,
            metadata = metadata,
            recent = recentStore,
            archive = archiveStore,
            rows = rows
        )
    }

    private fun multiProfileHarness(): MultiProfileHarness {
        val activeProfile = MutableStateFlow(1)
        val stores = mutableMapOf<Pair<Int, String>, TestPreferencesDataStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) {
                TestPreferencesDataStore()
            }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile
        return MultiProfileHarness(
            preferences = WatchProgressPreferences(factory, profileManager, InMemoryKeyedEntryStore()),
            activeProfile = activeProfile
        )
    }

    private fun legacyEntries(): Map<String, WatchProgress> {
        return (0 until 250).associate { index ->
            "item$index" to progress("item$index", lastWatched = index.toLong())
        }
    }

    private fun progress(contentId: String, lastWatched: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1_000L,
        duration = 10_000L,
        lastWatched = lastWatched
    )

    private fun preferences(entries: Map<String, WatchProgress>): Preferences {
        return emptyPreferences().toMutablePreferences().apply {
            this[watchProgressEntriesKey] = gson.toJson(entries)
        }.toPreferences()
    }

    private data class Harness(
        val preferences: WatchProgressPreferences,
        val factory: ProfileDataStoreFactory,
        val profileManager: ProfileManager,
        val metadata: TestPreferencesDataStore,
        val recent: TestPreferencesDataStore,
        val archive: TestPreferencesDataStore,
        val rows: InMemoryKeyedEntryStore
    ) {
        fun resetUpdateCounts() {
            metadata.resetUpdateCount()
            recent.resetUpdateCount()
            archive.resetUpdateCount()
            rows.resetWriteCount()
        }
    }

    private data class MultiProfileHarness(
        val preferences: WatchProgressPreferences,
        val activeProfile: MutableStateFlow<Int>
    )

    private class TestPreferencesDataStore(
        initial: Preferences = emptyPreferences()
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        var updateCount: Int = 0
            private set

        val value: Preferences
            get() = state.value

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            return mutex.withLock {
                transform(state.value).also { updated ->
                    updateCount += 1
                    state.value = updated
                }
            }
        }

        fun resetUpdateCount() {
            updateCount = 0
        }
    }
}
