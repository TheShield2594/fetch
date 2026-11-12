package com.fetch.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.core.profile.ProfileManager
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit G5: the library lives in the database, a change writes only the rows it touches, and the
 * titles older builds stored in DataStore move over intact. The sync queue older builds kept next
 * to them is dropped.
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

            assertEquals("rows written by one add with $size titles", 1, rows.entriesWritten)
            assertEquals(size + 1, preferences.getAllItems(profileId = 1).size)
        }
    }

    @Test
    fun `adding and removing titles`() = runBlocking {
        val preferences = preferences()
        preferences.addItem(item(1), profileId = 1)
        preferences.addItem(item(2), profileId = 1)
        preferences.addItem(item(1).copy(name = "Renamed"), profileId = 1)
        preferences.removeItem("tt2", "MOVIE", profileId = 1)

        assertEquals(listOf("Renamed"), preferences.getAllItems(profileId = 1).map { it.name })
        assertTrue(preferences.containsItem("tt1", "movie", profileId = 1))
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
    fun `saved titles move from DataStore once, and the old sync queue is dropped`() = runBlocking {
        val dataStore = TestPreferencesStore()
        val viewSort = stringPreferencesKey("library_sort_option")
        dataStore.edit { prefs ->
            prefs[stringSetPreferencesKey("library_items")] = setOf(gson.toJson(item(1)), gson.toJson(item(2)), "{not json")
            prefs[stringSetPreferencesKey("library_pending_upserts")] = setOf("""{"contentId":"tt1","contentType":"movie"}""")
            // A queued delete no longer hides a saved title.
            prefs[stringSetPreferencesKey("library_pending_deletes")] = setOf("""{"contentId":"tt2","contentType":"movie"}""")
            prefs[longPreferencesKey("library_delta_cursor")] = 42L
            prefs[booleanPreferencesKey("library_delta_initialized")] = true
            prefs[longPreferencesKey("library_mutation_revision")] = 7L
            // LibraryViewPreferences moves this out itself; the library move leaves it.
            prefs[viewSort] = "title"
        }
        val rows = InMemoryKeyedEntryStore()

        val items = preferences(dataStore, rows).getAllItems(profileId = 1)

        assertEquals(listOf(item(1), item(2)), items)
        val left = dataStore.data.first()
        assertEquals(setOf("library_sort_option"), left.asMap().keys.map { it.name }.toSet())
        assertEquals("title", left[viewSort])

        rows.resetWriteCount()
        assertEquals(items, preferences(dataStore, rows).getAllItems(profileId = 1))
        assertEquals(0, rows.entriesWritten)
    }

    @Test
    fun `titles saved in the database by an older build still load, and its sync rows are dropped`() = runBlocking {
        val rows = InMemoryKeyedEntryStore()
        rows.upsert(
            1,
            listOf(
                KeyedEntry("item", "movie:tt1", "tt1", item(1).addedAt, gson.toJson(item(1))),
                KeyedEntry("item", "movie:tt2", "tt2", item(2).addedAt, gson.toJson(item(2))),
                KeyedEntry("pending_upsert", "movie:tt1", "tt1", 0L, """{"contentId":"tt1","contentType":"movie"}"""),
                KeyedEntry("pending_delete", "movie:tt3", "tt3", 0L, """{"contentId":"tt3","contentType":"movie"}"""),
                KeyedEntry("meta", "sync", "", 0L, "42|true|7")
            )
        )
        val preferences = preferences(rows = rows)

        assertEquals(listOf(item(1), item(2)), preferences.getAllItems(profileId = 1))
        assertEquals(setOf("item"), rows.all(1).map { it.kind }.toSet())

        // A title keeps its row: removing it by the old key works.
        preferences.removeItem("tt1", "movie", profileId = 1)
        assertEquals(listOf("movie:tt2"), rows.all(1).map { it.key })
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
