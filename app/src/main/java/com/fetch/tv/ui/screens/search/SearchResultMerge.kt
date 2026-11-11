package com.fetch.tv.ui.screens.search

import androidx.compose.runtime.Immutable
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.MetaPreview
import java.text.Normalizer
import java.util.Locale

/**
 * Search results merged across addons (roadmap D3): every catalog's answer folded into one list
 * per type, each title once, best match first.
 */
@Immutable
data class MergedSearchResults(
    /** One row per type (movies, then series, then anything else), each ranked. */
    val rows: List<CatalogRow> = emptyList(),
    /** The best match across every type, or null with no results. */
    val topResult: MergedSearchResult? = null
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/** One merged title: the copy shown and the addons that returned it. */
@Immutable
data class MergedSearchResult(
    val item: MetaPreview,
    /** Display names of the addons that returned it, in the order they answered. */
    val addonNames: List<String>
)

/** The type filters offered above merged results. */
enum class SearchTypeFilter {
    ALL,
    MOVIES,
    SERIES,
    /** Only titles already saved to the library. */
    IN_LIBRARY
}

/** Synthetic addon id of a merged row; its items carry their own addon in `sourceAddonBaseUrl`. */
internal const val MERGED_ROW_ADDON_ID = "__merged"

internal object SearchResultMerger {

    private const val PLACEHOLDER_PREFIX = "__placeholder_"
    private val IMDB_ID = Regex("""tt\d{5,}""")
    private val TMDB_ID = Regex("""^tmdb[:_](?:(?:movie|tv|series)[:_])?(\d+)$""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""(?<!\d)(\d{4})(?!\d)""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val LEADING_ARTICLE = Regex("""^(the|a|an) """)

    /**
     * Merges [rows] (in addon order) into ranked per-type rows. [rowTitle] names a type's row.
     * [isInLibrary] and [filter] narrow the result; the top result is chosen after filtering.
     */
    fun merge(
        rows: List<CatalogRow>,
        query: String,
        filter: SearchTypeFilter = SearchTypeFilter.ALL,
        isInLibrary: (MetaPreview) -> Boolean = { false },
        rowTitle: (String) -> String = { it }
    ): MergedSearchResults {
        val groups = group(rows)
        if (groups.isEmpty()) return MergedSearchResults()
        val normalizedQuery = normalizeTitle(query)
        val ranked = groups
            .filter { group -> matchesFilter(group, filter, isInLibrary) }
            // Normalising a title is the costly part, so each group's tier is worked out once.
            .onEach { group -> group.matchTier = matchTier(normalizeTitle(group.item.name), normalizedQuery) }
            .sortedWith(RANK_COMPARATOR)
        if (ranked.isEmpty()) return MergedSearchResults()

        val byType = LinkedHashMap<String, MutableList<Group>>()
        typeOrder(ranked).forEach { byType[it] = mutableListOf() }
        ranked.forEach { group -> byType.getValue(group.typeKey) += group }

        val mergedRows = byType.mapNotNull { (typeKey, typeGroups) ->
            if (typeGroups.isEmpty()) return@mapNotNull null
            val sample = typeGroups.first().item
            CatalogRow(
                addonId = MERGED_ROW_ADDON_ID,
                addonName = "",
                addonBaseUrl = "",
                catalogId = typeKey,
                catalogName = rowTitle(typeKey),
                type = sample.type,
                rawType = typeKey,
                items = typeGroups.map(Group::displayItem),
                isLoading = false,
                hasMore = false
            )
        }
        val top = ranked.first()
        return MergedSearchResults(
            rows = mergedRows,
            topResult = MergedSearchResult(top.displayItem(), top.addonNames)
        )
    }

    /**
     * The key a title is merged on: its IMDb id, else its TMDB id, else null (the title-and-year
     * fallback in [group] takes over).
     */
    fun idKey(item: MetaPreview): String? {
        item.imdbId?.let { IMDB_ID.find(it) }?.let { return "imdb:${it.value}" }
        IMDB_ID.find(item.id)?.let { return "imdb:${it.value}" }
        TMDB_ID.find(item.id.trim())?.let { return "tmdb:${typeKey(item)}:${it.groupValues[1]}" }
        return null
    }

    /** Type, normalised title and release year: the fallback dedupe key. */
    fun titleYearKey(item: MetaPreview): String? {
        val title = normalizeTitle(item.name)
        if (title.isBlank()) return null
        val year = releaseYear(item)
        return "${typeKey(item)}|$title|${year.orEmpty()}"
    }

    fun normalizeTitle(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD).replace(DIACRITICS, "")
        return decomposed.lowercase(Locale.ROOT).replace("&", " and ").replace(NON_WORD, " ").trim()
    }

    /**
     * "movie", "series", or the addon's own type for anything else. Stremio's "tv" is live TV
     * channels, not shows, so it keeps a row of its own.
     */
    fun typeKey(item: MetaPreview): String = item.apiType.trim().lowercase(Locale.ROOT).ifBlank { "other" }

    private fun releaseYear(item: MetaPreview): String? =
        sequenceOf(item.releaseInfo, item.released).firstNotNullOfOrNull { value ->
            value?.let { YEAR.find(it)?.groupValues?.get(1) }
        }

    private class Group(
        val typeKey: String,
        var item: MetaPreview,
        val firstRow: Int,
        var bestIndex: Int
    ) {
        var idKey: String? = null
        var matchTier = 0
        var sourceUrl: String? = null
        val addonNames = mutableListOf<String>()
        val addonUrls = LinkedHashSet<String>()

        fun displayItem(): MetaPreview =
            if (item.sourceAddonBaseUrl == null && sourceUrl != null) item.copy(sourceAddonBaseUrl = sourceUrl) else item
    }

    private fun group(rows: List<CatalogRow>): List<Group> {
        val groups = mutableListOf<Group>()
        val byId = HashMap<String, Group>()
        val byTitleYear = HashMap<String, Group>()
        rows.forEachIndexed { rowIndex, row ->
            row.items.forEachIndexed { itemIndex, item ->
                if (item.id.startsWith(PLACEHOLDER_PREFIX) || item.id.isBlank()) return@forEachIndexed
                val idKey = idKey(item)
                val titleYear = titleYearKey(item)
                val existing = idKey?.let(byId::get)
                    ?: titleYear?.let(byTitleYear::get)?.takeIf { candidate ->
                        // Two different ids with the same title and year (a remake in the same
                        // year) stay apart; the fallback only joins a title to one without an id.
                        idKey == null || candidate.idKey == null
                    }
                val group = existing ?: Group(
                    typeKey = typeKey(item),
                    item = item,
                    firstRow = rowIndex,
                    bestIndex = itemIndex
                ).also(groups::add)
                if (existing != null) {
                    group.bestIndex = minOf(group.bestIndex, itemIndex)
                    // Keep the richest copy on screen: one with artwork beats one without.
                    if (group.item.poster.isNullOrBlank() && !item.poster.isNullOrBlank()) group.item = item
                }
                if (group.addonUrls.add(row.addonBaseUrl.ifBlank { row.addonId })) {
                    group.addonNames += row.addonName
                }
                if (group.sourceUrl == null && row.addonBaseUrl.isNotBlank() && row.addonId != MERGED_ROW_ADDON_ID) {
                    group.sourceUrl = row.addonBaseUrl
                }
                if (group.idKey == null && idKey != null) group.idKey = idKey
                idKey?.let { byId.putIfAbsent(it, group) }
                titleYear?.let { byTitleYear.putIfAbsent(it, group) }
            }
        }
        return groups
    }

    private fun matchesFilter(group: Group, filter: SearchTypeFilter, isInLibrary: (MetaPreview) -> Boolean) =
        when (filter) {
            SearchTypeFilter.ALL -> true
            SearchTypeFilter.MOVIES -> group.typeKey == "movie"
            SearchTypeFilter.SERIES -> group.typeKey == "series"
            SearchTypeFilter.IN_LIBRARY -> isInLibrary(group.item)
        }

    /**
     * Best match first: how closely the title matches the query, then how many addons returned it
     * (agreement is a strong signal that it's the well-known title), then where the addons put it,
     * then the order the addons answered in.
     */
    private val RANK_COMPARATOR: Comparator<Group> =
        compareBy<Group> { it.matchTier }
            .thenByDescending { it.addonUrls.size }
            .thenBy { it.bestIndex }
            .thenBy { it.firstRow }

    /** 0 exact, 1 exact without a leading article, 2 prefix, 3 a word starts with it, 4 contains, 5 none. */
    internal fun matchTier(title: String, query: String): Int {
        if (query.isBlank()) return 5
        return when {
            title == query -> 0
            title.replace(LEADING_ARTICLE, "") == query.replace(LEADING_ARTICLE, "") -> 1
            title.startsWith(query) -> 2
            " $title".contains(" $query") -> 3
            title.contains(query) -> 4
            else -> 5
        }
    }

    private fun typeOrder(groups: List<Group>): List<String> {
        val present = groups.mapTo(LinkedHashSet()) { it.typeKey }
        val ordered = mutableListOf<String>()
        if ("movie" in present) ordered += "movie"
        if ("series" in present) ordered += "series"
        present.filterTo(ordered) { it != "movie" && it != "series" }
        return ordered
    }

}

/** A library title's ids, as [libraryIdSet] reads them. */
internal data class LibraryIds(
    val id: String,
    val type: String,
    val imdbId: String? = null,
    val tmdbId: Int? = null
)

/**
 * The keys a library is matched on: each saved id as stored, plus its IMDb and TMDB ids in the
 * form [SearchResultMerger.idKey] gives, so a title an addon returns under another id scheme still
 * shows as saved. TMDB ids are per type, as a movie and a show can share a number.
 */
internal fun libraryIdSet(entries: Collection<LibraryIds>): Set<String> = buildSet {
    entries.forEach { entry ->
        val trimmed = entry.id.trim()
        if (trimmed.isNotEmpty()) add(trimmed)
        sequenceOf(entry.imdbId, trimmed).firstNotNullOfOrNull { value -> value?.let(LIBRARY_IMDB_ID::find) }
            ?.let { add("imdb:${it.value}") }
        val type = entry.type.trim().lowercase(java.util.Locale.ROOT)
        entry.tmdbId?.let { add("tmdb:$type:$it") }
        LIBRARY_TMDB_ID.find(trimmed)?.let { add("tmdb:$type:${it.groupValues[1]}") }
    }
}

/** How a type is named when no string resource covers it ("tv" reads "TV", "anime" "Anime"). */
internal fun addonTypeDisplayName(typeKey: String): String =
    if (typeKey.length <= 2) typeKey.uppercase(java.util.Locale.ROOT) else typeKey.replaceFirstChar { it.titlecase() }

/** Whether [item] is in the library whose keys are [libraryIds] (see [libraryIdSet]). */
internal fun isLibraryItem(item: MetaPreview, libraryIds: Set<String>): Boolean {
    if (libraryIds.isEmpty()) return false
    if (item.id.trim() in libraryIds) return true
    val key = SearchResultMerger.idKey(item) ?: return false
    return key in libraryIds
}

private val LIBRARY_IMDB_ID = Regex("""tt\d{5,}""")
private val LIBRARY_TMDB_ID = Regex("""^tmdb[:_](?:(?:movie|tv|series)[:_])?(\d+)$""", RegexOption.IGNORE_CASE)
