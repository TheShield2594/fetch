package com.fetch.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
import com.fetch.tv.core.util.runSuspendCatching

/**
 * How the Library screen is shown: its sort order and the last tab picked. These used to live in
 * the library file next to the saved titles, so every tab change rewrote (and woke every observer
 * of) the file holding the whole library. They now have a file of their own; values left in the
 * library file are moved here the first time a profile reads them.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val migratedProfiles = ConcurrentHashMap.newKeySet<Int>()
    private val migrationLock = Mutex()

    private fun store(profileId: Int) =
        factory.get(profileId, FEATURE)

    val sortOption: Flow<String?> = observe(sortOptionKey)
    val lastSelectedList: Flow<String?> = observe(lastSelectedListKey)
    val lastSelectedType: Flow<String?> = observe(lastSelectedTypeKey)

    suspend fun setSortOption(key: String) = write(sortOptionKey, key)
    suspend fun setLastSelectedList(key: String) = write(lastSelectedListKey, key)
    suspend fun setLastSelectedType(key: String) = write(lastSelectedTypeKey, key)

    /**
     * The Show, Genre and Year filters last chosen on [profileId] (#851), as Discover keeps its
     * filters: read once when the Library opens on a profile. Unset ones read as null.
     */
    suspend fun getFilters(profileId: Int): StoredLibraryFilters {
        val preferences = store(profileId).data.first()
        return StoredLibraryFilters(
            watchedFilter = preferences[watchedFilterKey]?.takeIf { it.isNotBlank() },
            genre = preferences[genreKey]?.takeIf { it.isNotBlank() },
            year = preferences[yearKey]?.takeIf { it.isNotBlank() }
        )
    }

    /** Writes [fields] of [filters]; the other stored filters are left as they are. */
    suspend fun setFilters(
        filters: StoredLibraryFilters,
        fields: Set<StoredLibraryFilterField> = StoredLibraryFilterField.entries.toSet()
    ) {
        val profileId = profileManager.activeProfileId.value
        store(profileId).edit { preferences ->
            fun put(key: Preferences.Key<String>, value: String?) {
                if (value.isNullOrBlank()) preferences.remove(key) else preferences[key] = value
            }
            if (StoredLibraryFilterField.SHOW in fields) put(watchedFilterKey, filters.watchedFilter)
            if (StoredLibraryFilterField.GENRE in fields) put(genreKey, filters.genre)
            if (StoredLibraryFilterField.YEAR in fields) put(yearKey, filters.year)
        }
    }

    private fun observe(key: Preferences.Key<String>): Flow<String?> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            flow {
                migrateLegacy(profileId)
                emitAll(store(profileId).data.map { preferences -> preferences[key] })
            }
        }.distinctUntilChanged()

    private suspend fun write(key: Preferences.Key<String>, value: String) {
        val profileId = profileManager.activeProfileId.value
        migrateLegacy(profileId)
        store(profileId).edit { preferences -> preferences[key] = value }
    }

    /** Moves the view keys out of the library file once per profile. */
    private suspend fun migrateLegacy(profileId: Int) {
        if (profileId in migratedProfiles) return
        migrationLock.withLock {
            if (profileId in migratedProfiles) return
            val target = store(profileId)
            if (target.data.first()[migratedKey] != true) {
                val legacy = factory.get(profileId, LEGACY_FEATURE)
                val old = legacy.data.first()
                // Best effort: a deleted profile's stores refuse writes, and the view keys are
                // only a convenience. Reads still work, so try again next time.
                val moved = runSuspendCatching {
                    target.edit { preferences ->
                        VIEW_KEYS.forEach { key ->
                            val value = old[key]
                            if (value != null && preferences[key] == null) preferences[key] = value
                        }
                        preferences[migratedKey] = true
                    }
                    if (VIEW_KEYS.any { it in old }) {
                        legacy.edit { preferences -> VIEW_KEYS.forEach { preferences.remove(it) } }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (moved.isFailure) return
            }
            migratedProfiles += profileId
        }
    }

    internal companion object {
        const val FEATURE = "library_view_preferences"
        // The library file these keys used to live in (LibraryPreferences.FEATURE).
        const val LEGACY_FEATURE = "library_preferences"

        val sortOptionKey = stringPreferencesKey("library_sort_option")
        val lastSelectedListKey = stringPreferencesKey("library_last_selected_list")
        val lastSelectedTypeKey = stringPreferencesKey("library_last_selected_type")
        val watchedFilterKey = stringPreferencesKey("library_last_watched_filter")
        val genreKey = stringPreferencesKey("library_last_genre")
        val yearKey = stringPreferencesKey("library_last_year")
        private val migratedKey = booleanPreferencesKey("migrated_from_library_preferences")
        private val VIEW_KEYS = listOf(sortOptionKey, lastSelectedListKey, lastSelectedTypeKey)
    }
}

/** One of the Library's remembered filters (#851). */
enum class StoredLibraryFilterField { SHOW, GENRE, YEAR }

/** The Library's Show, Genre and Year filters as stored per profile (#851); null is "All". */
data class StoredLibraryFilters(
    val watchedFilter: String? = null,
    val genre: String? = null,
    val year: String? = null
)
