package com.fetch.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tracking.LocalList
import com.fetch.tv.core.tracking.LocalListsReducer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The active profile's local lists, in their own per-profile file so a profile's lists go
 * with it when it is deleted and stay put across app updates and tracking sign-ins.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LocalListsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val listsKey = stringPreferencesKey("local_lists_json")
    private val listsType = object : TypeToken<List<LocalList?>>() {}.type

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    val lists: Flow<List<LocalList>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { preferences -> preferences.decodeLists() }
    }.distinctUntilChanged()

    suspend fun getLists(profileId: Int = profileManager.activeProfileId.value): List<LocalList> =
        store(profileId).data.first().decodeLists()

    suspend fun update(
        profileId: Int = profileManager.activeProfileId.value,
        transform: (List<LocalList>) -> List<LocalList>
    ) {
        store(profileId).edit { preferences ->
            val updated = LocalListsReducer.sanitize(transform(preferences.decodeLists()))
            if (updated.isEmpty()) {
                preferences.remove(listsKey)
            } else {
                preferences[listsKey] = gson.toJson(updated)
            }
        }
    }

    private fun Preferences.decodeLists(): List<LocalList> {
        val json = this[listsKey] ?: return emptyList()
        val decoded = runCatching { gson.fromJson<List<LocalList?>>(json, listsType) }.getOrNull()
        return LocalListsReducer.sanitize(decoded)
    }

    private companion object {
        const val FEATURE = "local_lists"
    }
}
