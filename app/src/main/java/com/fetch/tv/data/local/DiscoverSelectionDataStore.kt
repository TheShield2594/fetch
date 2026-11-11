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
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    /** The content type Discover last browsed, e.g. "movie" or "series". */
    suspend fun getSelectedType(): String? =
        store().data.first()[selectedType]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    suspend fun setSelectedType(type: String) {
        val normalized = type.trim()
        if (normalized.isEmpty()) return
        store().edit { preferences ->
            preferences[selectedType] = normalized
        }
    }
}
