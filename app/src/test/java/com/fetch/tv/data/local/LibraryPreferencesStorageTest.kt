package com.fetch.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.LibrarySyncKey
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.SavedLibraryItem
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit G5: the library and its queue of changes waiting to sync live in the database, a change
 * writes only the rows it touches, and what older builds stored in DataStore moves over intact.
 */
class LibraryPreferencesStorageTest {
    private val gson = Gson()
    private val profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }

    private fun preferences(dataStore: TestPreferencesStore = TestPreferencesStore(), rows: InMemoryKeyedEntryStore = InMemoryKeyedEntryStore()) =
        LibraryPreferences(mockk { every { get(any(), any()) } returns dataStore }, profileManager, rows)

    @Test
    fun `adding a title writes a bounded number of rows at 100, 1,000 and 5,000 titles`() = runBlocking {
        for (size in listOf(100, 1_000, 5_000)) {
            val rows = InMemoryKeyedEntryStore()
            val preferences = preferences(rows = rows)
            (0 until size).forEach { preferences.addItem(item(it), profileId = 1) }
            rows.resetWriteCount()

            preferences.addItem(item(size), profileId = 1)

            // The title, its queued upsert and the sync counters.
            assertEquals("rows written by one add with $size titles", 3, rows.entriesWritten)
            assertEquals(size + 1, preferences.getAllItems(profileId = 1).size)
        }
    }

    @Test
    fun `adding and removing queue the change for sync`() = runBlocking {
        val preferences = preferences()
        preferences.addItem(item(1), profileId = 1)
        preferences.addItem(item(2), profileId = 1)
        preferences.removeItem("tt2", "movie", profileId = 1)

        val state = preferences.getSyncState(profileId = 1)
        assertEquals(listOf("tt1"), state.items.map { it.id })
        assertEquals(listOf(LibrarySyncKey("tt1", "movie")), state.pendingUpsertKeys)
        assertEquals(listOf(LibrarySyncKey("tt2", "movie")), state.pendingDeleteKeys)
        assertEquals(3L, state.mutationRevision)
    }

    @Test
    fun `removing a title that isn't saved writes nothing`() = runBlocking {
        val rows = InMemoryKeyedEntryStore()
        val preferences = preferences(rows = rows)
        preferences.addItem(item(1), profileId = 1)
        rows.resetWriteCount()

        preferences.removeItem("tt9", "movie", profileId = 1)

        assertEquals(0, rows.entriesWritten)
    }

    @Test
    fun `a logo update writes the one title`() = runBlocking {
        val rows = InMemoryKeyedEntryStore()
        val preferences = preferences(rows = rows)
        (0 until 100).forEach { preferences.addItem(item(it), profileId = 1) }
        rows.resetWriteCount()

        preferences.updateLogo("tt5", "MOVIE", "logo.png", profileId = 1)

        assertEquals(1, rows.entriesWritten)
        assertEquals("logo.png", preferences.getAllItems(profileId = 1).single { it.id == "tt5" }.logo)
    }

    @Test
    fun `the library and its sync queue move from DataStore once, with their counters`() = runBlocking {
        val dataStore = TestPreferencesStore()
        val viewSort = stringPreferencesKey("library_sort_option")
        dataStore.edit { prefs ->
            prefs[stringSetPreferencesKey("library_items")] = setOf(gson.toJson(item(1)), gson.toJson(item(2)), "{not json")
            prefs[stringSetPreferencesKey("library_pending_upserts")] = setOf("""{"contentId":"tt1","contentType":"movie"}""")
            prefs[stringSetPreferencesKey("library_pending_deletes")] = setOf("""{"contentId":"tt3","contentType":"movie"}""")
            prefs[longPreferencesKey("library_delta_cursor")] = 42L
            prefs[booleanPreferencesKey("library_delta_initialized")] = true
            prefs[longPreferencesKey("library_mutation_revision")] = 7L
            // LibraryViewPreferences moves this out itself; the library move leaves it.
            prefs[viewSort] = "title"
        }
        val rows = InMemoryKeyedEntryStore()

        val state = preferences(dataStore, rows).getSyncState(profileId = 1)

        assertEquals(setOf("tt1", "tt2"), state.items.map { it.id }.toSet())
        assertEquals(listOf(LibrarySyncKey("tt1", "movie")), state.pendingUpsertKeys)
        assertEquals(listOf(LibrarySyncKey("tt3", "movie")), state.pendingDeleteKeys)
        assertEquals(42L, state.deltaCursorEventId)
        assertTrue(state.deltaInitialized)
        assertEquals(7L, state.mutationRevision)
        val left = dataStore.data.first()
        assertNull(left[stringSetPreferencesKey("library_items")])
        assertNull(left[stringSetPreferencesKey("library_pending_upserts")])
        assertNull(left[longPreferencesKey("library_mutation_revision")])
        assertEquals("title", left[viewSort])

        rows.resetWriteCount()
        assertEquals(state, preferences(dataStore, rows).getSyncState(profileId = 1))
        assertEquals(0, rows.entriesWritten)
    }

    @Test
    fun `a deleted profile refuses writes and reads as empty`() = runBlocking {
        val rows = InMemoryKeyedEntryStore()
        val preferences = LibraryPreferences(
            mockk { every { get(any(), any()) } returns DeletedProfileDataStore("deleted") },
            profileManager,
            rows
        )

        assertTrue(preferences.getAllItems(profileId = 1).isEmpty())
        val refused = runCatching { preferences.addItem(item(1), profileId = 1) }
        assertTrue(refused.exceptionOrNull() is CancellationException)
        assertEquals(0, rows.entriesWritten)
    }

    @Test
    fun `sync counters survive a round trip and reject anything else`() {
        val text = LibraryPreferences.encodeSyncCounters(12L, true, 3L)
        assertEquals(Triple(12L, true, 3L), LibraryPreferences.decodeSyncCounters(text))
        assertNull(LibraryPreferences.decodeSyncCounters("12|maybe|3"))
        assertNull(LibraryPreferences.decodeSyncCounters("{}"))
    }

    private fun item(index: Int) = SavedLibraryItem(
        id = "tt$index",
        type = "movie",
        name = "Movie $index",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        description = null,
        releaseInfo = "2020",
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        addedAt = 1_700_000_000_000L + index
    )
}
