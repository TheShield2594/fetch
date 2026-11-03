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
        tmdbFlags = tmdbFlags?.filter { (k, v) -> before.tmdbFlags?.get(k) != v }?.takeIf { it.isNotEmpty() },
        tmdbLanguage = pick(tmdbLanguage, before.tmdbLanguage)
    )
    return diff.takeUnless { it == RemoteSharedSettings() }
}
