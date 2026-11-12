package com.fetch.tv.data.local.db

import androidx.room.Room
import com.fetch.tv.data.local.RoomWatchedItemStore
import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.domain.model.WatchedItemKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The watched items table itself, on a real (in-memory) SQLite database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RoomWatchedItemStoreTest {
    private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FetchDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val store = RoomWatchedItemStore(database)

    @After
    fun close() = database.close()

    private val movie = WatchedItem(contentId = "tt1", contentType = "movie", title = "Movie", watchedAt = 10L)
    private val series = WatchedItem(contentId = "tt2", contentType = "series", title = "Series", watchedAt = 20L)
    private fun episode(season: Int, episode: Int, watchedAt: Long = 30L) = WatchedItem(
        contentId = "tt2", contentType = "series", title = "Series", season = season, episode = episode,
        watchedAt = watchedAt, poster = "p.jpg", trackingProviderId = "trakt", trackingProviderItemId = "99"
    )

    @Test
    fun `entries round-trip, a missing season and episode included`() = runBlocking {
        store.upsert(1, listOf(movie, series, episode(0, 1), episode(1, 2)))

        assertEquals(setOf(movie, series, episode(0, 1), episode(1, 2)), store.all(1).toSet())
        assertEquals(setOf(series, episode(0, 1), episode(1, 2)), store.observeContent(1, "tt2").first().toSet())
    }

    @Test
    fun `an upsert replaces the entry with the same key`() = runBlocking {
        store.upsert(1, listOf(episode(1, 1, watchedAt = 1L)))
        store.upsert(1, listOf(episode(1, 1, watchedAt = 2L)))

        assertEquals(listOf(episode(1, 1, watchedAt = 2L)), store.all(1))
    }

    @Test
    fun `insertMissing keeps entries already there`() = runBlocking {
        store.upsert(1, listOf(episode(1, 1, watchedAt = 2L)))
        store.insertMissing(1, listOf(episode(1, 1, watchedAt = 1L), episode(1, 2)))

        assertEquals(setOf(episode(1, 1, watchedAt = 2L), episode(1, 2)), store.all(1).toSet())
    }

    @Test
    fun `delete removes only the named keys, a movie's included`() = runBlocking {
        store.upsert(1, listOf(movie, series, episode(1, 1)))
        store.delete(1, listOf(WatchedItemKey("tt1", null, null), WatchedItemKey("tt2", 1, 1)))

        assertEquals(listOf(series), store.all(1))
    }

    @Test
    fun `transform writes the difference in one transaction`() = runBlocking {
        store.upsert(1, listOf(movie, episode(1, 1), episode(1, 2)))

        store.transform(1) { current -> current.filterNot { it.episode == 2 } + episode(1, 3) }

        assertEquals(setOf(movie, episode(1, 1), episode(1, 3)), store.all(1).toSet())
    }

    @Test
    fun `profiles are kept apart and deleted on their own`() = runBlocking {
        store.upsert(1, listOf(movie))
        store.upsert(2, listOf(series))

        ProfileDatabaseData { database }.deleteProfile(2)

        assertEquals(listOf(movie), store.all(1))
        assertTrue(store.all(2).isEmpty())
    }
}
