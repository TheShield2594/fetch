package com.fetch.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.domain.model.WatchedItemKey
import com.fetch.tv.domain.model.itemKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The watched movies, episodes and series of each profile. The entries live in [WatchedItemStore]
 * (SQLite), so marking one episode watched writes that one entry however long the history is
 * (audit G5).
 *
 * Older versions kept every entry as JSON in one DataStore string set, next to sync bookkeeping
 * (last push time, delta cursor). The first time a profile's items are read or written, the set
 * is moved into the store and those keys are removed.
 */
@Singleton
class WatchedItemsPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val items: WatchedItemStore
) {
    companion object {
        private const val FEATURE = "watched_items_preferences"
        private const val TAG = "WatchedItemsPrefs"
    }

    private fun store(profileId: Int) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()

    /** Where older versions kept the entries; read once by [ensureMigrated], then removed. */
    private val legacyWatchedItemsKey = stringSetPreferencesKey("watched_items")
    /** Sync bookkeeping older versions kept next to the entries; never read, removed with the set. */
    private val legacyBookkeepingKeys: List<Preferences.Key<*>> = listOf(
        longPreferencesKey("last_successful_watched_push_ms"),
        longPreferencesKey("watched_items_delta_cursor"),
        booleanPreferencesKey("watched_items_delta_initialized")
    )

    private val migratedProfiles: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val migrationMutex = Mutex()

    /**
     * Moves [profileId]'s entries from the legacy DataStore set into [items], once per process.
     * Entries are inserted before the set is removed, and an entry already in the store wins, so
     * a move interrupted halfway is finished on the next start without losing anything.
     */
    private suspend fun ensureMigrated(profileId: Int) {
        if (profileId in migratedProfiles) return
        migrationMutex.withLock {
            if (profileId in migratedProfiles) return
            val store = store(profileId)
            // A deleted profile's store reads as empty; there is nothing to move.
            if (store is DeletedProfileDataStore) return
            val prefs = store.data.first()
            val legacy = prefs[legacyWatchedItemsKey]
            if (legacy != null) {
                val decoded = legacy.mapNotNull { json ->
                    // An entry that doesn't parse is dropped, as reads of the old set skipped it.
                    try {
                        gson.fromJson(json, WatchedItem::class.java)
                    } catch (e: com.google.gson.JsonParseException) {
                        null
                    }
                }
                items.insertMissing(profileId, decoded)
                store.edit { it.remove(legacyWatchedItemsKey) }
                Log.i(TAG, "Moved ${decoded.size} of ${legacy.size} watched items of profile $profileId to the database")
            }
            if (legacyBookkeepingKeys.any { it in prefs }) {
                store.edit { edited -> legacyBookkeepingKeys.forEach { edited.remove(it) } }
            }
            migratedProfiles += profileId
        }
    }

    /**
     * Runs a write for [profileId], unless the profile was deleted: like a deleted profile's
     * DataStore, it refuses with a [CancellationException], so sync work still running for it
     * stops instead of writing rows for a profile that's gone.
     */
    private suspend fun write(profileId: Int, block: suspend () -> Unit) {
        if (store(profileId) is DeletedProfileDataStore) {
            throw CancellationException("Profile $profileId was deleted")
        }
        ensureMigrated(profileId)
        block()
    }

    internal val allItems: Flow<List<WatchedItem>> = profileManager.activeProfileId.flatMapLatest { pid ->
        observeAllItems(pid)
    }

    fun observeAllItems(profileId: Int): Flow<List<WatchedItem>> = flow {
        if (store(profileId) is DeletedProfileDataStore) {
            emit(emptyList())
            return@flow
        }
        ensureMigrated(profileId)
        emitAll(items.observe(profileId))
    }

    private fun observeContent(profileId: Int, contentId: String): Flow<List<WatchedItem>> = flow {
        if (store(profileId) is DeletedProfileDataStore) {
            emit(emptyList())
            return@flow
        }
        ensureMigrated(profileId)
        emitAll(items.observeContent(profileId, contentId))
    }

    fun isWatched(contentId: String, season: Int? = null, episode: Int? = null): Flow<Boolean> {
        return profileManager.activeProfileId.flatMapLatest { pid -> observeContent(pid, contentId) }.map { entries ->
            entries.any { item -> item.season == season && item.episode == episode }
        }
    }

    fun getWatchedEpisodesForContent(
        contentId: String,
        profileId: Int
    ): Flow<Set<Pair<Int, Int>>> {
        return observeContent(profileId, contentId).map { entries ->
            entries.filter { it.season != null && it.episode != null }
                .map { it.season!! to it.episode!! }
                .toSet()
        }
    }

    fun getWatchedEpisodesWithTimestamps(contentId: String): Flow<Map<Pair<Int, Int>, Long>> {
        return profileManager.activeProfileId.flatMapLatest { pid -> observeContent(pid, contentId) }.map { entries ->
            entries.filter { it.season != null && it.episode != null }
                .associate { (it.season!! to it.episode!!) to it.watchedAt }
        }
    }

    suspend fun markAsWatched(
        item: WatchedItem,
        profileId: Int
    ) = write(profileId) { items.upsert(profileId, listOf(item)) }

    suspend fun markAsWatchedBatch(
        items: List<WatchedItem>,
        profileId: Int
    ) {
        if (items.isEmpty()) return
        // The last entry for a key wins, as it did when the set was rewritten.
        val latest = items.associateBy { it.itemKey() }.values.toList()
        write(profileId) { this.items.upsert(profileId, latest) }
    }

    suspend fun unmarkAsWatched(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        profileId: Int
    ) = write(profileId) { items.delete(profileId, listOf(WatchedItemKey(contentId, season, episode))) }

    suspend fun unmarkAsWatchedBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>,
        profileId: Int
    ) {
        if (episodes.isEmpty()) return
        val keys = episodes.map { (s, e) -> WatchedItemKey(contentId, s, e) }.toSet()
        write(profileId) { items.delete(profileId, keys) }
    }

    suspend fun getAllItems(profileId: Int): List<WatchedItem> {
        if (store(profileId) is DeletedProfileDataStore) return emptyList()
        ensureMigrated(profileId)
        return items.all(profileId)
    }

    /** Adds the remote entries this TV doesn't have; local entries win. */
    suspend fun mergeRemoteItems(remoteItems: List<WatchedItem>, profileId: Int) {
        if (remoteItems.isEmpty()) return
        write(profileId) { items.insertMissing(profileId, remoteItems) }
    }

    suspend fun clearAll(profileId: Int) {
        write(profileId) { items.clear(profileId) }
        store(profileId).edit { preferences ->
            preferences.remove(legacyWatchedItemsKey)
            legacyBookkeepingKeys.forEach { preferences.remove(it) }
        }
    }
}
