package com.fetch.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.domain.model.WatchedMutationKey
import com.fetch.tv.domain.model.mutationKey
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
 * (audit G5). The push and delta bookkeeping beside them is small and stays in DataStore.
 *
 * Older versions kept every entry as JSON in one DataStore string set. A profile's set is moved
 * into the store the first time the profile's items are read or written, then removed.
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

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()

    /** Where older versions kept the entries; read once by [ensureMigrated], then removed. */
    private val legacyWatchedItemsKey = stringSetPreferencesKey("watched_items")
    private val lastSuccessfulPushMsKey = longPreferencesKey("last_successful_watched_push_ms")
    private val deltaCursorKey = longPreferencesKey("watched_items_delta_cursor")
    private val deltaInitializedKey = booleanPreferencesKey("watched_items_delta_initialized")

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
            val legacy = store.data.first()[legacyWatchedItemsKey]
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

    suspend fun getLastSuccessfulPushMs(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = store(profileId).data.first()
        return prefs[lastSuccessfulPushMsKey] ?: 0L
    }

    /**
     * Advances the stored push point, never lowering it. The comparison happens inside
     * the edit, so two pushes finishing out of order cannot leave the older one on disk.
     * Nothing needs to lower it: deleting a profile removes the whole store.
     */
    suspend fun advanceLastSuccessfulPushMs(timestampMs: Long, profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { prefs ->
            val stored = prefs[lastSuccessfulPushMsKey] ?: 0L
            prefs[lastSuccessfulPushMsKey] = maxOf(stored, timestampMs)
        }
    }

    suspend fun getDeltaCursor(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = store(profileId).data.first()
        return prefs[deltaCursorKey] ?: 0L
    }

    suspend fun isDeltaInitialized(profileId: Int = profileManager.activeProfileId.value): Boolean {
        val prefs = store(profileId).data.first()
        return prefs[deltaInitializedKey] ?: false
    }

    suspend fun setDeltaState(cursor: Long, initialized: Boolean = true, profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { prefs ->
            prefs[deltaCursorKey] = cursor.coerceAtLeast(0L)
            prefs[deltaInitializedKey] = initialized
        }
        Log.d(TAG, "setDeltaState: profile=$profileId cursor=${cursor.coerceAtLeast(0L)} initialized=$initialized")
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
        profileId: Int = profileManager.activeProfileId.value
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
        profileId: Int = profileManager.activeProfileId.value
    ) = write(profileId) { items.upsert(profileId, listOf(item)) }

    suspend fun markAsWatchedBatch(
        items: List<WatchedItem>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (items.isEmpty()) return
        // The last entry for a key wins, as it did when the set was rewritten.
        val latest = items.associateBy { it.mutationKey() }.values.toList()
        write(profileId) { this.items.upsert(profileId, latest) }
    }

    suspend fun unmarkAsWatched(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        profileId: Int = profileManager.activeProfileId.value
    ) = write(profileId) { items.delete(profileId, listOf(WatchedMutationKey(contentId, season, episode))) }

    suspend fun unmarkAsWatchedBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (episodes.isEmpty()) return
        val keys = episodes.map { (s, e) -> WatchedMutationKey(contentId, s, e) }.toSet()
        write(profileId) { items.delete(profileId, keys) }
    }

    suspend fun getAllItems(profileId: Int = profileManager.activeProfileId.value): List<WatchedItem> {
        if (store(profileId) is DeletedProfileDataStore) return emptyList()
        ensureMigrated(profileId)
        return items.all(profileId)
    }

    /** Adds the remote entries this TV doesn't have; local entries win. */
    suspend fun mergeRemoteItems(remoteItems: List<WatchedItem>, profileId: Int = profileManager.activeProfileId.value) {
        if (remoteItems.isEmpty()) return
        write(profileId) { items.insertMissing(profileId, remoteItems) }
    }

    suspend fun applyRemoteChanges(
        upserts: List<WatchedItem>,
        deletes: List<Triple<String, Int?, Int?>>,
        pendingUpsertKeys: Set<WatchedMutationKey> = emptySet(),
        pendingDeleteKeys: Set<WatchedMutationKey> = emptySet(),
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (upserts.isEmpty() && deletes.isEmpty()) {
            Log.d(TAG, "applyRemoteChanges: no changes for profile $profileId")
            return
        }
        var beforeCount = 0
        var afterCount = 0
        write(profileId) {
            items.transform(profileId) { current ->
                beforeCount = current.size
                val itemsByKey = linkedMapOf<WatchedMutationKey, WatchedItem>()
                current.forEach { item -> itemsByKey[item.mutationKey()] = item }
                deletes.forEach { (contentId, season, episode) ->
                    val mutationKey = WatchedMutationKey(contentId, season, episode)
                    // A local change not yet pushed wins over the remote delete.
                    if (mutationKey !in pendingUpsertKeys) itemsByKey.remove(mutationKey)
                }
                upserts.forEach { item ->
                    val mutationKey = item.mutationKey()
                    when {
                        mutationKey in pendingDeleteKeys -> itemsByKey.remove(mutationKey)
                        mutationKey !in pendingUpsertKeys -> itemsByKey[mutationKey] = item
                    }
                }
                afterCount = itemsByKey.size
                itemsByKey.values.toList()
            }
        }
        Log.d(TAG, "applyRemoteChanges: profile=$profileId before=$beforeCount after=$afterCount upserts=${upserts.size} deletes=${deletes.size}")
    }

    suspend fun replaceWithRemoteItems(
        remoteItems: List<WatchedItem>,
        pendingUpsertKeys: Set<WatchedMutationKey> = emptySet(),
        pendingDeleteKeys: Set<WatchedMutationKey> = emptySet(),
        lastSuccessfulPushMs: Long? = null,
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean {
        var preservedLocalItems = false
        write(profileId) {
            items.transform(profileId) { localItems ->
                Log.d(TAG, "replaceWithRemoteItems: profile=$profileId current=${localItems.size} remote=${remoteItems.size}")
                val deduped = linkedMapOf<WatchedMutationKey, WatchedItem>()
                remoteItems.filterNot { it.mutationKey() in pendingDeleteKeys }.forEach { item ->
                    deduped[item.mutationKey()] = item
                }
                localItems.forEach { localItem ->
                    val itemKey = localItem.mutationKey()
                    if (ServerItemRef.isServerId(localItem.contentId)) {
                        deduped[itemKey] = localItem
                        return@forEach
                    }
                    val preservePendingUpsert = itemKey in pendingUpsertKeys
                    val preserveAfterPush = itemKey !in pendingDeleteKeys &&
                        itemKey !in deduped &&
                        lastSuccessfulPushMs != null &&
                        localItem.watchedAt > lastSuccessfulPushMs
                    if (preservePendingUpsert || preserveAfterPush) {
                        deduped[itemKey] = localItem
                        preservedLocalItems = true
                    }
                }
                Log.d(TAG, "replaceWithRemoteItems: profile=$profileId stored=${deduped.size} preservedLocal=$preservedLocalItems")
                deduped.values.toList()
            }
        }
        return preservedLocalItems
    }

    suspend fun clearAll(profileId: Int = profileManager.activeProfileId.value) {
        write(profileId) { items.clear(profileId) }
        store(profileId).edit { preferences ->
            preferences.remove(legacyWatchedItemsKey)
            preferences.remove(deltaCursorKey)
            preferences.remove(deltaInitializedKey)
        }
    }
}
