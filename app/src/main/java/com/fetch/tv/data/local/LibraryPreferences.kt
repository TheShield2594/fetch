package com.fetch.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.SavedLibraryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The profile's own library ("Add to library"). Each saved title is a row of [entries] (SQLite),
 * and only the rows a change touches are written (audit G5). Older versions kept the titles as a
 * JSON set in the library DataStore file; a profile's set is moved the first time its library is used.
 *
 * Older versions also kept a queue of changes waiting to sync and its counters, next to the titles
 * in both places. Nothing reads them: they're removed when the library is first used.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    @LibraryEntries private val entries: KeyedEntryStore
) {
    private val gson = Gson()

    // Where older versions kept the library; read once by ensureMigrated, then removed with the
    // old sync keys beside it. The file stays: LibraryViewPreferences moves its own keys out of it.
    private val legacyLibraryItemsKey = stringSetPreferencesKey("library_items")
    private val legacyKeys: List<Preferences.Key<*>> = listOf(
        legacyLibraryItemsKey,
        longPreferencesKey("library_delta_cursor"),
        booleanPreferencesKey("library_delta_initialized"),
        stringSetPreferencesKey("library_pending_upserts"),
        stringSetPreferencesKey("library_pending_deletes"),
        longPreferencesKey("library_mutation_revision")
    )

    // Holds the one shared decode of the active profile's library while anything observes it.
    private val sharingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Decoded items per profile, keyed by their stored JSON: one add or remove re-parses only the
    // changed entry, not the whole library. Entries that fail to parse are cached as null.
    private val decodeCaches = ConcurrentHashMap<Int, AtomicReference<Map<String, SavedLibraryItem?>>>()

    private val migratedProfiles: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val migrationMutex = Mutex()

    private fun store(profileId: Int) =
        factory.get(profileId, FEATURE)

    private fun isDeleted(profileId: Int) = store(profileId) is DeletedProfileDataStore

    // The shared decode, tagged with its profile so a late subscriber never sees the previous
    // profile's library while the switch is still being decoded.
    private val sharedLibraryItems: Flow<Pair<Int, List<SavedLibraryItem>>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            observeRows(profileId).map { rows -> profileId to rows.toItems(profileId) }
        }
            .flowOn(Dispatchers.Default)
            .shareIn(
                sharingScope,
                SharingStarted.WhileSubscribed(
                    stopTimeoutMillis = SHARE_STOP_TIMEOUT_MS,
                    replayExpirationMillis = 0
                ),
                replay = 1
            )

    /**
     * The saved items, decoded off the main thread and shared by every observer (the Library,
     * each open detail page's "in library" state, Home), so a change is decoded once.
     */
    val libraryItems: Flow<List<SavedLibraryItem>> = sharedLibraryItems
        .filter { (profileId, _) -> profileId == profileManager.activeProfileId.value }
        .map { (_, items) -> items }

    private fun observeRows(profileId: Int): Flow<List<KeyedEntry>> = flow {
        if (isDeleted(profileId)) {
            emit(emptyList())
            return@flow
        }
        ensureMigrated(profileId)
        emitAll(entries.observe(profileId))
    }

    fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        return libraryItems.map { items ->
            items.any { item ->
                item.id == itemId && item.type.equals(itemType, ignoreCase = true)
            }
        }.distinctUntilChanged()
    }

    suspend fun containsItem(
        itemId: String,
        itemType: String,
        profileId: Int
    ): Boolean {
        return getAllItems(profileId).any { item ->
            item.id == itemId && item.type.equals(itemType, ignoreCase = true)
        }
    }

    suspend fun addItem(
        item: SavedLibraryItem,
        profileId: Int
    ) = edit(profileId) { items ->
        if (item.id.isBlank() || item.type.isBlank()) return@edit items
        val stored = if (item.addedAt == 0L) item.copy(addedAt = System.currentTimeMillis()) else item
        // Replaces a saved copy of the title; sanitize keeps the last one of each key.
        items + stored
    }

    suspend fun removeItem(
        itemId: String,
        itemType: String,
        profileId: Int
    ) = edit(profileId) { items ->
        val key = libraryItemKey(itemId, itemType)
        items.filterNot { libraryItemKey(it.id, it.type) == key }
    }

    suspend fun getAllItems(
        profileId: Int
    ): List<SavedLibraryItem> {
        if (isDeleted(profileId)) return emptyList()
        ensureMigrated(profileId)
        return entries.all(profileId).toItems(profileId)
    }

    suspend fun updateLogo(
        id: String,
        type: String,
        logo: String,
        profileId: Int
    ) = edit(profileId) { items ->
        items.map { item ->
            if (item.id == id && item.type.equals(type, ignoreCase = true)) item.copy(logo = logo) else item
        }
    }

    /**
     * Applies [transform] to the saved items in one transaction and writes only the rows that
     * differ. A deleted profile refuses, as its DataStore does.
     */
    private suspend fun edit(profileId: Int, transform: (List<SavedLibraryItem>) -> List<SavedLibraryItem>) {
        if (isDeleted(profileId)) throw CancellationException("Profile $profileId was deleted")
        ensureMigrated(profileId)
        entries.transaction {
            val stored = entries.all(profileId).filter { it.kind == KIND_ITEM }
            val updated = sanitize(transform(stored.toItems(profileId)))
            entries.writeChanges(
                profileId,
                KIND_ITEM,
                stored.associateBy { it.key },
                updated.map { it.toEntry() }.associateBy { it.key }
            )
        }
    }

    /**
     * Moves [profileId]'s library out of the legacy DataStore set, once per process, and drops the
     * old sync queue and counters from DataStore and the database. Rows are inserted before the
     * set is removed, and a row already moved wins, so a move cut short finishes on the next start
     * without losing anything.
     */
    private suspend fun ensureMigrated(profileId: Int) {
        if (profileId in migratedProfiles) return
        migrationMutex.withLock {
            if (profileId in migratedProfiles) return
            if (isDeleted(profileId)) return
            val store = store(profileId)
            val prefs = store.data.first()
            if (legacyKeys.any { it in prefs }) {
                val items = sanitize(
                    prefs[legacyLibraryItemsKey].orEmpty().mapNotNull { parse(it, SavedLibraryItem::class.java) }
                )
                entries.insertMissing(profileId, items.map { it.toEntry() })
                store.edit { preferences -> legacyKeys.forEach { preferences.remove(it) } }
                Log.i(TAG, "Moved ${items.size} library items of profile $profileId to the database")
            }
            LEGACY_SYNC_KINDS.forEach { entries.clearKind(profileId, it) }
            migratedProfiles += profileId
        }
    }

    private fun SavedLibraryItem.toEntry() =
        KeyedEntry(KIND_ITEM, libraryItemKey(id, type), id, addedAt, gson.toJson(this))

    private fun List<KeyedEntry>.toItems(profileId: Int): List<SavedLibraryItem> =
        sanitize(decodeItems(profileId, filter { it.kind == KIND_ITEM }))

    /** Drops titles without an id or type, and keeps one copy of each title, the last. */
    private fun sanitize(items: List<SavedLibraryItem>): List<SavedLibraryItem> {
        val byKey = LinkedHashMap<String, SavedLibraryItem>()
        items.forEach { item ->
            if (item.id.isNotBlank() && item.type.isNotBlank()) byKey[libraryItemKey(item.id, item.type)] = item
        }
        return byKey.values.toList()
    }

    private fun decodeItems(profileId: Int, rows: List<KeyedEntry>): List<SavedLibraryItem> {
        val cache = decodeCaches.getOrPut(profileId) { AtomicReference(emptyMap()) }
        val previous = cache.get()
        val decoded = HashMap<String, SavedLibraryItem?>(rows.size * 2)
        val items = ArrayList<SavedLibraryItem>(rows.size)
        // Oldest first, as the set they came from kept no order a caller relied on.
        rows.sortedBy { it.sortTime }.forEach { row ->
            val item = if (previous.containsKey(row.json)) previous[row.json] else parse(row.json, SavedLibraryItem::class.java)
            decoded[row.json] = item
            if (item != null) items += item
        }
        cache.set(decoded)
        return items
    }

    private fun <T> parse(json: String, type: Class<T>): T? = try {
        gson.fromJson(json, type)
    } catch (e: JsonParseException) {
        null
    }

    internal companion object {
        const val FEATURE = "library_preferences"
        const val SHARE_STOP_TIMEOUT_MS = 5_000L
        private const val TAG = "LibraryPreferences"

        const val KIND_ITEM = "item"

        /** Rows older versions kept for the sync queue and its counters; removed, never read. */
        val LEGACY_SYNC_KINDS = listOf("pending_upsert", "pending_delete", "meta")

        /** A title's row key, as older versions stored it: `type:id`, type in lower case. */
        fun libraryItemKey(contentId: String, contentType: String): String =
            "${contentType.trim().lowercase()}:${contentId.trim()}"
    }
}
