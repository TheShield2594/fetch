package com.fetch.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscoverSelectionDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE = "discover_selection"
        val selectedType = stringPreferencesKey("selected_type")

        const val SORT = "sort"
        const val MIN_RATING = "min_rating"
        const val YEAR_RANGE = "year_range"
        const val GENRE = "genre"
        const val HIDE_WATCHED = "hide_watched"
        const val INCLUDE_UNRATED = "include_unrated"

        /** Written with a type's filters, so a type stored with every filter at its default counts as stored. */
        const val STORED_MARKER = "stored"

        /** A filter's key for one type (#857), e.g. "sort:series"; the bare key is the one once shared by every type. */
        fun key(name: String, type: String?) =
            stringPreferencesKey(if (type.isNullOrBlank()) name else "$name:${type.trim().lowercase()}")
    }

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    /** The content type Discover last browsed, e.g. "movie" or "series". */
    suspend fun getSelectedType(): String? =
        store(profileManager.currentProfileId()).data.first()[selectedType]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    suspend fun setSelectedType(type: String) {
        val normalized = type.trim()
        if (normalized.isEmpty()) return
        store(profileManager.currentProfileId()).edit { preferences ->
            preferences[selectedType] = normalized
        }
    }

    /**
     * Discover's filters as last chosen on this profile for [type] (#851, #857), so it opens as the
     * Library does, on the view left. Stored as plain strings: a sort's name, a minimum rating, a
     * year range as "from..to" with an empty side for an open bound, the genre and two toggles. A
     * type not stored since #857 starts from the filters once shared by every type.
     */
    suspend fun getFilters(type: String? = null): StoredDiscoverFilters {
        val preferences = store(profileManager.currentProfileId()).data.first()
        val stored = type?.takeIf { preferences[key(STORED_MARKER, it)] != null }
        fun read(name: String): String? = preferences[key(name, stored)]
        return StoredDiscoverFilters(
            sort = read(SORT),
            minRating = read(MIN_RATING)?.toIntOrNull(),
            yearRange = read(YEAR_RANGE),
            genre = read(GENRE)?.takeIf { it.isNotBlank() },
            hideWatched = read(HIDE_WATCHED) == "1",
            includeUnrated = read(INCLUDE_UNRATED) == "1"
        )
    }

    suspend fun setFilters(filters: StoredDiscoverFilters, type: String? = null) {
        store(profileManager.currentProfileId()).edit { preferences ->
            fun write(name: String, value: String?) {
                val k = key(name, type)
                if (value != null) preferences[k] = value else preferences.remove(k)
            }
            write(SORT, filters.sort)
            write(MIN_RATING, filters.minRating?.toString())
            write(YEAR_RANGE, filters.yearRange)
            write(GENRE, filters.genre)
            write(HIDE_WATCHED, if (filters.hideWatched) "1" else null)
            write(INCLUDE_UNRATED, if (filters.includeUnrated) "1" else null)
            if (type != null) preferences[key(STORED_MARKER, type)] = "1"
        }
    }
}

/** Discover's filters as stored; the screen maps them to and from its own types. */
data class StoredDiscoverFilters(
    val sort: String? = null,
    val minRating: Int? = null,
    val yearRange: String? = null,
    val genre: String? = null,
    val hideWatched: Boolean = false,
    val includeUnrated: Boolean = false
)
