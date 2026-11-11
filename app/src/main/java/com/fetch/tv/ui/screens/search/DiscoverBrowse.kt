package com.fetch.tv.ui.screens.search

import com.fetch.tv.domain.model.MetaPreview
import java.text.Collator
import java.util.Locale

/**
 * Discover browses every addon catalog of the chosen type at once. This file holds the pure parts:
 * which catalogs to ask, how their pages merge, and the year, rating and sort filters, which run on
 * the merged items because addon catalogs only take a genre.
 */

/** Order of the merged Discover grid. */
enum class DiscoverSort {
    /** Interleaved catalog order: each catalog's first title, then each one's second, and so on. */
    CATALOG,
    RATING,
    NEWEST,
    TITLE
}

/** An inclusive release-year range; a null bound is open. */
data class DiscoverYearRange(val from: Int?, val to: Int?) {
    operator fun contains(year: Int): Boolean =
        (from == null || year >= from) && (to == null || year <= to)
}

data class DiscoverFilters(
    val yearRange: DiscoverYearRange? = null,
    /** Lowest IMDb rating kept, or null for any rating (unrated titles included). */
    val minRating: Int? = null,
    val sort: DiscoverSort = DiscoverSort.CATALOG
) {
    val narrowsResults: Boolean
        get() = yearRange != null || minRating != null
}

/** Rating thresholds offered by the Rating filter. */
internal val DISCOVER_RATING_THRESHOLDS: List<Int> = listOf(5, 6, 7, 8)

/** Decades offered by the Year filter, the current one first. Older titles share one "before" bucket. */
internal const val DISCOVER_DECADE_COUNT = 5

/**
 * The Year filter's ranges for [currentYear]: this year, the last [DISCOVER_DECADE_COUNT] decades,
 * then everything before them.
 */
internal fun discoverYearRanges(currentYear: Int): List<DiscoverYearRange> {
    val currentDecade = Math.floorDiv(currentYear, 10) * 10
    val decades = (0 until DISCOVER_DECADE_COUNT).map { index ->
        val start = currentDecade - index * 10
        DiscoverYearRange(from = start, to = start + 9)
    }
    val oldestDecade = currentDecade - (DISCOVER_DECADE_COUNT - 1) * 10
    return listOf(DiscoverYearRange(from = currentYear, to = currentYear)) +
        decades +
        DiscoverYearRange(from = null, to = oldestDecade - 1)
}

/** Identity used to merge and de-duplicate titles across catalogs. */
internal fun discoverItemKey(item: MetaPreview): String = "${item.apiType}:${item.id}"

private val YEAR_PATTERN = Regex("""(?<!\d)(1[89]\d{2}|2\d{3})(?!\d)""")

/** The first release year in `releaseInfo` ("2019", "2019–2022"), else in `released`. */
internal fun MetaPreview.discoverYear(): Int? =
    sequenceOf(releaseInfo, released)
        .mapNotNull { value -> value?.let { YEAR_PATTERN.find(it)?.value?.toIntOrNull() } }
        .firstOrNull()

/** Content types Discover can browse, movies and series first, then the rest as the addons list them. */
internal fun discoverTypes(catalogs: List<DiscoverCatalog>): List<String> {
    val types = catalogs.map { it.type }.distinct()
    val preferred = listOf("movie", "series").filter { it in types }
    return preferred + (types - preferred.toSet())
}

/** The type to open on: the one last browsed, else the current one, else movies, else the first. */
internal fun resolveDiscoverType(
    types: List<String>,
    preferredType: String?,
    currentType: String?
): String? =
    types.firstOrNull { it == preferredType }
        ?: types.firstOrNull { it == currentType }
        ?: types.firstOrNull()

