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
        val sortKey = stringPreferencesKey("sort")
        val minRatingKey = stringPreferencesKey("min_rating")
        val yearRangeKey = stringPreferencesKey("year_range")
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
     * Discover's sort, rating and year filters as last chosen on this profile (#851), so it opens
     * as the Library does, on the view left. Stored as plain strings: a sort's name, a minimum
     * rating, and a year range as "from..to" with an empty side for an open bound.
     */
    suspend fun getFilters(): StoredDiscoverFilters {
        val preferences = store(profileManager.currentProfileId()).data.first()
        return StoredDiscoverFilters(
            sort = preferences[sortKey],
            minRating = preferences[minRatingKey]?.toIntOrNull(),
            yearRange = preferences[yearRangeKey]
        )
    }

    suspend fun setFilters(filters: StoredDiscoverFilters) {
        store(profileManager.currentProfileId()).edit { preferences ->
            if (filters.sort != null) preferences[sortKey] = filters.sort else preferences.remove(sortKey)
            if (filters.minRating != null) preferences[minRatingKey] = filters.minRating.toString() else preferences.remove(minRatingKey)
            if (filters.yearRange != null) preferences[yearRangeKey] = filters.yearRange else preferences.remove(yearRangeKey)
        }
    }
}

/** Discover's filters as stored; the screen maps them to and from its own types. */
data class StoredDiscoverFilters(
    val sort: String? = null,
    val minRating: Int? = null,
    val yearRange: String? = null
)
