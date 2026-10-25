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
 * One rail entry: a page of settings, listed under its [group]'s heading. Search has none. In
 * advanced mode each [playbackSection] is an entry of its own; essential mode has the single
 * [PLAYBACK] page instead.
 */
internal enum class SettingsCategory(
    val group: SettingsRailGroup?,
    val playbackSection: PlaybackSection? = null
) {
    SEARCH(null),
    PROFILES(SettingsRailGroup.PROFILES),
    WATCH_PARTY(SettingsRailGroup.PROFILES),
    PLAYBACK(SettingsRailGroup.PLAYBACK),
    PLAYBACK_PLAYER(SettingsRailGroup.PLAYBACK, PlaybackSection.PLAYER),
    PLAYBACK_STREAM_SELECTION(SettingsRailGroup.PLAYBACK, PlaybackSection.STREAM_SELECTION),
    PLAYBACK_UP_NEXT(SettingsRailGroup.PLAYBACK, PlaybackSection.UP_NEXT),
    PLAYBACK_SKIP_SEGMENTS(SettingsRailGroup.PLAYBACK, PlaybackSection.SKIP_SEGMENTS),
    PLAYBACK_PLAYER_INTERFACE(SettingsRailGroup.PLAYBACK, PlaybackSection.PLAYER_INTERFACE),
    PLAYBACK_AUDIO(SettingsRailGroup.PLAYBACK, PlaybackSection.AUDIO),
    PLAYBACK_SUBTITLES(SettingsRailGroup.PLAYBACK, PlaybackSection.SUBTITLES),
    PLAYBACK_VIDEO(SettingsRailGroup.PLAYBACK, PlaybackSection.VIDEO),
    PLAYBACK_BUFFER_NETWORK(SettingsRailGroup.PLAYBACK, PlaybackSection.BUFFER_NETWORK),
    PLAYBACK_P2P(SettingsRailGroup.PLAYBACK, PlaybackSection.P2P),
    ANIME_SKIP(SettingsRailGroup.PLAYBACK),
    CONTENT_DISCOVERY(SettingsRailGroup.SOURCES),
    TMDB(SettingsRailGroup.SOURCES),
    MDBLIST(SettingsRailGroup.SOURCES),
    APPEARANCE(SettingsRailGroup.HOME),
    LAYOUT(SettingsRailGroup.HOME),
    ACCOUNT(SettingsRailGroup.ACCOUNTS),
    TRACKING(SettingsRailGroup.ACCOUNTS),
    YOUTUBE(SettingsRailGroup.ACCOUNTS),
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
    isWatchPartyAvailable: Boolean = false,
    isYouTubeAvailable: Boolean = false,
    playbackSections: List<PlaybackSection> = PlaybackSection.entries
): List<SettingsCategory> = SettingsCategory.entries.filter { category ->
    category.playbackSection?.let { section ->
        return@filter !isEssentialMode && section in playbackSections
    }
    when (category) {
        SettingsCategory.PLAYBACK -> isEssentialMode
        SettingsCategory.ACCOUNT,
        SettingsCategory.PROFILES -> isPrimaryProfile
        SettingsCategory.WATCH_PARTY -> isWatchPartyAvailable
        SettingsCategory.YOUTUBE -> isYouTubeAvailable
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
