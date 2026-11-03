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
private val SEGMENT_STORED = mapOf(
    SegmentType.INTRO to "intro", SegmentType.RECAP to "recap", SegmentType.OUTRO to "outro", SegmentType.MOVIE_CREDITS to "movie-credits"
)

/**
 * [settings] with the account's shared settings applied. Fields the blob doesn't have are left
 * as they are, and so are values this app has no equivalent for: Discover
 * `OFF`, and the TV's `device`/`original`/`forced` language choices (`default` clears the
 * language here, meaning the file's own). Ranking rules keep this app's `dropUndecodable` off,
 * since mpv plays nearly anything.
 */
fun RemoteSharedSettings.applyTo(settings: DesktopSettings): DesktopSettings {
    var s = settings
    theme?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }?.let { s = s.copy(theme = it.name) }
    appearance?.let { s = s.copy(appearance = it.applyTo(s.appearance)) }
    experience?.let { s = s.copy(experience = it.applyTo(s.experience)) }
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
    playerExtras?.let { player = player.copy(extras = it.applyTo(player.extras)) }
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
    return s.copy(player = player, tmdb = tmdb, mdblist = applyMdbList(s.mdblist))
}

/** The TV's `mdblist_settings` (all but the API key) over [m]; keys the blob lacks keep their value. */
private fun RemoteSharedSettings.applyMdbList(m: com.fetch.desktop.mdblist.MdbListSettings): com.fetch.desktop.mdblist.MdbListSettings {
    var out = m
    mdblistFlags?.let { f ->
        out = out.copy(
            enabled = f["mdblist_enabled"] ?: out.enabled,
            showTrakt = f["mdblist_show_trakt"] ?: out.showTrakt,
            showImdb = f["mdblist_show_imdb"] ?: out.showImdb,
            showTmdb = f["mdblist_show_tmdb"] ?: out.showTmdb,
            showLetterboxd = f["mdblist_show_letterboxd"] ?: out.showLetterboxd,
            showTomatoes = f["mdblist_show_tomatoes"] ?: out.showTomatoes,
            showAudience = f["mdblist_show_audience"] ?: out.showAudience,
            showMetacritic = f["mdblist_show_metacritic"] ?: out.showMetacritic,
            showMal = f["mdblist_show_mal"] ?: out.showMal,
            showOnHero = f["mdblist_show_on_hero"] ?: out.showOnHero
        )
    }
    // As the TV reads it: comma-separated, blanks dropped.
    mdblistRatingOrder?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { out = out.copy(ratingOrder = it) }
    return out
}

private fun com.fetch.desktop.mdblist.MdbListSettings.toRemoteFlags(): Map<String, Boolean> = mapOf(
    "mdblist_enabled" to enabled,
    "mdblist_show_trakt" to showTrakt,
    "mdblist_show_imdb" to showImdb,
    "mdblist_show_tmdb" to showTmdb,
    "mdblist_show_letterboxd" to showLetterboxd,
    "mdblist_show_tomatoes" to showTomatoes,
    "mdblist_show_audience" to showAudience,
    "mdblist_show_metacritic" to showMetacritic,
    "mdblist_show_mal" to showMal,
    "mdblist_show_on_hero" to showOnHero
)

/**
 * [settings] as shared settings to push. [remote] is the account's current copy: the TV's own
 * `dropUndecodable` and any auto-skip type this app lacks are kept from it, and a language left unset
 * here isn't sent, so the TV keeps its own.
 */
fun DesktopSettings.toRemoteSharedSettings(remote: RemoteSharedSettings? = null): RemoteSharedSettings {
    val tvDropUndecodable = remote?.rankingRules?.dropUndecodable ?: StreamRankingRules().dropUndecodable
    val extraSegments = remote?.autoSkipSegments.orEmpty() - SEGMENT_STORED.values.toSet()
    return RemoteSharedSettings(
        theme = appTheme.name,
        appearance = appearance.toRemote(),
        experience = experience.toRemote(),
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
        mdblistFlags = mdblist.toRemoteFlags(),
        mdblistRatingOrder = mdblist.ratingOrder.joinToString(","),
        playerExtras = RemotePlayerExtras.from(player.extras),
    )
}

private fun String.languageOr(current: String?): String? = when (val code = trim()) {
    "default" -> null
    in TV_LANGUAGE_CHOICES, "" -> current
    else -> code
}
