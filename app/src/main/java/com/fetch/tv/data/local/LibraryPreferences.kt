package com.fetch.tv.data.local

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.LibraryDeltaApplyResult
import com.fetch.tv.domain.model.LibraryDeltaEvent
import com.fetch.tv.domain.model.LibrarySnapshotApplyResult
import com.fetch.tv.domain.model.LibrarySyncKey
import com.fetch.tv.domain.model.LibrarySyncReducer
import com.fetch.tv.domain.model.LibrarySyncState
import com.fetch.tv.domain.model.SavedLibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val libraryItemsKey = stringSetPreferencesKey("library_items")
    private val deltaCursorKey = longPreferencesKey("library_delta_cursor")
    private val deltaInitializedKey = booleanPreferencesKey("library_delta_initialized")
    private val pendingUpsertKeysKey = stringSetPreferencesKey("library_pending_upserts")
    private val pendingDeleteKeysKey = stringSetPreferencesKey("library_pending_deletes")
    private val mutationRevisionKey = longPreferencesKey("library_mutation_revision")

    // Holds the one shared decode of the active profile's library while anything observes it.
    private val sharingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Decoded items per profile, keyed by their stored JSON, as in WatchedItemsPreferences: one
    // add or remove re-parses only the changed entry, not the whole library. Values depend only
    // on the key, so a lost update only costs cache hits. Entries that fail to parse are cached
    // as null so they are only parsed once.
    private val decodeCaches = ConcurrentHashMap<Int, AtomicReference<Map<String, SavedLibraryItem?>>>()

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    // The shared decode, tagged with its profile so a late subscriber never sees the previous
    // profile's library while the switch is still being decoded.
    private val sharedLibraryItems: Flow<Pair<Int, List<SavedLibraryItem>>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data
                .distinctUntilChanged { old, new -> old.librarySyncFingerprint() == new.librarySyncFingerprint() }
                .map { preferences -> profileId to preferences.toLibrarySyncState(profileId).items }
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
     * each open detail page's "in library" state, Home), so a change is decoded once. The stored
     * sets are compared first, so writes that leave them unchanged emit nothing and re-parse
     * nothing.
     */
    val libraryItems: Flow<List<SavedLibraryItem>> = sharedLibraryItems
        .filter { (profileId, _) -> profileId == profileManager.activeProfileId.value }
        .map { (_, items) -> items }

    /** Everything [toLibrarySyncState] reads. Equal fingerprints decode to equal states. */
    private fun Preferences.librarySyncFingerprint(): List<Any?> = listOf(
        this[libraryItemsKey],
        this[pendingUpsertKeysKey],
        this[pendingDeleteKeysKey],
        this[deltaCursorKey],
        this[deltaInitializedKey],
        this[mutationRevisionKey]
    )

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
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean {
        return getSyncState(profileId).items.any { item ->
            item.id == itemId && item.type.equals(itemType, ignoreCase = true)
        }
    }

    suspend fun addItem(
        item: SavedLibraryItem,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            val updated = LibrarySyncReducer.upsertLocal(
                state = preferences.toLibrarySyncState(profileId),
                item = item,
                nowEpochMs = System.currentTimeMillis()
            )
            preferences.writeLibrarySyncState(updated)
        }
    }

    suspend fun removeItem(
        itemId: String,
        itemType: String,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            val updated = LibrarySyncReducer.deleteLocal(
                state = preferences.toLibrarySyncState(profileId),
                contentId = itemId,
                contentType = itemType
            )
            preferences.writeLibrarySyncState(updated)
        }
    }

    suspend fun getAllItems(
        profileId: Int = profileManager.activeProfileId.value
    ): List<SavedLibraryItem> {
        return getSyncState(profileId).items
    }

    suspend fun updateLogo(
        id: String,
        type: String,
        logo: String,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            val state = preferences.toLibrarySyncState(profileId)
            val updatedItems = state.items.map { item ->
                if (item.id == id && item.type.equals(type, ignoreCase = true)) {
                    item.copy(logo = logo)
                } else {
                    item
                }
            }
            preferences.writeLibrarySyncState(state.copy(items = updatedItems))
        }
    }

    private suspend fun getSyncState(profileId: Int): LibrarySyncState {
        return store(profileId).data.first().toLibrarySyncState(profileId)
    }

    private fun Preferences.toLibrarySyncState(profileId: Int): LibrarySyncState {
        return LibrarySyncReducer.sanitize(
            LibrarySyncState(
                items = decodeItems(profileId, this[libraryItemsKey].orEmpty()),
                deltaCursorEventId = this[deltaCursorKey] ?: 0L,
                deltaInitialized = this[deltaInitializedKey] ?: false,
                pendingUpsertKeys = decodeSet(pendingUpsertKeysKey, LibrarySyncKey::class.java),
                pendingDeleteKeys = decodeSet(pendingDeleteKeysKey, LibrarySyncKey::class.java),
                mutationRevision = this[mutationRevisionKey] ?: 0L
            )
        )
    }

    private fun MutablePreferences.writeLibrarySyncState(state: LibrarySyncState) {
        val sanitized = LibrarySyncReducer.sanitize(state)
        this[libraryItemsKey] = sanitized.items.map(gson::toJson).toSet()
        this[deltaCursorKey] = sanitized.deltaCursorEventId
        this[deltaInitializedKey] = sanitized.deltaInitialized
        this[pendingUpsertKeysKey] = sanitized.pendingUpsertKeys.map(gson::toJson).toSet()
        this[pendingDeleteKeysKey] = sanitized.pendingDeleteKeys.map(gson::toJson).toSet()
        this[mutationRevisionKey] = sanitized.mutationRevision
    }

    private fun decodeItems(profileId: Int, raw: Set<String>): List<SavedLibraryItem> {
        val cache = decodeCaches.getOrPut(profileId) { AtomicReference(emptyMap()) }
        val previous = cache.get()
        val decoded = HashMap<String, SavedLibraryItem?>(raw.size * 2)
        val items = ArrayList<SavedLibraryItem>(raw.size)
        raw.forEach { json ->
            val item = if (previous.containsKey(json)) {
                previous[json]
            } else {
                runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }.getOrNull()
            }
            decoded[json] = item
            if (item != null) items += item
        }
        cache.set(decoded)
        return items
    }

    private fun <T> Preferences.decodeSet(
        key: Preferences.Key<Set<String>>,
        type: Class<T>
    ): List<T> {
        return this[key].orEmpty().mapNotNull { value ->
            runCatching { gson.fromJson(value, type) }.getOrNull()
        }
    }

    private companion object {
        const val FEATURE = "library_preferences"
        const val SHARE_STOP_TIMEOUT_MS = 5_000L
    }
}
