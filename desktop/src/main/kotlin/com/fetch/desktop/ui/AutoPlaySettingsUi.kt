package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.runtime.Composable
import com.fetch.desktop.AppState
import com.fetch.desktop.data.AutoPlayMode
import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.desktop.data.NextEpisodeThresholdMode

private fun AppState.updateAutoPlay(change: (AutoPlaySettings) -> AutoPlaySettings) =
    updateSettings { it.copy(autoPlay = change(it.autoPlay)) }

private fun halfSteps(from: Float, to: Float): List<Float> = generateSequence(from) { it + 0.5f }.takeWhile { it <= to + 0.001f }.toList()

private fun number(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else value.toString()

/**
 * Stream Selection › When you press Play, the TV's Auto Stream Selection: the timeout, Start
 * without waiting, Reuse Last Link, the pick on the Play button, and the next source on failure.
 */
@Composable
fun StreamAutoPlaySettings(state: AppState) {
    val s = state.settings
    val ap = s.autoPlay
    SettingsChoice(
        "Stream selection timeout",
        "How long Play waits for your addons before picking from what's in. Instant takes the first answer; Unlimited waits for every addon (at most a minute).",
        AutoPlaySettings.normalizeTimeout(s.addonTimeoutSeconds),
        AutoPlaySettings.TIMEOUT_VALUES.map { DropdownOption(it, AutoPlaySettings.timeoutLabel(it)) }
    ) { v -> state.updateSettings { it.copy(addonTimeoutSeconds = v) } }
    if (s.autoPlayMode == AutoPlayMode.BEST_MATCH) {
        SettingsToggle(
            "Start without waiting for every addon",
            "Plays the best source so far as soon as it reaches your preferred quality, and is cached when you prefer cached sources. A slower addon may have had a better one. Torrents never start early.",
            ap.startEarly
        ) { v -> state.updateAutoPlay { it.copy(startEarly = v) } }
    }
    if (s.autoPlayMode != AutoPlayMode.MANUAL) AllowedAutoPlayAddons(state)
    SettingsToggle(
        "Reuse last link",
        "Play your last working link for the same movie or episode while it's still fresh.",
        ap.reuseLastLink
    ) { v -> state.updateAutoPlay { it.copy(reuseLastLink = v) } }
    if (ap.reuseLastLink) {
        SettingsChoice(
            "Last link cache duration",
            null,
            ap.reuseLastLinkCacheHours,
            (AutoPlaySettings.CACHE_HOURS + ap.reuseLastLinkCacheHours).distinct().sorted().map { DropdownOption(it, AutoPlaySettings.cacheLabel(it)) }
        ) { v -> state.updateAutoPlay { it.copy(reuseLastLinkCacheHours = v) } }
    }
    SettingsToggle(
        "Show the pick on the Play button",
        "Asks your stream addons about a title while you look at it, so the Play button can name the file and playback starts sooner. Off, they're asked when you press Play.",
        ap.bestMatchPreview
    ) { v -> state.updateAutoPlay { it.copy(bestMatchPreview = v) } }
    SettingsToggle(
        "Try the next source when one fails",
        "When the built-in player can't open a source, the next one in the ranking plays instead.",
        ap.nextSourceOnFailure
    ) { v -> state.updateAutoPlay { it.copy(nextSourceOnFailure = v) } }
}

/**
 * The TV's "Allowed addons for autoplay": off, auto-play picks from every addon; on, only from
 * the addons left switched on (and your media servers). Picking a source by hand isn't limited.
 * Saved by addon name and synced under the TV's `stream_auto_play_selected_addons`.
 */
@Composable
private fun AllowedAutoPlayAddons(state: AppState) {
    val allowed = state.settings.autoPlay.allowedAddons
    val installed = state.addons.filter { it.enabled }.map { it.displayName }.distinct()
    // Addons chosen on the TV that aren't installed here stay in the set, so they still sync.
    val missing = allowed.filter { it !in installed }
    SettingsToggle(
        "Limit autoplay to chosen addons",
        if (allowed.isEmpty()) "Autoplay picks from all addons."
        else "Autoplay picks only from the ${allowed.size} chosen ${if (allowed.size == 1) "addon" else "addons"} and your media servers. Choosing a source yourself isn't limited.",
        allowed.isNotEmpty()
    ) { on -> state.updateAutoPlay { it.copy(allowedAddons = if (on) installed.toSet() else emptySet()) } }
    if (allowed.isNotEmpty()) {
        SettingsSection("Allowed addons for autoplay") {
            (installed + missing).forEach { name ->
                SettingsToggle(name, if (name in missing) "Not installed on this computer" else null, name in allowed) { on ->
                    // Switching the last one off goes back to every addon, as on TV (empty means all).
                    state.updateAutoPlay { it.copy(allowedAddons = if (on) it.allowedAddons + name else it.allowedAddons - name) }
                }
            }
        }
    }
}

/** Up Next's TV settings beyond autoplay: the threshold, preloading, binge groups and the movie recommendation. */
@Composable
fun UpNextAutoPlaySettings(state: AppState) {
    val ap = state.settings.autoPlay
    val autoplay = state.settings.player.autoplayNextEpisode
    SettingsSection("Next episode threshold") {
        SettingsChoice(
            "Threshold mode",
            "When the next episode's card shows and, with autoplay, the next episode starts. Credits times from IntroDB come first.",
            ap.nextEpisodeThresholdMode,
            NextEpisodeThresholdMode.entries.map { DropdownOption(it, it.label) }
        ) { v -> state.updateAutoPlay { it.copy(nextEpisodeThresholdMode = v) } }
        when (ap.nextEpisodeThresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE -> SettingsChoice(
                "Threshold percentage",
                "Show at this share of the episode when no credits time is known.",
                AutoPlaySettings.halfStep(ap.nextEpisodeThresholdPercent, 97f, 100f),
                halfSteps(97f, 100f).map { DropdownOption(it, "${number(it)}%") }
            ) { v -> state.updateAutoPlay { it.copy(nextEpisodeThresholdPercent = v) } }
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> SettingsChoice(
                "Threshold minutes",
                "Show this many minutes before the end when no credits time is known.",
                AutoPlaySettings.halfStep(ap.nextEpisodeThresholdMinutesBeforeEnd, 0f, 3.5f),
                halfSteps(0f, 3.5f).map { DropdownOption(it, tr("%1\$s min", number(it))) }
            ) { v -> state.updateAutoPlay { it.copy(nextEpisodeThresholdMinutesBeforeEnd = v) } }
        }
        if (autoplay) {
            SettingsToggle(
                "Preload next episode sources",
                "Look for the next episode's sources in the background before its card appears.",
                ap.preloadNextEpisode
            ) { v -> state.updateAutoPlay { it.copy(preloadNextEpisode = v) } }
        }
    }
    if (autoplay) {
        SettingsSection("Binge group") {
            SettingsToggle(
                "Prefer binge group (next episode)",
                "Try the same source profile first (same addon and quality group) before the usual auto-play rules.",
                ap.preferBingeGroup
            ) { v -> state.updateAutoPlay { it.copy(preferBingeGroup = v) } }
            if (ap.preferBingeGroup) {
                SettingsToggle(
                    "Reuse binge group",
                    "Remember a show's last binge group across sessions (Continue Watching, title pages).",
                    ap.reuseBingeGroup
                ) { v -> state.updateAutoPlay { it.copy(reuseBingeGroup = v) } }
                SettingsToggle(
                    "Fallback when binge group fails",
                    "Pick the usual way when the binge group isn't found. Off, the episode's sources open instead.",
                    ap.nextEpisodeFallback
                ) { v -> state.updateAutoPlay { it.copy(nextEpisodeFallback = v) } }
            }
        }
    }
    SettingsSection("After a movie") {
        SettingsToggle(
            "Post-play recommendations",
            "A recommendation from TMDB near the end of a movie, with its trailer after the credits. Needs Settings › TMDB with More like this.",
            ap.postPlayRecommendations
        ) { v -> state.updateAutoPlay { it.copy(postPlayRecommendations = v) } }
        if (ap.postPlayRecommendations) {
            SettingsChoice(
                "Movie recommendation timing",
                "When the recommendation appears, unless IntroDB knows the credits. Episodes follow the next episode threshold.",
                ap.postPlayMovieThresholdPercent.coerceIn(AutoPlaySettings.MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT, AutoPlaySettings.MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT),
                ((AutoPlaySettings.MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT..AutoPlaySettings.MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT step 2) + ap.postPlayMovieThresholdPercent)
                    .filter { it in AutoPlaySettings.MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT..AutoPlaySettings.MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT }
                    .distinct().sorted().map { DropdownOption(it, "$it%") }
            ) { v -> state.updateAutoPlay { it.copy(postPlayMovieThresholdPercent = v) } }
        }
    }
}

/** Player › Content warnings (the TV's parental guide overlay). */
@Composable
fun ContentWarningsSetting(state: AppState) {
    SettingsToggle(
        "Content warnings",
        "As playback starts in the built-in player, the rating and IMDb's parents guide (violence, language and the like).",
        state.settings.autoPlay.parentalGuide
    ) { v -> state.updateAutoPlay { it.copy(parentalGuide = v) } }
}
