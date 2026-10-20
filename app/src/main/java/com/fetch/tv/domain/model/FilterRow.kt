package com.fetch.tv.domain.model

import com.fetch.tv.data.mediaserver.ServerCatalog

/**
 * A Home row built from filters over one source: a media-server library, or an addon catalog
 * that takes a `genre` extra. It shows up on Home as one more catalog of the addon or server it
 * reads from, so it can be reordered, hidden and pinned like any other row.
 */
data class FilterRow(
    val id: String,
    val title: String,
    val source: FilterRowSource,
    val filters: FilterRowFilters = FilterRowFilters()
) {
    /** Catalog id the row takes in its owner's catalog list. */
    val catalogId: String
        get() = filterRowCatalogId(id)
}

sealed interface FilterRowSource {
    data class ServerLibrary(val connectionId: String, val libraryId: String) : FilterRowSource

    /** Only [FilterRowFilters.genre] applies: addons take no other filter through `extra`. */
    data class AddonCatalog(val addonId: String, val type: String, val catalogId: String) : FilterRowSource
}

enum class WatchedFilter { ANY, UNWATCHED, WATCHED }

enum class FilterRowSort { DATE_ADDED, TITLE, RELEASE_DATE, RATING, RANDOM }

data class FilterRowFilters(
    val genre: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val watched: WatchedFilter = WatchedFilter.ANY,
    val minRating: Double? = null,
    val sort: FilterRowSort = FilterRowSort.DATE_ADDED,
    val descending: Boolean = true
)

private const val FILTER_ROW_CATALOG_PREFIX = "fetch-filter."

fun filterRowCatalogId(rowId: String): String = FILTER_ROW_CATALOG_PREFIX + rowId

fun filterRowIdOf(catalogId: String): String? =
    catalogId.takeIf { it.startsWith(FILTER_ROW_CATALOG_PREFIX) }?.removePrefix(FILTER_ROW_CATALOG_PREFIX)

/** Home order and visibility keys of filter rows are local to this device, like server rows. */
fun isFilterRowKey(key: String): Boolean = key.contains("_$FILTER_ROW_CATALOG_PREFIX")

/**
 * Adds each filter row to the catalogs of the addon or server it reads from, so Home loads it
 * through that addon and opening a title from it works as it does from the source catalog.
 * Rows whose source is gone are left out.
 */
fun List<Addon>.withFilterRows(rows: List<FilterRow>): List<Addon> {
    if (rows.isEmpty()) return this
    return map { addon ->
        val extra = rows.mapNotNull { row -> row.descriptorFor(addon) }
        if (extra.isEmpty()) addon else addon.copy(catalogs = addon.catalogs + extra)
    }
}

private fun FilterRow.descriptorFor(addon: Addon): CatalogDescriptor? {
    val sourceCatalog = when (val source = source) {
        is FilterRowSource.AddonCatalog -> {
            if (addon.id != source.addonId) return null
            addon.catalogs.firstOrNull { it.id == source.catalogId && it.apiType == source.type }
        }
        is FilterRowSource.ServerLibrary -> {
            if (addon.id != ServerCatalog.addonId(source.connectionId)) return null
            addon.catalogs.firstOrNull { it.id == source.libraryId }
        }
    } ?: return null
    return sourceCatalog.copy(
        id = catalogId,
        name = title,
        // The filters are applied by the row itself; only paging stays with the source.
        extra = sourceCatalog.extra.filter { it.name.equals("skip", ignoreCase = true) },
        extraSupported = emptyList(),
        extraRequired = emptyList(),
        showInHome = true,
        hasExplicitShowInHome = true
    )
}
