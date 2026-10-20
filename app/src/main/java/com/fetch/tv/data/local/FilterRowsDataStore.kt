package com.fetch.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.FilterRow
import com.fetch.tv.domain.model.FilterRowFilters
import com.fetch.tv.domain.model.FilterRowSort
import com.fetch.tv.domain.model.FilterRowSource
import com.fetch.tv.domain.model.WatchedFilter
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Per-profile filter rows. They point at local servers and addons, so they are not synced. */
@Singleton
class FilterRowsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val rowsKey = stringPreferencesKey("filter_rows_json")

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    val rows: Flow<List<FilterRow>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { prefs -> parse(prefs[rowsKey]) }
    }

    suspend fun row(id: String): FilterRow? = rows.first().firstOrNull { it.id == id }

    /** False when the row could not be written. */
    suspend fun save(row: FilterRow): Boolean =
        edit { rows ->
            val index = rows.indexOfFirst { it.id == row.id }
            if (index >= 0) rows.toMutableList().apply { set(index, row) } else rows + row
        }

    /** False when the row could not be removed. */
    suspend fun remove(id: String): Boolean = edit { rows -> rows.filterNot { it.id == id } }

    private suspend fun edit(transform: (List<FilterRow>) -> List<FilterRow>): Boolean = try {
        store().edit { prefs ->
            val updated = transform(parse(prefs[rowsKey]))
            if (updated.isEmpty()) {
                prefs.remove(rowsKey)
            } else {
                prefs[rowsKey] = gson.toJson(updated.map { it.toStored() })
            }
        }
        true
    } catch (_: IOException) {
        false
    }

    private fun parse(json: String?): List<FilterRow> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<StoredFilterRow>>() {}.type
            gson.fromJson<List<StoredFilterRow>>(json, type).orEmpty().mapNotNull { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Field names are the stored JSON keys; @SerializedName keeps them stable under R8.
    private data class StoredFilterRow(
        @SerializedName("id") val id: String? = null,
        @SerializedName("title") val title: String? = null,
        @SerializedName("sourceKind") val sourceKind: String? = null,
        @SerializedName("connectionId") val connectionId: String? = null,
        @SerializedName("libraryId") val libraryId: String? = null,
        @SerializedName("addonId") val addonId: String? = null,
        @SerializedName("type") val type: String? = null,
        @SerializedName("catalogId") val catalogId: String? = null,
        @SerializedName("genre") val genre: String? = null,
        @SerializedName("yearFrom") val yearFrom: Int? = null,
        @SerializedName("yearTo") val yearTo: Int? = null,
        @SerializedName("watched") val watched: String? = null,
        @SerializedName("minRating") val minRating: Double? = null,
        @SerializedName("sort") val sort: String? = null,
        @SerializedName("descending") val descending: Boolean? = null
    ) {
        fun toDomain(): FilterRow? {
            val id = id?.takeIf { it.isNotBlank() } ?: return null
            val source = when (sourceKind) {
                SOURCE_SERVER -> FilterRowSource.ServerLibrary(
                    connectionId = connectionId ?: return null,
                    libraryId = libraryId ?: return null
                )
                SOURCE_ADDON -> FilterRowSource.AddonCatalog(
                    addonId = addonId ?: return null,
                    type = type ?: return null,
                    catalogId = catalogId ?: return null
                )
                else -> return null
            }
            return FilterRow(
                id = id,
                title = title.orEmpty(),
                source = source,
                filters = FilterRowFilters(
                    genre = genre?.takeIf { it.isNotBlank() },
                    yearFrom = yearFrom,
                    yearTo = yearTo,
                    watched = WatchedFilter.entries.firstOrNull { it.name == watched } ?: WatchedFilter.ANY,
                    minRating = minRating,
                    sort = FilterRowSort.entries.firstOrNull { it.name == sort } ?: FilterRowSort.DATE_ADDED,
                    descending = descending ?: true
                )
            )
        }
    }

    private fun FilterRow.toStored(): StoredFilterRow {
        val server = source as? FilterRowSource.ServerLibrary
        val addon = source as? FilterRowSource.AddonCatalog
        return StoredFilterRow(
            id = id,
            title = title,
            sourceKind = if (server != null) SOURCE_SERVER else SOURCE_ADDON,
            connectionId = server?.connectionId,
            libraryId = server?.libraryId,
            addonId = addon?.addonId,
            type = addon?.type,
            catalogId = addon?.catalogId,
            genre = filters.genre,
            yearFrom = filters.yearFrom,
            yearTo = filters.yearTo,
            watched = filters.watched.name,
            minRating = filters.minRating,
            sort = filters.sort.name,
            descending = filters.descending
        )
    }

    private companion object {
        const val FEATURE = "filter_rows"
        const val SOURCE_SERVER = "server"
        const val SOURCE_ADDON = "addon"
    }
}
