package com.fetch.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tracking.LocalList
import com.fetch.tv.core.tracking.LocalListsReducer
import com.fetch.tv.core.tracking.LocalListKind
import com.fetch.tv.domain.model.SavedLibraryItem
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    val lists: Flow<List<LocalList>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { preferences -> preferences.decodeLists() }
    }.distinctUntilChanged()

    /** One profile's lists, whichever profile is active; a kids profile reads the parent's this way. */
    fun listsFor(profileId: Int): Flow<List<LocalList>> =
        store(profileId).data.map { preferences -> preferences.decodeLists() }.distinctUntilChanged()

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
        return LocalListsReducer.sanitize(LocalListsJson.decode(gson, json))
    }

    private companion object {
        const val FEATURE = "local_lists"
    }
}

/**
 * Reads the stored lists, including those a minified build wrote before [LocalList] was kept:
 * R8 had renamed its fields to short names in declaration order, so such an entry is read back
 * by the order of its keys and the type of each value. [SavedLibraryItem] always kept its names.
 */
internal object LocalListsJson {
    private val itemsType = object : TypeToken<List<SavedLibraryItem?>>() {}.type
    private val knownKinds = setOf(LocalListKind.WATCHLIST, LocalListKind.COLLECTION, LocalListKind.CUSTOM)

    fun decode(gson: Gson, json: String): List<LocalList?> {
        val array = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
        return array.map { element ->
            val entry = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@map null
            runCatching {
                if (entry.keySet().all { it.length <= 2 } && !entry.has("id")) {
                    decodeLegacy(gson, entry)
                } else {
                    gson.fromJson(entry, LocalList::class.java)
                }
            }.getOrNull()
        }
    }

    private fun decodeLegacy(gson: Gson, entry: JsonObject): LocalList? {
        val fields = entry.entrySet().sortedWith(compareBy({ it.key.length }, { it.key }))
        // id, name, kind, description; description is absent when it was null.
        val strings = fields.map { it.value }
            .filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            .map(JsonElement::getAsString)
        // createdAt, updatedAt.
        val numbers = fields.map { it.value }
            .filter { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            .map(JsonElement::getAsLong)
        val itemsJson = fields.map { it.value }.singleOrNull(JsonElement::isJsonArray)
        if (strings.size !in 3..4) return null
        val items: List<SavedLibraryItem?> = itemsJson
            ?.let { gson.fromJson<List<SavedLibraryItem?>>(it, itemsType) }
            .orEmpty()
        return LocalList(
            id = strings[0],
            name = strings[1],
            kind = strings[2].takeIf { it in knownKinds } ?: LocalListKind.CUSTOM,
            description = strings.getOrNull(3),
            createdAt = numbers.getOrNull(0) ?: 0L,
            updatedAt = numbers.getOrNull(1) ?: numbers.getOrNull(0) ?: 0L,
            items = items.filterNotNull()
        )
    }
}
