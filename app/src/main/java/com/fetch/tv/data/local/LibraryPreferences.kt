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
import com.fetch.tv.domain.model.LibrarySyncKey
import com.fetch.tv.domain.model.LibrarySyncReducer
import com.fetch.tv.domain.model.LibrarySyncState
import com.fetch.tv.domain.model.SavedLibraryItem
import com.fetch.tv.domain.model.librarySyncIdentity
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
 * The profile's own library ("Add to library") and its queue of changes waiting to sync. Items,
 * queued keys and the sync counters are rows of [entries] (SQLite), changed together in one
 * transaction through [LibrarySyncReducer], and only the rows a change touches are written
 * (audit G5). Older versions kept all of it as JSON sets in the library DataStore file; a
 * profile's sets are moved the first time its library is used.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    @LibraryEntries private val entries: KeyedEntryStore
) {
    private val gson = Gson()

    // Where older versions kept the library; read once by ensureMigrated, then removed. The file
    // stays: LibraryViewPreferences moves its own keys out of it.
    private val legacyLibraryItemsKey = stringSetPreferencesKey("library_items")
    private val legacyDeltaCursorKey = longPreferencesKey("library_delta_cursor")
    private val legacyDeltaInitializedKey = booleanPreferencesKey("library_delta_initialized")
    private val legacyPendingUpsertKeysKey = stringSetPreferencesKey("library_pending_upserts")
    private val legacyPendingDeleteKeysKey = stringSetPreferencesKey("library_pending_deletes")
    private val legacyMutationRevisionKey = longPreferencesKey("library_mutation_revision")
    private val legacyKeys: List<Preferences.Key<*>> = listOf(
        legacyLibraryItemsKey, legacyDeltaCursorKey, legacyDeltaInitializedKey,
        legacyPendingUpsertKeysKey, legacyPendingDeleteKeysKey, legacyMutationRevisionKey
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
            observeRows(profileId).map { rows -> profileId to rows.toSyncState(profileId).items }
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
        return getSyncState(profileId).items.any { item ->
            item.id == itemId && item.type.equals(itemType, ignoreCase = true)
        }
    }

    suspend fun addItem(
        item: SavedLibraryItem,
        profileId: Int
    ) = edit(profileId) { state ->
        LibrarySyncReducer.upsertLocal(state = state, item = item, nowEpochMs = System.currentTimeMillis())
    }

    suspend fun removeItem(
        itemId: String,
        itemType: String,
        profileId: Int
    ) = edit(profileId) { state ->
        LibrarySyncReducer.deleteLocal(state = state, contentId = itemId, contentType = itemType)
    }

    suspend fun getAllItems(
        profileId: Int
    ): List<SavedLibraryItem> {
        return getSyncState(profileId).items
    }

    suspend fun updateLogo(
        id: String,
        type: String,
        logo: String,
        profileId: Int
    ) = edit(profileId) { state ->
        state.copy(
            items = state.items.map { item ->
                if (item.id == id && item.type.equals(type, ignoreCase = true)) item.copy(logo = logo) else item
            }
        )
    }

    /** The whole sync state: items, queued changes and counters. */
    internal suspend fun getSyncState(profileId: Int): LibrarySyncState {
        if (isDeleted(profileId)) return LibrarySyncState()
        ensureMigrated(profileId)
        return entries.all(profileId).toSyncState(profileId)
    }

    /**
     * Applies [transform] to the stored state in one transaction and writes only the rows that
     * differ. A deleted profile refuses, as its DataStore does.
     */
    private suspend fun edit(profileId: Int, transform: (LibrarySyncState) -> LibrarySyncState) {
        if (isDeleted(profileId)) throw CancellationException("Profile $profileId was deleted")
        ensureMigrated(profileId)
        entries.transaction {
            val stored = entries.all(profileId)
            val updated = LibrarySyncReducer.sanitize(transform(stored.toSyncState(profileId)))
            val before = stored.groupBy { it.kind }.mapValues { (_, rows) -> rows.associateBy { it.key } }
            val after = updated.toEntries().groupBy { it.kind }.mapValues { (_, rows) -> rows.associateBy { it.key } }
            (before.keys + after.keys).forEach { kind ->
                entries.writeChanges(profileId, kind, before[kind].orEmpty(), after[kind].orEmpty())
            }
        }
    }

    /**
     * Moves [profileId]'s library out of the legacy DataStore sets, once per process. Rows are
     * inserted before the sets are removed, and a row already moved wins, so a move cut short
     * finishes on the next start without losing anything.
     */
    private suspend fun ensureMigrated(profileId: Int) {
        if (profileId in migratedProfiles) return
        migrationMutex.withLock {
            if (profileId in migratedProfiles) return
            if (isDeleted(profileId)) return
            val store = store(profileId)
            val prefs = store.data.first()
            if (legacyKeys.any { it in prefs }) {
                val state = LibrarySyncReducer.sanitize(
                    LibrarySyncState(
                        items = prefs[legacyLibraryItemsKey].orEmpty().mapNotNull { parse(it, SavedLibraryItem::class.java) },
                        deltaCursorEventId = prefs[legacyDeltaCursorKey] ?: 0L,
                        deltaInitialized = prefs[legacyDeltaInitializedKey] ?: false,
                        pendingUpsertKeys = prefs[legacyPendingUpsertKeysKey].orEmpty().mapNotNull { parse(it, LibrarySyncKey::class.java) },
                        pendingDeleteKeys = prefs[legacyPendingDeleteKeysKey].orEmpty().mapNotNull { parse(it, LibrarySyncKey::class.java) },
                        mutationRevision = prefs[legacyMutationRevisionKey] ?: 0L
                    )
                )
                entries.insertMissing(profileId, state.toEntries())
                store.edit { preferences -> legacyKeys.forEach { preferences.remove(it) } }
                Log.i(
                    TAG,
                    "Moved ${state.items.size} library items and " +
                        "${state.pendingUpsertKeys.size + state.pendingDeleteKeys.size} queued changes of profile $profileId to the database"
                )
            }
            migratedProfiles += profileId
        }
    }

    private fun LibrarySyncState.toEntries(): List<KeyedEntry> = buildList {
        items.forEach { item ->
            add(KeyedEntry(KIND_ITEM, librarySyncIdentity(item.id, item.type), item.id, item.addedAt, gson.toJson(item)))
        }
        pendingUpsertKeys.forEach { key -> add(key.entry(KIND_PENDING_UPSERT)) }
        pendingDeleteKeys.forEach { key -> add(key.entry(KIND_PENDING_DELETE)) }
        add(
            KeyedEntry(
                kind = KIND_META,
                key = META_KEY,
                contentId = "",
                sortTime = 0L,
                json = encodeSyncCounters(deltaCursorEventId, deltaInitialized, mutationRevision)
            )
        )
    }

    private fun LibrarySyncKey.entry(kind: String) =
        KeyedEntry(kind, librarySyncIdentity(contentId, contentType), contentId, 0L, gson.toJson(this))

    private fun List<KeyedEntry>.toSyncState(profileId: Int): LibrarySyncState {
        val byKind = groupBy { it.kind }
        val counters = byKind[KIND_META]?.firstOrNull()?.json?.let(::decodeSyncCounters)
        return LibrarySyncReducer.sanitize(
            LibrarySyncState(
                items = decodeItems(profileId, byKind[KIND_ITEM].orEmpty()),
                deltaCursorEventId = counters?.first ?: 0L,
                deltaInitialized = counters?.second ?: false,
                pendingUpsertKeys = byKind[KIND_PENDING_UPSERT].orEmpty().mapNotNull { parse(it.json, LibrarySyncKey::class.java) },
                pendingDeleteKeys = byKind[KIND_PENDING_DELETE].orEmpty().mapNotNull { parse(it.json, LibrarySyncKey::class.java) },
                mutationRevision = counters?.third ?: 0L
            )
        )
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
        const val KIND_PENDING_UPSERT = "pending_upsert"
        const val KIND_PENDING_DELETE = "pending_delete"
        const val KIND_META = "meta"
        const val META_KEY = "sync"

        /**
         * The delta cursor, whether it's set, and the mutation revision, as "cursor|initialized|revision".
         * Plain text rather than a JSON class, so shrinking a release build can't rename its fields.
         */
        fun encodeSyncCounters(cursor: Long, initialized: Boolean, revision: Long): String =
            "$cursor|$initialized|$revision"

        fun decodeSyncCounters(text: String): Triple<Long, Boolean, Long>? {
            val parts = text.split('|')
            if (parts.size != 3) return null
            val cursor = parts[0].toLongOrNull() ?: return null
            val initialized = parts[1].toBooleanStrictOrNull() ?: return null
            val revision = parts[2].toLongOrNull() ?: return null
            return Triple(cursor, initialized, revision)
        }
    }
}
