package com.fetch.desktop.data

/** The TV Library's sort options; which a provider offers is [com.fetch.desktop.library.LibraryProviderOrder.sortOptions]. */
enum class LibrarySort(val label: String) {
    /** The list's own order on Trakt, Simkl or MDBList. */
    PROVIDER("Provider order"),
    RECENTLY_ADDED("Recently added"),
    FIRST_ADDED("First added"),
    RELEASED_DESC("Newest releases"),
    RELEASED_ASC("Oldest releases"),
    TITLE_ASC("Title A–Z"),
    TITLE_DESC("Title Z–A");

    companion object {
        /** [items] in [order] (content ids, as MDBList gives them); ones it doesn't list go last. */
        fun <T> byOrder(items: List<T>, order: List<String>, id: (T) -> String): List<T> {
            val index = order.withIndex().associate { it.value to it.index }
            return items.sortedBy { index[id(it)] ?: Int.MAX_VALUE }
        }
    }
}

enum class WatchedFilter(val label: String) { ALL("All"), WATCHED("Watched"), UNWATCHED("Unwatched") }

/** The Library's filters. Null means "All". */
data class LibraryQuery(
    val type: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val watched: WatchedFilter = WatchedFilter.ALL,
    val sort: LibrarySort = LibrarySort.RECENTLY_ADDED
)

/** One option in a filter, with how many items it would show given the other filters. */
data class FacetOption(val value: String, val count: Int)

/**
 * Filters, sorts and counts the library the way the TV app does: each filter's counts are for the
 * items that match every *other* active filter, so picking one never shows a dead-end option.
 */
class LibraryView(items: List<LibraryEntry>, private val watchedIds: Set<String>, val query: LibraryQuery) {
    private val all = items
    private var ordered: List<LibraryEntry>? = null

    /** This view with its results in an order given from outside (MDBList's release order). */
    fun withResults(results: List<LibraryEntry>): LibraryView = LibraryView(all, watchedIds, query).also { it.ordered = results }

    val results: List<LibraryEntry> get() = ordered ?: sorted
    private val sorted: List<LibraryEntry> = sort(all.filter { matches(it, query) })

    val types: List<FacetOption> = facet({ listOf(it.type.lowercase()) }, query.copy(type = null))
        .sortedWith(compareBy({ typeOrder(it.value) }, { it.value }))
    val genres: List<FacetOption> = facet({ it.genres }, query.copy(genre = null)).sortedBy { it.value.lowercase() }
    val years: List<FacetOption> = facet({ listOfNotNull(yearOf(it)) }, query.copy(year = null)).sortedByDescending { it.value }

    /** How many items "All" would show for the type filter. */
    val allTypesCount: Int = all.count { matches(it, query.copy(type = null)) }

    private fun facet(values: (LibraryEntry) -> List<String>, others: LibraryQuery): List<FacetOption> =
        all.filter { matches(it, others) }
            .flatMap { entry -> values(entry).distinct() }
            .groupingBy { it }
            .eachCount()
            .map { (value, count) -> FacetOption(value, count) }

    private fun matches(entry: LibraryEntry, q: LibraryQuery): Boolean {
        if (q.type != null && !entry.type.equals(q.type, ignoreCase = true)) return false
        if (q.genre != null && q.genre !in entry.genres) return false
        if (q.year != null && yearOf(entry) != q.year) return false
        val isWatched = entry.id in watchedIds
        return when (q.watched) {
            WatchedFilter.ALL -> true
            WatchedFilter.WATCHED -> isWatched
            WatchedFilter.UNWATCHED -> !isWatched
        }
    }

    private fun sort(items: List<LibraryEntry>): List<LibraryEntry> = when (query.sort) {
        // [all] is in the provider's order; filtering keeps it.
        LibrarySort.PROVIDER -> items.sortedWith(com.fetch.desktop.library.LibraryProviderOrder.of(all).comparator())
        LibrarySort.RELEASED_DESC -> items.sortedWith(compareByDescending<LibraryEntry, String?>(nullsFirst()) { yearOf(it) }.thenBy { titleSortKey(it.name) })
        LibrarySort.RELEASED_ASC -> items.sortedWith(compareBy<LibraryEntry, String?>(nullsLast()) { yearOf(it) }.thenBy { titleSortKey(it.name) })
        LibrarySort.RECENTLY_ADDED -> items.sortedByDescending { it.addedAt }
        LibrarySort.FIRST_ADDED -> items.sortedBy { it.addedAt }
        LibrarySort.TITLE_ASC -> items.sortedBy { titleSortKey(it.name) }
        LibrarySort.TITLE_DESC -> items.sortedByDescending { titleSortKey(it.name) }
    }

    companion object {
        private val YEAR = Regex("""(19|20)\d{2}""")
        private val ARTICLES = Regex("""^(the|a|an)\s+""", RegexOption.IGNORE_CASE)

        fun yearOf(entry: LibraryEntry): String? = entry.releaseInfo?.let { YEAR.find(it)?.value }

        /** "The Office" sorts under O. */
        fun titleSortKey(name: String): String = name.trim().replace(ARTICLES, "").lowercase()

        private fun typeOrder(type: String): Int = when (type) {
            "movie" -> 0
            "series", "tv", "show" -> 1
            "anime" -> 2
            else -> 3
        }
    }
}
