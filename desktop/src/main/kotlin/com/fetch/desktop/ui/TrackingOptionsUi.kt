package com.fetch.desktop.ui

import androidx.compose.runtime.Composable
import com.fetch.desktop.AppState
import com.fetch.desktop.TrackingService
import com.fetch.desktop.tracking.LibrarySourceOption
import com.fetch.desktop.tracking.MoreLikeThisSourceOption
import com.fetch.desktop.tracking.SimklAnimeIdOption
import com.fetch.desktop.tracking.TrackingOptions
import com.fetch.desktop.tracking.WatchProgressSourceOption

/** The Library sources this profile can open with: Fetch's own, and each connected service. */
fun availableLibrarySources(state: AppState): List<LibrarySourceOption> = buildList {
    add(LibrarySourceOption.LOCAL)
    if (state.tracking.account(TrackingService.TRAKT) != null) add(LibrarySourceOption.TRAKT)
    if (state.tracking.account(TrackingService.SIMKL) != null) add(LibrarySourceOption.SIMKL)
    if (state.mdbListAccount.connected) add(LibrarySourceOption.MDBLIST)
}

/** The Continue Watching sources this profile has: each connected service and the sync server. */
fun availableProgressSources(state: AppState): Set<WatchProgressSourceOption> = buildSet {
    if (state.tracking.account(TrackingService.TRAKT) != null) add(WatchProgressSourceOption.TRAKT)
    if (state.tracking.account(TrackingService.SIMKL) != null) add(WatchProgressSourceOption.SIMKL)
    if (state.mdbListAccount.connected) add(WatchProgressSourceOption.MDBLIST)
    if (state.account?.signedIn == true) add(WatchProgressSourceOption.FETCH_SYNC)
}

private fun AppState.updateTracking(change: (TrackingOptions) -> TrackingOptions) =
    updateSettings { it.copy(tracking = change(it.tracking)) }

private fun daysLabel(days: Int) = when (days) {
    0 -> "All"
    365 -> "1 year"
    else -> "$days days"
}

/**
 * Settings › Tracking's options, as on TV: Library source, watch progress source, the Continue
 * Watching window, More like this source, comments and Simkl's anime id. Synced with the TV.
 */
@Composable
fun TrackingOptionsSection(state: AppState) {
    val o = state.settings.tracking
    SettingsSection("Library & Continue Watching") {
        val libraries = availableLibrarySources(state)
        SettingsChoice(
            "Library source",
            "Which list the Library opens with. The others stay a click away in its Source menu.",
            o.library.takeIf { it in libraries } ?: LibrarySourceOption.LOCAL,
            libraries.map { DropdownOption(it, it.label) }
        ) { v -> state.updateTracking { it.copy(librarySource = v.name) } }
        val progress = availableProgressSources(state)
        if (progress.isNotEmpty()) {
            SettingsChoice(
                "Watch progress source",
                "Whose resume points fill Continue Watching, besides what you play here.",
                o.progress.takeIf { it in progress } ?: progress.first(),
                WatchProgressSourceOption.entries.filter { it in progress }.map { DropdownOption(it, it.label) }
            ) { v ->
                state.updateTracking { it.copy(watchProgressSource = v.name) }
                state.syncTracking()
            }
        }
        SettingsChoice(
            "Continue Watching window",
            "Titles you haven't watched for longer leave Continue Watching.",
            o.continueWatchingDays,
            TrackingOptions.CW_DAY_CHOICES.map { DropdownOption(it, daysLabel(it)) }
        ) { v -> state.updateTracking { it.copy(continueWatchingDays = TrackingOptions.normalizeDays(v)) } }
    }
    SettingsSection("Title pages") {
        SettingsChoice(
            "More like this",
            "Where a title page's More like this row comes from. TMDB needs Settings › TMDB.",
            o.moreLikeThis,
            MoreLikeThisSourceOption.entries.map { DropdownOption(it, it.label) }
        ) { v -> state.updateTracking { it.copy(moreLikeThisSource = v.name) } }
        SettingsToggle("Show comments", "Trakt's most liked comments on title pages.", o.showComments) { v ->
            state.updateTracking { it.copy(showComments = v) }
        }
        SettingsChoice(
            "Simkl anime id",
            o.animeId.description,
            o.animeId,
            SimklAnimeIdOption.entries.map { DropdownOption(it, it.label) }
        ) { v ->
            state.updateTracking { it.copy(simklAnimeId = v.name) }
            state.syncTracking()
        }
    }
}
