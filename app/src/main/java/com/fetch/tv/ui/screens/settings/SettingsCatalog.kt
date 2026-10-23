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

/** One rail entry: a page of settings, listed under its [group]'s heading. Search has none. */
internal enum class SettingsCategory(val group: SettingsRailGroup?) {
    SEARCH(null),
    PROFILES(SettingsRailGroup.PROFILES),
    WATCH_PARTY(SettingsRailGroup.PROFILES),
    PLAYBACK(SettingsRailGroup.PLAYBACK),
    ANIME_SKIP(SettingsRailGroup.PLAYBACK),
    CONTENT_DISCOVERY(SettingsRailGroup.SOURCES),
    TMDB(SettingsRailGroup.SOURCES),
    MDBLIST(SettingsRailGroup.SOURCES),
    APPEARANCE(SettingsRailGroup.HOME),
    LAYOUT(SettingsRailGroup.HOME),
    ACCOUNT(SettingsRailGroup.ACCOUNTS),
    TRACKING(SettingsRailGroup.ACCOUNTS),
    PHONE(SettingsRailGroup.SYSTEM),
    ADVANCED(SettingsRailGroup.SYSTEM),
    ABOUT(SettingsRailGroup.SYSTEM),
    DEBUG(SettingsRailGroup.SYSTEM),
    EXPERIENCE(SettingsRailGroup.SYSTEM)
}

internal fun visibleSettingsCategories(
    isPrimaryProfile: Boolean,
    isEssentialMode: Boolean,
    isDebugBuild: Boolean,
    isWatchPartyAvailable: Boolean = false
): List<SettingsCategory> = SettingsCategory.entries.filter { category ->
    when (category) {
        SettingsCategory.ACCOUNT,
        SettingsCategory.PROFILES -> isPrimaryProfile
        SettingsCategory.WATCH_PARTY -> isWatchPartyAvailable
        SettingsCategory.DEBUG -> isDebugBuild && !isEssentialMode
        SettingsCategory.EXPERIENCE -> false
        else -> true
    }
}

/** Whether the entry at [index] is the first of its category, and so sits under its heading. */
internal fun List<SettingsCategory>.startsNewGroup(index: Int): Boolean {
    val group = this[index].group ?: return false
    return index == 0 || this[index - 1].group != group
}
