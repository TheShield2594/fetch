package com.fetch.tv.ui.screens.settings

/** The six categories the rail is organised into, in rail order. Each has a heading. */
internal enum class SettingsRailGroup {
    PROFILES,
    PLAYBACK,
    SOURCES,
    HOME,
    ACCOUNTS,
    SYSTEM
}

/**
 * Pages that share one rail entry and are picked between with tabs at the top of the pane, so the
 * rail stays short without folding any page into another. The first visible page of a set stands
 * for it in the rail.
 */
internal enum class SettingsTabSet {
    PLAYER,
    ADDONS,
    METADATA,
    HOME,
    ACCOUNTS,
    ADVANCED
}

/**
 * One page of settings, listed under its [group]'s heading. Search has none. Pages with a
 * [tabSet] share a rail entry with the rest of that set (see [settingsRailEntries]). In advanced
 * mode each [playbackPage] is a page of its own, holding several playback sections; essential mode
 * has the single [PLAYBACK] page instead.
 */
internal enum class SettingsCategory(
    val group: SettingsRailGroup?,
    val playbackPage: PlaybackPage? = null,
    val tabSet: SettingsTabSet? = null
) {
    SEARCH(null),
    PROFILES(SettingsRailGroup.PROFILES),
    PLAYBACK(SettingsRailGroup.PLAYBACK),
    PLAYBACK_PLAYER(SettingsRailGroup.PLAYBACK, PlaybackPage.PLAYER_NETWORK, SettingsTabSet.PLAYER),
    ANIME_SKIP(SettingsRailGroup.PLAYBACK, tabSet = SettingsTabSet.PLAYER),
    PLAYBACK_STREAMS(SettingsRailGroup.PLAYBACK, PlaybackPage.STREAMS_AUTO_PLAY),
    PLAYBACK_AUDIO_SUBTITLES(SettingsRailGroup.PLAYBACK, PlaybackPage.AUDIO_SUBTITLES),
    PLAYBACK_VIDEO(SettingsRailGroup.PLAYBACK, PlaybackPage.VIDEO_DISPLAY),
    ADDONS(SettingsRailGroup.SOURCES, tabSet = SettingsTabSet.ADDONS),
    CONTENT_DISCOVERY(SettingsRailGroup.SOURCES, tabSet = SettingsTabSet.ADDONS),
    TMDB(SettingsRailGroup.SOURCES, tabSet = SettingsTabSet.METADATA),
    MDBLIST(SettingsRailGroup.SOURCES, tabSet = SettingsTabSet.METADATA),
    CUSTOMISE_HOME(SettingsRailGroup.HOME, tabSet = SettingsTabSet.HOME),
    APPEARANCE(SettingsRailGroup.HOME),
    LAYOUT(SettingsRailGroup.HOME, tabSet = SettingsTabSet.HOME),
    ACCOUNT(SettingsRailGroup.ACCOUNTS, tabSet = SettingsTabSet.ACCOUNTS),
    TRACKING(SettingsRailGroup.ACCOUNTS, tabSet = SettingsTabSet.ACCOUNTS),
    YOUTUBE(SettingsRailGroup.ACCOUNTS, tabSet = SettingsTabSet.ACCOUNTS),
    PHONE(SettingsRailGroup.SYSTEM),
    ADVANCED(SettingsRailGroup.SYSTEM, tabSet = SettingsTabSet.ADVANCED),
    ABOUT(SettingsRailGroup.SYSTEM),
    DEBUG(SettingsRailGroup.SYSTEM, tabSet = SettingsTabSet.ADVANCED),
    EXPERIENCE(SettingsRailGroup.SYSTEM)
}

/**
 * The pages this profile can open, in rail order. Search looks through these; the rail itself
 * lists [settingsRailEntries] of them.
 */
internal fun visibleSettingsCategories(
    isPrimaryProfile: Boolean,
    isEssentialMode: Boolean,
    isDebugBuild: Boolean,
    isYouTubeAvailable: Boolean = false,
    playbackSections: List<PlaybackSection> = PlaybackSection.entries
): List<SettingsCategory> = SettingsCategory.entries.filter { category ->
    category.playbackPage?.let { page ->
        return@filter !isEssentialMode && page.sections.any { it in playbackSections }
    }
    when (category) {
        SettingsCategory.PLAYBACK -> isEssentialMode
        SettingsCategory.ACCOUNT,
        SettingsCategory.PROFILES -> isPrimaryProfile
        SettingsCategory.YOUTUBE -> isYouTubeAvailable && !isEssentialMode
        SettingsCategory.ANIME_SKIP -> !isEssentialMode
        // Folders and filter rows are Advanced mode's tools, as they were in the addon manager.
        SettingsCategory.CUSTOMISE_HOME -> !isEssentialMode
        SettingsCategory.DEBUG -> isDebugBuild && !isEssentialMode
        SettingsCategory.EXPERIENCE -> false
        else -> true
    }
}

/**
 * The rail's entries for these visible pages: every page without a tab set, and the first page of
 * each tab set standing for the rest of it.
 */
internal fun List<SettingsCategory>.settingsRailEntries(): List<SettingsCategory> =
    distinctBy { category -> category.tabSet ?: category }

/** The rail entry that opens [page], among these visible pages. */
internal fun List<SettingsCategory>.railEntryFor(page: SettingsCategory): SettingsCategory {
    val tabSet = page.tabSet ?: return page
    return firstOrNull { it.tabSet == tabSet } ?: page
}

/** The pages [entry] shows as tabs, in order; just [entry] itself when it has no tab set. */
internal fun List<SettingsCategory>.tabsOf(entry: SettingsCategory): List<SettingsCategory> {
    val tabSet = entry.tabSet ?: return listOf(entry)
    return filter { it.tabSet == tabSet }.ifEmpty { listOf(entry) }
}

/** Whether the entry at [index] is the first of its category, and so sits under its heading. */
internal fun List<SettingsCategory>.startsNewGroup(index: Int): Boolean {
    val group = this[index].group ?: return false
    return index == 0 || this[index - 1].group != group
}
