package com.fetch.desktop.sync

/**
 * Only the shared settings that differ from [before], with the rest null ("leave as is"). The TV
 * app replaces every key a push contains, so pushing unchanged values would undo changes made on
 * the TV since the last pull. Null when nothing changed.
 */
fun RemoteSharedSettings.changedFrom(before: RemoteSharedSettings): RemoteSharedSettings? {
    fun <T> pick(now: T?, then: T?): T? = now.takeIf { it != then }
    val diff = RemoteSharedSettings(
        theme = pick(theme, before.theme),
        showContinueWatching = pick(showContinueWatching, before.showContinueWatching),
        showCatalogType = pick(showCatalogType, before.showCatalogType),
        showAddonName = pick(showAddonName, before.showAddonName),
        showPosterLabels = pick(showPosterLabels, before.showPosterLabels),
        discoverLocation = pick(discoverLocation, before.discoverLocation),
        audioLanguage = pick(audioLanguage, before.audioLanguage),
        subtitleLanguage = pick(subtitleLanguage, before.subtitleLanguage),
        rankingRules = pick(rankingRules, before.rankingRules),
        autoplayNextEpisode = pick(autoplayNextEpisode, before.autoplayNextEpisode),
        autoSkipSegments = pick(autoSkipSegments, before.autoSkipSegments),
        showUnairedNextUp = pick(showUnairedNextUp, before.showUnairedNextUp),
        nextUpFromFurthest = pick(nextUpFromFurthest, before.nextUpFromFurthest),
        continueWatchingSort = pick(continueWatchingSort, before.continueWatchingSort),
        tmdbFlags = tmdbFlags?.filter { (k, v) -> before.tmdbFlags?.get(k) != v }?.takeIf { it.isNotEmpty() },
        tmdbLanguage = pick(tmdbLanguage, before.tmdbLanguage),
        mdblistFlags = mdblistFlags?.filter { (k, v) -> before.mdblistFlags?.get(k) != v }?.takeIf { it.isNotEmpty() },
        mdblistRatingOrder = pick(mdblistRatingOrder, before.mdblistRatingOrder),
        appearance = appearance.changedFrom(before.appearance),
        experience = experience.changedFrom(before.experience),
        playerExtras = playerExtras?.changedFrom(before.playerExtras),
        autoPlay = autoPlay?.filter { (k, v) -> before.autoPlay?.get(k) != v }?.takeIf { it.isNotEmpty() },
        autoPlayAddons = pick(autoPlayAddons, before.autoPlayAddons),
        episodeQueue = pick(episodeQueue, before.episodeQueue),
        layoutValues = layoutValues?.filter { (k, v) -> before.layoutValues?.get(k) != v }?.takeIf { it.isNotEmpty() },
        trackingStrings = trackingStrings?.filter { (k, v) -> before.trackingStrings?.get(k) != v }?.takeIf { it.isNotEmpty() },
        trackingInts = trackingInts?.filter { (k, v) -> before.trackingInts?.get(k) != v }?.takeIf { it.isNotEmpty() },
        trackingFlags = trackingFlags?.filter { (k, v) -> before.trackingFlags?.get(k) != v }?.takeIf { it.isNotEmpty() }
    )
    return diff.takeUnless { it == RemoteSharedSettings() }
}
