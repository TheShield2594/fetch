package com.fetch.tv.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.SavedLibraryItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roadmap G1: the library is decoded once per change, shared, and reused entry by entry. */
class LibraryPreferencesDecodeTest {

    private val profileId = MutableStateFlow(1)
    private val profileManager = mockk<ProfileManager> {
        every { activeProfileId } returns profileId
    }

    @Test
    fun `adding one title reuses every already decoded entry`() = runBlocking {
        val preferences = LibraryPreferences(factoryOf(TestPreferencesStore()), profileManager, InMemoryKeyedEntryStore())
        (0 until LIBRARY_SIZE).forEach { preferences.addItem(item(it)) }
        val before = preferences.getAllItems().associateBy { it.id }

        preferences.addItem(item(LIBRARY_SIZE))
        val after = preferences.getAllItems()

        assertEquals(LIBRARY_SIZE + 1, after.size)
        val redecoded = after.count { item -> before[item.id]?.let { it !== item } ?: false }
        assertEquals("entries parsed again after adding one title", 0, redecoded)
    }

    @Test
    fun `observers share one decode`() = runBlocking {
        val preferences = LibraryPreferences(factoryOf(TestPreferencesStore()), profileManager, InMemoryKeyedEntryStore())
        (0 until 50).forEach { preferences.addItem(item(it)) }

        val first = withTimeout(TIMEOUT_MS) { preferences.libraryItems.first { it.size == 50 } }
        val second = withTimeout(TIMEOUT_MS) { preferences.libraryItems.first() }

        assertSame(first, second)
    }

    @Test
    fun `in library follows the shared list`() = runBlocking {
        val preferences = LibraryPreferences(factoryOf(TestPreferencesStore()), profileManager, InMemoryKeyedEntryStore())
        preferences.addItem(item(7))

        assertTrue(withTimeout(TIMEOUT_MS) { preferences.isInLibrary("tt7", "MOVIE").first() })
        assertEquals(false, withTimeout(TIMEOUT_MS) { preferences.isInLibrary("tt8", "movie").first() })
    }

    @Test
    fun `view preferences move out of the library file once`() = runBlocking {
        val sort = stringPreferencesKey("library_sort_option")
        val tab = stringPreferencesKey("library_last_selected_type")
        val legacy = TestPreferencesStore(mutablePreferencesOf(sort to "title", tab to "movies"))
        val view = TestPreferencesStore()
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), LibraryViewPreferences.LEGACY_FEATURE) } returns legacy
            every { get(any(), LibraryViewPreferences.FEATURE) } returns view
        }
        val preferences = LibraryViewPreferences(factory, profileManager)

        assertEquals("title", withTimeout(TIMEOUT_MS) { preferences.sortOption.first() })
        assertEquals("movies", withTimeout(TIMEOUT_MS) { preferences.lastSelectedType.first() })
        assertNull(legacy.value[sort])
        assertNull(legacy.value[tab])

        preferences.setSortOption("added")
        assertEquals("added", withTimeout(TIMEOUT_MS) { preferences.sortOption.first() })
        assertNull("the library file is not written by view changes", legacy.value[sort])
    }

    private fun factoryOf(store: TestPreferencesStore) = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } returns store
    }

    private fun item(index: Int) = SavedLibraryItem(
        id = "tt$index",
        type = "movie",
        name = "Movie $index",
        poster = "https://example.com/$index.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        description = "Description $index",
        releaseInfo = "2020",
        imdbRating = 7.5f,
        genres = listOf("Drama"),
        addonBaseUrl = null,
        addedAt = 1_700_000_000_000L + index
    )

    private companion object {
        const val LIBRARY_SIZE = 500
        const val TIMEOUT_MS = 10_000L
    }
}
