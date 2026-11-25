package com.fetch.tv.ui.screens.search

import com.fetch.tv.core.hidden.TitleVisibility
import com.fetch.tv.data.mediaserver.ServerCatalog

/**
 * Leaves the profile's "Not interested" titles (#650) out of search results and Discover. A media
 * server's rows are the user's own library and keep them, and a search never hides watched titles:
 * someone who types a title wants to find it. A row the filter empties goes, as with the profile
 * limit; a row still loading keeps its placeholders.
 */
internal fun SearchUiState.withoutNotInterested(visibility: TitleVisibility): SearchUiState {
    if (visibility.hiddenTitles.isEmpty()) return this
    val rows = catalogRows.mapNotNull { row ->
        if (ServerCatalog.isServerAddonId(row.addonId)) return@mapNotNull row
        if (row.isLoading && row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true) return@mapNotNull row
        visibility.filterSuggestionRow(row).takeIf { it.items.isNotEmpty() || row.items.isEmpty() }
    }
    val discover = visibility.filterSuggestions(discoverResults)
    if (rows == catalogRows && discover === discoverResults) return this
    return copy(catalogRows = rows, discoverResults = discover)
}
