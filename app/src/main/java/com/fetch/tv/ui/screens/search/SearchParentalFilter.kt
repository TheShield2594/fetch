package com.fetch.tv.ui.screens.search

import com.fetch.tv.core.parental.ParentalControls

private fun com.fetch.tv.domain.model.CatalogRow.isPlaceholderRow(): Boolean =
    isLoading && items.firstOrNull()?.id?.startsWith("__placeholder_") == true

/** Applies the active profile's certification limit to search and Discover results. */
internal fun SearchUiState.filteredForProfile(parentalControls: ParentalControls): SearchUiState {
    if (!parentalControls.activeRestrictions.value.isRestricted) return this
    return copy(
        catalogRows = catalogRows.mapNotNull { row ->
            if (row.isPlaceholderRow()) return@mapNotNull row
            parentalControls.filterRow(row).takeIf { it.items.isNotEmpty() || row.items.isEmpty() }
        },
        discoverResults = discoverResults.filter(parentalControls::isAllowed),
        pendingDiscoverResults = pendingDiscoverResults.filter(parentalControls::isAllowed)
    )
}
