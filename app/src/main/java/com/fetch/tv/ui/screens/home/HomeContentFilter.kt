package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.CatalogRow

/** Home's filter above the rows: All · Movies · Shows, plus Anime with anime catalogs (#814). */
enum class HomeContentFilter {
    ALL,
    MOVIES,
    SHOWS,
    ANIME;

    /** Whether a row or title of this addon type stays on Home under this filter. */
    fun matchesType(apiType: String?): Boolean {
        val type = apiType?.trim()?.lowercase()
        return when (this) {
            ALL -> true
            MOVIES -> type == "movie"
            SHOWS -> type == "series"
            ANIME -> type == "anime"
        }
    }

    companion object {
        /** The stored name, or All for none or one this version doesn't know. */
        fun fromStored(name: String?): HomeContentFilter =
            entries.firstOrNull { it.name == name } ?: ALL
    }
}

/**
 * The filters worth offering: All, and each of the others that would leave something on Home.
 * Empty when at most one kind of content is there, as a filter would then change nothing.
 */
internal fun availableHomeContentFilters(input: ModernHomePresentationInput): List<HomeContentFilter> {
    val types = buildSet {
        input.catalogRows.filter { it.items.isNotEmpty() }.forEach { add(it.apiType) }
        input.homeRows.forEach { row ->
            when (row) {
                is HomeRow.Catalog -> add(row.row.apiType)
                is HomeRow.PlaceholderCatalog -> add(row.apiType)
                is HomeRow.CollectionRow -> Unit
            }
        }
        input.continueWatchingItems.forEach { add(it.contentType()) }
    }
    val offered = HomeContentFilter.entries
        .filter { it != HomeContentFilter.ALL }
        .filter { filter -> types.any(filter::matchesType) }
    return if (offered.size < 2) emptyList() else listOf(HomeContentFilter.ALL) + offered
}

/**
 * The input with only what [filter] keeps: catalog rows of its type, and Continue Watching,
 * Upcoming and Ready tonight titles of its type. Collection and YouTube rows show only under All.
 */
internal fun ModernHomePresentationInput.filteredBy(filter: HomeContentFilter): ModernHomePresentationInput {
    if (filter == HomeContentFilter.ALL) return this
    fun CatalogRow.kept() = filter.matchesType(apiType)
    return copy(
        homeRows = homeRows.filter { row ->
            when (row) {
                is HomeRow.Catalog -> row.row.kept()
                is HomeRow.PlaceholderCatalog -> filter.matchesType(row.apiType)
                is HomeRow.CollectionRow -> false
            }
        },
        catalogRows = catalogRows.filter { it.kept() },
        becauseYouWatchedRows = becauseYouWatchedRows.filter { it.kept() },
        continueWatchingItems = continueWatchingItems.filter { filter.matchesType(it.contentType()) },
        upcomingItems = upcomingItems.filter { filter.matchesType(it.contentType()) },
        readyTonightItems = readyTonightItems.filter { filter.matchesType(it.contentType()) },
        readyWatchlistMovies = if (filter == HomeContentFilter.MOVIES) readyWatchlistMovies else emptyList(),
        youTubeVideos = emptyList()
    )
}
