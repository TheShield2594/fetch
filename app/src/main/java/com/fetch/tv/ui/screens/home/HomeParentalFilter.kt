package com.fetch.tv.ui.screens.home

import com.fetch.tv.core.parental.CertificationQuery
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.data.local.ParentalTitle
import com.fetch.tv.data.local.ProfileTitleLists
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape

/** Rows a kids Home shows after Continue Watching. */
internal const val KIDS_HOME_MAX_ROWS = 6

/** How much bigger cards are on a kids Home than the poster size setting. */
internal const val KIDS_HOME_CARD_SCALE = 1.25f

internal fun ContinueWatchingItem.certificationQuery(): CertificationQuery = when (this) {
    is ContinueWatchingItem.InProgress -> CertificationQuery(
        contentId = progress.contentId,
        type = ContentType.fromString(progress.contentType)
    )
    is ContinueWatchingItem.NextUp -> CertificationQuery(
        contentId = info.contentId,
        type = ContentType.fromString(info.contentType)
    )
}

/**
 * Hides Continue Watching and upcoming entries the active profile's limit or hidden titles don't allow.
 * Kids profiles get a Home of their own (see [HomeUiState.kidsHome]), without the upcoming row.
 */
internal fun filterContinueWatchingForProfile(state: HomeUiState, parentalControls: ParentalControls): HomeUiState {
    if (!parentalControls.filtersContent()) return state
    val restrictions = parentalControls.activeRestrictions.value
    val main = parentalControls.filterAllowed(state.continueWatchingItems) { it.certificationQuery() }
    val upcoming = if (restrictions.isKidsProfile) {
        emptyList()
    } else {
        parentalControls.filterAllowed(state.upcomingItems) { it.certificationQuery() }
    }
    if (main.size == state.continueWatchingItems.size && upcoming.size == state.upcomingItems.size) return state
    return state.copy(continueWatchingItems = main, upcomingItems = upcoming)
}

/** Addon id of the rows a kids Home shows in "Approved titles only" mode. */
internal const val APPROVED_TITLES_ADDON_ID = "fetch.parental.approved"

private fun ParentalTitle.toPreview(): MetaPreview {
    val apiType = type.ifBlank { "movie" }
    return MetaPreview(
        id = ids.first(),
        type = ContentType.fromString(apiType),
        rawType = apiType,
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        imdbId = ids.firstOrNull { it.startsWith("tt") }
    )
}

/**
 * The rows a kids Home starts with in "Approved titles only" mode: the profile's approved
 * movies, then its approved shows, newest approval first. Empty in any other mode.
 */
internal fun approvedTitleRows(
    approvedOnly: Boolean,
    lists: ProfileTitleLists,
    moviesTitle: String,
    showsTitle: String
): List<CatalogRow> {
    if (!approvedOnly) return emptyList()
    val titles = lists.allowed.filter { it.ids.isNotEmpty() }.asReversed()
    val (movies, shows) = titles.partition { it.type.equals("movie", ignoreCase = true) }
    return listOf(
        Triple("movies", ContentType.MOVIE, movies) to moviesTitle,
        Triple("shows", ContentType.SERIES, shows) to showsTitle
    ).mapNotNull { (spec, title) ->
        val (catalogId, type, items) = spec
        if (items.isEmpty()) return@mapNotNull null
        CatalogRow(
            addonId = APPROVED_TITLES_ADDON_ID,
            addonName = "",
            addonBaseUrl = "",
            catalogId = catalogId,
            catalogName = title,
            type = type,
            items = items.map { it.toPreview() },
            hasMore = false
        )
    }
}