/** Every genre any catalog of [type] offers, de-duplicated ignoring case, first spelling kept. */
internal fun discoverGenres(catalogs: List<DiscoverCatalog>, type: String): List<String> =
    catalogs.asSequence()
        .filter { it.type == type }
        .flatMap { it.genres.asSequence() }
        .filter { it.isNotBlank() }
        .distinctBy { it.trim().lowercase(Locale.ROOT) }
        .toList()

/**
 * The catalogs a Discover query asks. With a genre, only catalogs that list it. Without one, every
 * catalog that can be browsed without a genre; only when none of those exist do genre-required
 * catalogs join, on their default genre.
 */
internal fun selectDiscoverSources(
    catalogs: List<DiscoverCatalog>,
    type: String,
    genre: String?
): List<DiscoverCatalog> {
    val ofType = catalogs.filter { it.type == type }
    if (!genre.isNullOrBlank()) {
        return ofType.filter { catalog -> catalog.genres.any { it.equals(genre, ignoreCase = true) } }
    }
    val browsable = ofType.filter { !it.genreRequired }
    return browsable.ifEmpty { ofType.filter { it.defaultGenre != null } }
}

/** The genre value to send to [catalog]: its own spelling of [genre], else its required default. */
internal fun discoverGenreArg(catalog: DiscoverCatalog, genre: String?): String? {
    if (!genre.isNullOrBlank()) {
        return catalog.genres.firstOrNull { it.equals(genre, ignoreCase = true) } ?: genre
    }
    return catalog.defaultGenre.takeIf { catalog.genreRequired }
}

/** Round-robin merge: the first title of each page, then the second of each, and so on. */
internal fun interleaveDiscoverPages(pages: List<List<MetaPreview>>): List<MetaPreview> {
    val longest = pages.maxOfOrNull { it.size } ?: 0
    val merged = ArrayList<MetaPreview>(pages.sumOf { it.size })
    for (index in 0 until longest) {
        pages.forEach { page -> page.getOrNull(index)?.let(merged::add) }
    }
    return merged
}

/**
 * Appends one paging round (one page per catalog, in catalog order) to [existing]. Titles already
 * merged keep their place, so loading more never reorders what the catalog order already showed.
 */
internal fun appendDiscoverRound(
    existing: List<MetaPreview>,
    roundPages: List<List<MetaPreview>>
): List<MetaPreview> {
    val seen = existing.mapTo(HashSet(existing.size * 2)) { discoverItemKey(it) }
    val added = interleaveDiscoverPages(roundPages).filter { seen.add(discoverItemKey(it)) }
    return if (added.isEmpty()) existing else existing + added
}

private val titleCollator: Collator = Collator.getInstance(Locale.ROOT).apply {
    strength = Collator.SECONDARY
}

/**
 * Applies the year and rating filters, then the sort. Titles without a year or rating drop out
 * only when that filter is set; sorts keep them, after the titles that have one. Sorts are stable,
 * so ties keep catalog order.
 */
internal fun applyDiscoverFilters(
    items: List<MetaPreview>,
    filters: DiscoverFilters
): List<MetaPreview> {
    val yearRange = filters.yearRange
    val minRating = filters.minRating
    val filtered = if (!filters.narrowsResults) {
        items
    } else {
        items.filter { item ->
            val yearOk = yearRange == null || item.discoverYear()?.let { it in yearRange } == true
            val ratingOk = minRating == null || (item.imdbRating ?: -1f) >= minRating
            yearOk && ratingOk
        }
    }
    return when (filters.sort) {
        DiscoverSort.CATALOG -> filtered
        DiscoverSort.RATING -> filtered.sortedWith(
            compareBy<MetaPreview> { it.imdbRating == null }.thenByDescending { it.imdbRating ?: 0f }
        )
        DiscoverSort.NEWEST -> filtered.sortedWith(
            compareBy<MetaPreview> { it.discoverYear() == null }.thenByDescending { it.discoverYear() ?: 0 }
        )
        DiscoverSort.TITLE -> filtered.sortedWith { a, b -> titleCollator.compare(a.name, b.name) }
    }
}
