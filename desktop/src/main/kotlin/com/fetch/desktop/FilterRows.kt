package com.fetch.desktop

import com.fetch.desktop.data.JsonFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A Home row built from filters over one source, as the TV's `FilterRow`: a media server
 * library (genre, years, watched, rating and sort, applied by the server), or an addon catalog
 * that takes a `genre` (only the genre applies; addons take no other filter).
 */
@Serializable
data class FilterRow(
    val id: String,
    val title: String,
    val source: FilterRowSource,
    val filters: FilterRowFilters = FilterRowFilters()
)

@Serializable
sealed interface FilterRowSource {
    @Serializable
    @SerialName("server")
    data class ServerLibrary(val accountId: String, val libraryId: String) : FilterRowSource

    /** [addonId] is the manifest id, as on TV; [type] and [catalogId] the catalog's. */
    @Serializable
    @SerialName("addon")
    data class AddonCatalog(val addonId: String, @SerialName("catalogType") val type: String, val catalogId: String) : FilterRowSource
}

/** The TV's `WatchedFilter` for filter rows. */
enum class RowWatched(val label: String) { ANY("Any"), UNWATCHED("Unwatched"), WATCHED("Watched") }

/** The TV's `FilterRowSort`. */
enum class RowSort(val label: String) {
    DATE_ADDED("Date added"),
    TITLE("Title"),
    RELEASE_DATE("Release date"),
    RATING("Rating"),
    RANDOM("Random")
}

@Serializable
data class FilterRowFilters(
    val genre: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val watched: RowWatched = RowWatched.ANY,
    val minRating: Double? = null,
    val sort: RowSort = RowSort.DATE_ADDED,
    val descending: Boolean = true
)

@Serializable
data class FilterRowsFile(val rows: List<FilterRow> = emptyList())

/** A profile's filter rows, kept on this computer only, as the TV keeps them on each TV. */
fun filterRowsFile(dir: File) = JsonFile(File(dir, "filter-rows.json"), FilterRowsFile.serializer()) { FilterRowsFile() }

/** The Home row key of an addon filter row, apart from its source catalog's. */
fun filterRowKey(row: FilterRow) = "filter|${row.id}"
