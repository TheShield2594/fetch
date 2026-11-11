package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppInfo
import com.fetch.desktop.AppState
import com.fetch.desktop.data.AutoPlayMode
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.player.MpvArgs
import com.fetch.desktop.player.embedded.LibMpv
import com.fetch.tv.domain.model.ContinueWatchingSortMode
import com.fetch.tv.domain.model.ExperienceMode

private fun AppState.updatePlayerSettings(change: (PlayerSettings) -> PlayerSettings) =
    updateSettings { it.copy(player = change(it.player)) }

private val Languages: List<DropdownOption<String?>> = MpvArgs.languages.map { DropdownOption<String?>(it.code, it.label) }

/** "Switch to Essential?" / "Switch to Advanced?", as the TV's ExperienceModeConfirmationDialog. */
@Composable
fun ExperienceModeConfirmationDialog(target: ExperienceMode, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val essential = target == ExperienceMode.ESSENTIAL
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(if (essential) tr("Switch to Essential?") else tr("Switch to Advanced?"), color = FetchPalette.TextPrimary) },
        text = {
            Text(
                if (essential) tr("Advanced setup surfaces will be hidden, but your saved settings stay unchanged. You can switch back anytime.")
                else tr("This reveals the full settings surface, including advanced setup, catalog, Home folder, and tuning controls."),
                color = FetchPalette.TextSecondary
            )
        },
        confirmButton = {
            PrimaryButton(if (essential) "Switch to Essential" else "Switch to Advanced", icon = null, onClick = {
                onConfirm()
                onDismiss()
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
}

/** Sets Essential or Advanced; saved values stay as they are. */
fun AppState.setExperienceMode(mode: ExperienceMode) =
    updateSettings { it.copy(experience = it.experience.copy(mode = mode)) }

/**
 * System › Advanced: the Essential / Advanced switch (with the TV's confirmation) and the
 * first-run setup, to run it again.
 */
@Composable
fun AdvancedSettingsPage(state: AppState) {
    val essential = state.settings.experience.isEssential
    var confirming by remember { mutableStateOf<ExperienceMode?>(null) }
    PageHeader("Advanced", subtitle = if (essential) "Switch to Advanced for the full settings surface." else "Experience mode and setup.")
    SettingsSection("Experience mode") {
        SettingsRow(
            "Experience mode",
            if (essential) "Essential: setup, addons, playback basics, Trakt and account settings. Switch to Advanced to show full layout, integration, catalog and tuning settings."
            else "Advanced: every setting. Switch to Essential to hide advanced setup surfaces without changing your saved values."
        ) {
            FilterDropdown(
                label = "",
                selected = if (essential) ExperienceMode.ESSENTIAL else ExperienceMode.ADVANCED,
                options = listOf(DropdownOption(ExperienceMode.ESSENTIAL, "Essential"), DropdownOption(ExperienceMode.ADVANCED, "Advanced")),
                onSelect = { mode -> if ((mode == ExperienceMode.ESSENTIAL) != essential) confirming = mode }
            )
        }
    }
    SettingsSection("Setup") {
        SettingsRow(
            "First-run setup",
            "Go through Server, Sources, Metadata and Experience again. Nothing is reset.",
            onClick = { FirstRunState.reopen() }
        ) { Icon(FetchIcons.ChevronRight, contentDescription = null, tint = FetchPalette.TextMuted) }
    }
    confirming?.let { target ->
        ExperienceModeConfirmationDialog(target, onConfirm = { state.setExperienceMode(target) }, onDismiss = { confirming = null })
    }
}

/**
 * Essential's one Playback page, as the TV's EssentialPlaybackSettingsContent: the player, Manual
 * or Best match, next-episode autoplay, and subtitle and audio languages.
 */
@Composable
fun PlaybackBasicsPage(state: AppState) {
    val s = state.settings
    val p = s.player
    PageHeader("Playback", subtitle = "Simple playback, subtitle and audio preferences.")
    SettingsSection("Playback basics") {
        val libmpv = remember { LibMpv.available }
        SettingsChoice(
            "Default Player",
            when {
                p.askPlayer -> "Fetch asks each time you press Play."
                p.builtInPlayer && !libmpv -> "libmpv isn't installed (sudo apt install libmpv2), so mpv's window is used."
                p.builtInPlayer -> "Inside Fetch's window, with Fetch's controls."
                else -> "mpv, in its own window."
            },
            if (p.askPlayer) null else p.builtInPlayer,
            listOf(DropdownOption<Boolean?>(false, "mpv window"), DropdownOption<Boolean?>(true, "Built-in (beta)"), DropdownOption<Boolean?>(null, "Ask every time"))
        ) { v -> state.updatePlayerSettings { it.copy(builtInPlayer = v ?: it.builtInPlayer, askPlayer = v == null) } }
        // The TV's Essential toggle: Manual, or let Fetch pick (Best match). Other modes show as they are.
        SettingsChoice(
            "Stream selection",
            "Choose manually or autoplay the best match.",
            s.autoPlayMode,
            buildList {
                add(DropdownOption(AutoPlayMode.MANUAL, "Manual"))
                add(DropdownOption(AutoPlayMode.BEST_MATCH, "Best match"))
                if (s.autoPlayMode != AutoPlayMode.MANUAL && s.autoPlayMode != AutoPlayMode.BEST_MATCH) add(DropdownOption(s.autoPlayMode, s.autoPlayMode.label))
            }
        ) { v -> state.updateSettings { it.copy(autoPlayMode = v) } }
        SettingsToggle("Auto-play next episode", "Continue to the next episode after a stream finishes.", p.autoplayNextEpisode) { v ->
            state.updatePlayerSettings { it.copy(autoplayNextEpisode = v) }
        }
    }
    SettingsSection("Subtitles and audio") {
        SettingsChoice("Subtitle language", "Preferred subtitle language.", p.subtitleLanguage, listOf(DropdownOption<String?>(null, "Device language")) + Languages) { v ->
            state.updatePlayerSettings { it.copy(subtitleLanguage = v) }
        }
        SettingsToggle("Use forced subtitles", "Show the forced track (signs, lines in another language) when no other subtitle is picked.", p.useForcedSubtitles) { v ->
            state.updatePlayerSettings { it.copy(useForcedSubtitles = v) }
        }
        SettingsChoice("Subtitle size", null, p.subtitleScale, listOf(0.75, 1.0, 1.25, 1.5, 2.0).map { DropdownOption(it, "${(it * 100).toInt()}%") }) { v ->
            state.updatePlayerSettings { it.copy(subtitleScale = v) }
        }
        SettingsChoice("Audio language", "Preferred audio language.", p.audioLanguage, listOf(DropdownOption<String?>(null, "Default (media file)")) + Languages) { v ->
            state.updatePlayerSettings { it.copy(audioLanguage = v) }
        }
    }
}

/** Essential's Layout: Continue Watching, its order, unreleased titles and where Discover lives. */
@Composable
fun EssentialLayoutPage(state: AppState) {
    val s = state.settings
    PageHeader("Layout", subtitle = "Switch to Advanced (System › Advanced) for every layout setting.")
    SettingsSection("Home") {
        SettingsToggle("Show Continue Watching", "The row of titles you started, first on Home.", s.showContinueWatching) { v ->
            state.updateSettings { it.copy(showContinueWatching = v) }
        }
        if (s.showContinueWatching) {
            SettingsChoice(
                "Sort Order",
                null,
                s.continueWatchingSort,
                listOf(
                    DropdownOption(ContinueWatchingSortMode.DEFAULT, "Default"),
                    DropdownOption(ContinueWatchingSortMode.STREAMING_STYLE, "Streaming Style"),
                    DropdownOption(ContinueWatchingSortMode.SPLIT_UPCOMING, "Separate Upcoming Row")
                )
            ) { v -> state.updateSettings { it.copy(continueWatchingSort = v) } }
        }
        SettingsToggle("Hide unreleased content", "Leave titles that aren't out yet off Home's rows.", s.hideUnreleased) { v ->
            state.updateSettings { it.copy(hideUnreleased = v) }
        }
    }
    SettingsSection("Navigation") {
        SettingsChoice("Discover Location", "Where Discover lives.", s.discoverInSidebar, listOf(DropdownOption(false, "In Search"), DropdownOption(true, "Show in Side Panel"))) { v ->
            state.updateSettings { it.copy(discoverInSidebar = v) }
        }
    }
}

/** Essential's TMDB: on or off, and a key of your own. */
@Composable
fun EssentialTmdbPage(state: AppState) {
    val t = state.settings.tmdb
    PageHeader("TMDB", subtitle = "Richer title pages from The Movie Database.")
    SettingsSection(null) {
        SettingsToggle("Use TMDB", "Artwork, cast, episode titles and trailers on top of what addons give.", t.enabled) { v ->
            state.updateSettings { it.copy(tmdb = it.tmdb.copy(enabled = v)) }
        }
        SettingsTextRow("Your TMDB key", "An API key (v3) or read access token (v4). Leave empty to use the build's.", state.settings.tmdbApiKey.orEmpty()) { key ->
            state.updateSettings { it.copy(tmdbApiKey = key.ifBlank { null }) }
        }
    }
}

/** Essential's MDBList: ratings on or off, and the API key. */
@Composable
fun EssentialMdbListPage(state: AppState) {
    val m = state.settings.mdblist
    PageHeader("MDBList", subtitle = "Ratings from IMDb, Rotten Tomatoes, Metacritic, Letterboxd and more on title pages.")
    SettingsSection("Ratings") {
        SettingsToggle("Enable MDBList ratings", "Show ratings from other sites on title pages.", m.enabled) { v ->
            state.updateSettings { it.copy(mdblist = it.mdblist.copy(enabled = v)) }
        }
        ApiKeyRow(
            value = m.apiKey,
            description = "Your key from mdblist.com › Preferences.",
            verify = state.mdbListRatings::verify,
            onSave = { key -> state.updateSettings { it.copy(mdblist = it.mdblist.copy(apiKey = key)) } }
        )
    }
}

/** Essential's About: the version, updates and the legal links. */
@Composable
fun EssentialAboutPage(state: AppState) {
    val uri = LocalUriHandler.current
    val updates = state.updates
    val s = state.settings
    PageHeader("About", subtitle = "Fetch for desktop")
    SettingsSection(null) {
        SettingsRow(tr("Version %1\$s", AppInfo.version), "Same version as the TV app.")
    }
    SettingsSection("Updates") {
        SettingsToggle("Check for updates", "Look for a newer Fetch on GitHub at start-up.", s.checkForUpdates) { v -> state.updateSettings { it.copy(checkForUpdates = v) } }
        SettingsRow(if (updates.checking) "Checking…" else "Check now", updates.status, onClick = { updates.dismissed = false; updates.check(beta = s.betaUpdates) })
        updates.available?.let { update ->
            SettingsRow("Download Fetch ${update.version}", update.packageUrl ?: update.pageUrl, onClick = { state.openInBrowser(update.packageUrl ?: update.pageUrl) })
        }
    }
    SettingsSection("Legal") {
        SettingsRow("Privacy Policy", "No telemetry, no required account.", onClick = { uri.openUri(AppInfo.PRIVACY_URL) })
        SettingsRow("Licenses & Attribution", null, onClick = { uri.openUri(AppInfo.LICENSE_URL) })
    }
}

/** A free-text setting, saved when the field loses focus. */
@Composable
internal fun SettingsTextRow(title: String, description: String?, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    SettingsRow(title, description) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = FetchPalette.TextPrimary, fontSize = 15.sp),
            cursorBrush = SolidColor(FetchPalette.Accent),
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(FetchPalette.Background)
                .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .onFocusChanged { focus -> if (!focus.isFocused && text.trim() != value) onChange(text.trim()) }
        )
    }
}
