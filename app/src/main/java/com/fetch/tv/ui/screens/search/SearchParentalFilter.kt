package com.fetch.tv.ui.screens.search

import com.fetch.tv.core.parental.ParentalControls

private fun com.fetch.tv.domain.model.CatalogRow.isPlaceholderRow(): Boolean =
    isLoading && items.firstOrNull()?.id?.startsWith("__placeholder_") == true

/**
 * A filtered search row, or null when the filter emptied it and there is nothing left to page.
 * A row with more pages stays, empty, so See all keeps its paging source and can reach the titles
 * on later pages (#789). A row that was already empty stays as it was.
 */
internal fun com.fetch.tv.domain.model.CatalogRow.keptAfterFiltering(
    original: com.fetch.tv.domain.model.CatalogRow
): com.fetch.tv.domain.model.CatalogRow? =
    takeIf { items.isNotEmpty() || original.items.isEmpty() || original.hasMore }

/** Applies the active profile's certification limit and hidden titles to search and Discover results. */
internal fun SearchUiState.filteredForProfile(parentalControls: ParentalControls): SearchUiState {
    if (!parentalControls.filtersContent()) return this
    return copy(
        catalogRows = catalogRows.mapNotNull { row ->
            if (row.isPlaceholderRow()) return@mapNotNull row
            parentalControls.filterRow(row).keptAfterFiltering(original = row)
        },
        discoverResults = discoverResults.filter(parentalControls::isAllowed),
        discoverTrending = discoverTrending?.let { row ->
            row.copy(items = row.items.filter(parentalControls::isAllowed))
        },
        // A person's page lists their whole filmography; restricted profiles browse titles only.
        peopleResults = emptyList()
    )
}
