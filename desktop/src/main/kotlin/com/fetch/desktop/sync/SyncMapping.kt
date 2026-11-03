package com.fetch.desktop.sync

import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.skip.SegmentType
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.SavedLibraryItem

/** The shared model the TV app's library reducer works on. `logo` is not synced. */
fun RemoteLibraryItem.toSavedLibraryItem(): SavedLibraryItem = SavedLibraryItem(
    id = id,
    type = type,
    name = name,
    poster = poster,
    posterShape = PosterShape.fromString(posterShape),
    background = background,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl,
    addedAt = addedAtEpochMs,
)

fun SavedLibraryItem.toRemoteLibraryItem(): RemoteLibraryItem = RemoteLibraryItem(
    id = id,
    type = type,
    name = name,
    poster = poster,
    posterShape = posterShape.name,
    background = background,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl,
    addedAtEpochMs = addedAt,
)

// --- Shared settings <-> DesktopSettings ---

private val TV_LANGUAGE_CHOICES = setOf("device", "default", "original", "forced")
private val SEGMENT_STORED = mapOf(SegmentType.INTRO to "intro", SegmentType.RECAP to "recap", SegmentType.OUTRO to "outro")

/**
 * [settings] with the account's shared settings applied. Fields the blob doesn't have are left
 * as they are, and so are values this app has no equivalent for: the `CUSTOM` theme, Discover
 * `OFF`, and the TV's `device`/`original`/`forced` language choices (`default` clears the
 * language here, meaning the file's own). Ranking rules keep this app's `dropUndecodable` off,
 * since mpv plays nearly anything.
 */
fun RemoteSharedSettings.applyTo(settings: DesktopSettings): DesktopSettings {
    var s = settings
    theme?.let { name -> AppTheme.entries.firstOrNull { it.name == name && it != AppTheme.CUSTOM } }?.let { s = s.copy(theme = it.name) }
    showContinueWatching?.let { s = s.copy(showContinueWatching = it) }
    showCatalogType?.let { s = s.copy(showCatalogType = it) }
    showAddonName?.let { s = s.copy(showAddonName = it) }
    showPosterLabels?.let { s = s.copy(showPosterLabels = it) }
    when (discoverLocation) {
        "IN_SEARCH" -> s = s.copy(discoverInSidebar = false)
        "IN_SIDEBAR" -> s = s.copy(discoverInSidebar = true)
    }
    rankingRules?.let { s = s.copy(rankingRules = it.copy(dropUndecodable = settings.rankingRules.dropUndecodable)) }
    var player = s.player
    audioLanguage?.let { player = player.copy(audioLanguage = it.languageOr(player.audioLanguage)) }
    subtitleLanguage?.let { player = player.copy(subtitleLanguage = it.languageOr(player.subtitleLanguage)) }
    autoplayNextEpisode?.let { player = player.copy(autoplayNextEpisode = it) }
    autoSkipSegments?.let { stored ->
        player = player.copy(autoSkip = SEGMENT_STORED.filterValues { it in stored }.keys)
    }
    showUnairedNextUp?.let { s = s.copy(showUnairedNextUp = it) }
    nextUpFromFurthest?.let { s = s.copy(nextUpFromFurthest = it) }
    continueWatchingSort?.let { name ->
        com.fetch.tv.domain.model.ContinueWatchingSortMode.entries.firstOrNull { it.name == name }?.let { s = s.copy(continueWatchingSort = it) }
    }
    var tmdb = s.tmdb
    tmdbFlags?.let { f ->
        tmdb = tmdb.copy(
            enabled = f["tmdb_enabled"] ?: tmdb.enabled,
            useArtwork = f["tmdb_use_artwork"] ?: tmdb.useArtwork,
            useBasicInfo = f["tmdb_use_basic_info"] ?: tmdb.useBasicInfo,
            useDetails = f["tmdb_use_details"] ?: tmdb.useDetails,
            useCredits = f["tmdb_use_credits"] ?: tmdb.useCredits,
            useProductions = f["tmdb_use_productions"] ?: tmdb.useProductions,
            useNetworks = f["tmdb_use_networks"] ?: tmdb.useNetworks,
            useEpisodes = f["tmdb_use_episodes"] ?: tmdb.useEpisodes,
            useTrailers = f["tmdb_use_trailers"] ?: tmdb.useTrailers,
            useMoreLikeThis = f["tmdb_use_more_like_this"] ?: tmdb.useMoreLikeThis,
            useCollections = f["tmdb_use_collections"] ?: tmdb.useCollections
        )
    }
    tmdbLanguage?.takeIf { it.isNotBlank() }?.let { tmdb = tmdb.copy(language = it) }
    return s.copy(player = player, tmdb = tmdb)
}

/**
 * [settings] as shared settings to push. [remote] is the account's current copy: the TV's own
 * `dropUndecodable` and a `movie-credits` auto-skip are kept from it, and a language left unset
 * here isn't sent, so the TV keeps its own, as is the fallback theme over a `CUSTOM` one there.
 */
fun DesktopSettings.toRemoteSharedSettings(remote: RemoteSharedSettings? = null): RemoteSharedSettings {
    val tvDropUndecodable = remote?.rankingRules?.dropUndecodable ?: StreamRankingRules().dropUndecodable
    val extraSegments = remote?.autoSkipSegments.orEmpty() - SEGMENT_STORED.values.toSet()
    return RemoteSharedSettings(
        // A custom TV theme has colours this app can't show; don't replace it with the fallback.
        theme = appTheme.name.takeUnless { remote?.theme == AppTheme.CUSTOM.name && appTheme == AppTheme.FETCH },
        showContinueWatching = showContinueWatching,
        showCatalogType = showCatalogType,
        showAddonName = showAddonName,
        showPosterLabels = showPosterLabels,
        discoverLocation = if (discoverInSidebar) "IN_SIDEBAR" else "IN_SEARCH",
        audioLanguage = player.audioLanguage?.takeIf { it.isNotBlank() },
        subtitleLanguage = player.subtitleLanguage?.takeIf { it.isNotBlank() },
        rankingRules = rankingRules.copy(dropUndecodable = tvDropUndecodable),
        autoplayNextEpisode = player.autoplayNextEpisode,
        autoSkipSegments = player.autoSkip.mapNotNull { SEGMENT_STORED[it] }.toSet() + extraSegments,
        showUnairedNextUp = showUnairedNextUp,
        nextUpFromFurthest = nextUpFromFurthest,
        continueWatchingSort = continueWatchingSort.name,
        tmdbFlags = mapOf(
            "tmdb_enabled" to tmdb.enabled,
            "tmdb_use_artwork" to tmdb.useArtwork,
            "tmdb_use_basic_info" to tmdb.useBasicInfo,
            "tmdb_use_details" to tmdb.useDetails,
            "tmdb_use_credits" to tmdb.useCredits,
            "tmdb_use_productions" to tmdb.useProductions,
            "tmdb_use_networks" to tmdb.useNetworks,
            "tmdb_use_episodes" to tmdb.useEpisodes,
            "tmdb_use_trailers" to tmdb.useTrailers,
            "tmdb_use_more_like_this" to tmdb.useMoreLikeThis,
            "tmdb_use_collections" to tmdb.useCollections
        ),
        tmdbLanguage = tmdb.language,
    )
}

private fun String.languageOr(current: String?): String? = when (val code = trim()) {
    "default" -> null
    in TV_LANGUAGE_CHOICES, "" -> current
    else -> code
}
