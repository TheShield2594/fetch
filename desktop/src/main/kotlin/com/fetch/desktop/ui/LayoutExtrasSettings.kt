package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.data.LayoutPrefs
import com.fetch.desktop.data.TmdbSettings
import com.fetch.desktop.home.HomeLayout
import com.fetch.desktop.poster.CustomPosterScreen
import com.fetch.tv.domain.model.ContinueWatchingCardStyle
import com.fetch.tv.domain.model.DetailImdbRatingsVisibility
import com.fetch.tv.domain.model.EpisodeOptionsOverlayStyle
import com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.fetch.tv.domain.model.HomeImdbRatingsVisibility
import com.fetch.tv.domain.model.THEME_CARD_CORNER_RADIUS

private fun AppState.updateLayout(change: (LayoutPrefs) -> LayoutPrefs) = updateSettings { it.copy(layout = change(it.layout)) }

/**
 * Layout's sections beyond Home basics, as the TV's Layout settings (`LayoutHomeSettings`,
 * `LayoutCardSettings`, `LayoutDetailSettings` and the trailer settings): Continue Watching
 * cards, the hero, Poster Card, Focused Poster, Detail Page and Custom Poster Source.
 */
@Composable
fun LayoutExtrasSections(state: AppState) {
    val l = state.settings.layout
    val update = state::updateLayout
    Box(Modifier.padding(top = 8.dp))
    SettingsSection("Continue Watching") {
        SettingsChoice(
            "Card Style",
            when (l.continueWatchingCardStyle) {
                ContinueWatchingCardStyle.CARD -> "Landscape cards with the title underneath."
                ContinueWatchingCardStyle.WIDE -> "Wider cards with the title on the artwork."
                ContinueWatchingCardStyle.POSTER -> "Posters, like the catalog rows."
            },
            l.continueWatchingCardStyle,
            listOf(
                DropdownOption(ContinueWatchingCardStyle.CARD, "Card"),
                DropdownOption(ContinueWatchingCardStyle.WIDE, "Wide"),
                DropdownOption(ContinueWatchingCardStyle.POSTER, "Poster")
            )
        ) { v -> update { it.copy(continueWatchingCardStyle = v) } }
        SettingsToggle(
            "Use Episode Thumbnails in Continue Watching",
            "Use episode thumbnails as default image. When disabled, uses backdrop.",
            l.episodeThumbnailsInContinueWatching
        ) { v -> update { it.copy(episodeThumbnailsInContinueWatching = v) } }
        SettingsToggle(
            "Blur Unwatched in Continue Watching",
            "Blur unwatched episode thumbnails in Continue Watching to avoid spoilers.",
            l.blurContinueWatchingNextUp
        ) { v -> update { it.copy(blurContinueWatchingNextUp = v) } }
        SettingsToggle(
            "Check what's ready in the background",
            "Asks your addons about up to 6 titles from Continue Watching and your Watchlist, each at most every 12 hours, " +
                "so Ready tonight can show the ones that start instantly. Needs Best match as the Play mode.",
            l.readyCheckEnabled
        ) { v -> update { it.copy(readyCheckEnabled = v) } }
    }
    SettingsSection("Hero") {
        SettingsToggle("Show Hero", "The large title at the top of Home.", l.heroEnabled) { v -> update { it.copy(heroEnabled = v) } }
        if (l.heroEnabled) {
            SettingsToggle("Fullscreen Hero Backdrop", "Expand the hero backdrop to fill the entire screen.", l.fullScreenHeroBackdrop) { v ->
                update { it.copy(fullScreenHeroBackdrop = v) }
            }
            SettingsRow("Hero Catalogs", "The hero cycles through titles from these rows. None: Continue Watching, then the first row.")
            ChipPicker(
                options = state.rows.filter { it.filter == null }.map { DropdownOption(it.tvKey(), "${state.homeRowTitle(it)} · ${it.addon.displayName}") },
                selected = l.heroCatalogKeys,
                onChange = { keys -> update { it.copy(heroCatalogKeys = keys) } },
                ordered = true
            )
        }
        SettingsChoice(
            "Overall Ratings",
            if (l.homeRatings.showRatings) "Standard and TMDB ratings are shown." else "Standard and TMDB ratings are hidden on Home.",
            l.homeRatings,
            listOf(DropdownOption(HomeImdbRatingsVisibility.SHOW_ALL, "Show"), DropdownOption(HomeImdbRatingsVisibility.HIDE_ALL, "Hide"))
        ) { v -> update { it.copy(homeRatings = v) } }
        SettingsToggle("Show full release date", "For movies, show full release date instead of year only.", l.showFullReleaseDate) { v ->
            update { it.copy(showFullReleaseDate = v) }
        }
    }
    SettingsSection("Poster Card") {
        SettingsToggle("Landscape Poster Mode", "Use landscape cards everywhere.", l.landscapePosters) { v -> update { it.copy(landscapePosters = v) } }
        SettingsToggle(
            "Always Backdrop with Logo for Landscape Posters",
            "Ignore landscape poster from addon and use backdrop with clearlogo overlay instead.",
            l.alwaysShowLandscapeLogo
        ) { v -> update { it.copy(alwaysShowLandscapeLogo = v) } }
        SettingsChoice(
            "Card Width",
            "Poster cards' width (TV sizes; 126 is the default).",
            l.posterCardWidthDp,
            (listOf(104, 114, 126, 138, 150, 164) + l.posterCardWidthDp).distinct().sorted().map { DropdownOption(it, if (it == LayoutPrefs.TV_POSTER_WIDTH_DP) "$it (default)" else "$it") }
        ) { v -> update { it.copy(posterCardWidthDp = v) } }
        SettingsChoice(
            "Card Height",
            "Poster cards' height (189 is the default).",
            l.posterCardHeightDp,
            (listOf(156, 171, 189, 207, 225, 246) + l.posterCardHeightDp).distinct().sorted().map { DropdownOption(it, if (it == LayoutPrefs.TV_POSTER_HEIGHT_DP) "$it (default)" else "$it") }
        ) { v -> update { it.copy(posterCardHeightDp = v) } }
        SettingsChoice(
            "Corner Radius",
            null,
            l.posterCardCornerRadiusDp,
            (listOf(THEME_CARD_CORNER_RADIUS, 0, 4, 8, 12, 16) + l.posterCardCornerRadiusDp).distinct()
                .map { DropdownOption(it, if (it == THEME_CARD_CORNER_RADIUS) "Theme" else "$it") }
        ) { v -> update { it.copy(posterCardCornerRadiusDp = v) } }
    }
    SettingsSection("Focused Poster") {
        SettingsToggle("Expand Focused Poster to Backdrop", "Expand the poster under the pointer after a pause.", l.expandFocusedPoster) { v ->
            update { it.copy(expandFocusedPoster = v) }
        }
        if (l.expandFocusedPoster) {
            SettingsChoice(
                "Backdrop Expand Delay",
                "How long to wait before expanding a card.",
                l.expandFocusedPosterDelaySeconds,
                (listOf(1, 2, 3, 5, 8) + l.expandFocusedPosterDelaySeconds).distinct().sorted().map { DropdownOption(it, "$it s") }
            ) { v -> update { it.copy(expandFocusedPosterDelaySeconds = v) } }
        }
        SettingsToggle("Autoplay Trailer", "Play a trailer in the hero for the title you point at, when it has one (needs libmpv and yt-dlp).", l.focusedPosterTrailer) { v ->
            update { it.copy(focusedPosterTrailer = v) }
        }
        if (l.focusedPosterTrailer) {
            SettingsToggle("Play Trailer Muted", "Mute trailer audio during autoplay preview.", l.focusedPosterTrailerMuted) { v ->
                update { it.copy(focusedPosterTrailerMuted = v) }
            }
            SettingsChoice(
                "Trailer Delay",
                "How long a title stays in the hero before its trailer starts.",
                l.trailerDelaySeconds,
                (listOf(2, 3, 5, 7, 10, 15) + l.trailerDelaySeconds).distinct().sorted().map { DropdownOption(it, "$it s") }
            ) { v -> update { it.copy(trailerDelaySeconds = v) } }
            SettingsToggle(
                "Crop trailers to fill",
                "Zoom background trailers so they fill the frame. Turn off to see the whole picture with black bars.",
                l.trailerCropToFill
            ) { v -> update { it.copy(trailerCropToFill = v) } }
        }
    }
    SettingsSection("Detail Page") {
        SettingsChoice(
            "Episode Options Overlay",
            when (l.episodeOptionsOverlay) {
                EpisodeOptionsOverlayStyle.BLUR -> "Blurred episode artwork behind an episode's sources."
                EpisodeOptionsOverlayStyle.ARTWORK -> "The episode's artwork behind its sources."
                EpisodeOptionsOverlayStyle.NONE -> "A plain dark scrim behind an episode's sources."
            },
            l.episodeOptionsOverlay,
            listOf(
                DropdownOption(EpisodeOptionsOverlayStyle.BLUR, "Blur"),
                DropdownOption(EpisodeOptionsOverlayStyle.ARTWORK, "Artwork"),
                DropdownOption(EpisodeOptionsOverlayStyle.NONE, "None")
            )
        ) { v -> update { it.copy(episodeOptionsOverlay = v) } }
        SettingsToggle("Shuffle", "Shuffle episodes from any series. Turning this off pauses shuffle.", l.randomEpisodeEnabled) { v ->
            update { it.copy(randomEpisodeEnabled = v) }
        }
        SettingsToggle("Blur Unwatched Episodes", "Blur episode thumbnails until watched to avoid spoilers.", l.blurUnwatchedEpisodes) { v ->
            update { it.copy(blurUnwatchedEpisodes = v) }
        }
        SettingsChoice(
            "Episode Ratings",
            "Choose which episode ratings are visible.",
            l.detailRatings,
            listOf(
                DropdownOption(DetailImdbRatingsVisibility.SHOW_ALL, "Show"),
                DropdownOption(DetailImdbRatingsVisibility.HIDE_UNWATCHED_EPISODES, "Hide Unwatched"),
                DropdownOption(DetailImdbRatingsVisibility.HIDE_EPISODES, "Hide")
            )
        ) { v -> update { it.copy(detailRatings = v) } }
        SettingsToggle("Show Trailer Button", "Show trailer button on detail page (only when trailer is available).", l.detailTrailerButton) { v ->
            update { it.copy(detailTrailerButton = v) }
        }
        SettingsToggle("Prefer meta from external addon", "Use metadata from external addon instead of catalog addon.", l.preferExternalMeta) { v ->
            update { it.copy(preferExternalMeta = v) }
        }
    }
    SettingsSection("Custom Poster Source") {
        LayoutTextRow(
            "URL Pattern",
            "A poster URL pattern from an external service. If a placeholder has no value for a title, its own poster is used.",
            l.customPosterUrlPattern,
            width = 420.dp
        ) { v -> update { it.copy(customPosterUrlPattern = v) } }
        SettingsRow(
            "Placeholders",
            "{id}, {id_type}, {typed_id}, {type}, {shape}, {imdb_id}, {tmdb_id}, {tvdb_id}, {kitsu_id}. {imdb_id|tmdb_id} takes the first a title has; " +
                "add ? for optional ({imdb_id?}). Landscape cards only get custom art with {shape}."
        )
        if (l.customPosterUrlPattern.isNotBlank()) {
            SettingsRow("Apply to", null)
            val selected = CustomPosterScreen.shown.filter { l.customPosterScreens == null || it.key in l.customPosterScreens }
            ChipPicker(
                options = CustomPosterScreen.shown.map { DropdownOption(it, it.label) },
                selected = selected,
                onChange = { picked ->
                    val keys = picked.map { it.key }.toSet() + (l.customPosterScreens.orEmpty().filter { it == CustomPosterScreen.TV_RECOMMENDATIONS.key })
                    val all = CustomPosterScreen.entries.map { it.key }.toSet()
                    update { it.copy(customPosterScreens = if (l.customPosterScreens == null && picked.size == CustomPosterScreen.shown.size) null else keys.takeUnless { k -> k.containsAll(all) }) }
                }
            )
        }
    }
}

/** TMDB's switches for Home: the hero and cards ("modern Home") and Continue Watching. */
@Composable
fun TmdbHomeToggles(state: AppState) {
    val t = state.settings.tmdb
    val update: ((TmdbSettings) -> TmdbSettings) -> Unit = { change -> state.updateSettings { it.copy(tmdb = change(it.tmdb)) } }
    SettingsToggle("Enable on Home", "Also apply TMDB enrichment to Home's hero.", t.modernHomeEnabled) { v -> update { it.copy(modernHomeEnabled = v) } }
    SettingsToggle("Enrich Continue Watching", "Apply TMDB enrichment to Continue Watching items.", t.enrichContinueWatching) { v ->
        update { it.copy(enrichContinueWatching = v) }
    }
}

/**
 * Home catalogs, as the TV's catalog order page: every row and collection in Home's order, to
 * pin, move, rename or hide. Collections can go anywhere among the rows; with "Follow addons
 * order" the catalogs keep their addons' order and only collections move.
 */
@Composable
fun HomeCatalogsList(state: AppState) {
    val s = state.settings
    val l = s.layout
    val disabled = s.disabledCatalogs.toSet()
    val catalogs = state.homeRows
    val collections = state.collections.collections.filter { it.folders.isNotEmpty() }
    // Home's order of the movable rows (pinned collections lead apart, as on Home).
    val current = HomeLayout.middle(
        catalogs.map { it.key },
        collections.filter { !it.pinToTop && HomeLayout.collectionKey(it.id) !in disabled }.map { it.id },
        l.homeOrder,
        s.pinnedCatalogs,
        l.followAddonsOrder
    )
    val rowByKey = catalogs.associateBy { it.key }
    val collectionByKey = collections.associateBy { HomeLayout.collectionKey(it.id) }
    SettingsSection(null) {
        SettingsToggle(
            "Follow addons order",
            "Catalogs follow addon manifest order. Collections can still be moved between addons.",
            l.followAddonsOrder
        ) { v -> state.updateLayout { it.copy(followAddonsOrder = v) } }
    }
    var renaming by remember { mutableStateOf<String?>(null) }
    SettingsSection(null) {
        collections.filter { it.pinToTop }.forEach { c ->
            val key = HomeLayout.collectionKey(c.id)
            val on = key !in disabled
            SettingsRow(c.title, listOfNotNull("Collection · pinned to the top", "Hidden from Home".takeIf { !on }).joinToString(" · ")) {
                SecondaryButton(if (on) "Hide" else "Show", onClick = { state.setCatalogEnabled(key, !on) })
            }
        }
        current.forEachIndexed { index, key ->
            val row = rowByKey[key]
            val collection = collectionByKey[key]
            val movable = collection != null || !l.followAddonsOrder
            val move: (Int) -> Unit = { delta -> state.updateLayout { it.copy(homeOrder = HomeLayout.move(current, key, delta)) } }
            when {
                row != null -> {
                    val pinned = key in s.pinnedCatalogs
                    val custom = l.customCatalogTitles[row.tvKey()]
                    if (renaming == key) {
                        LayoutTextRow("Rename", "Leave empty for the catalog's own name (${row.title(s.showCatalogType)}).", custom.orEmpty(), width = 300.dp) { title ->
                            state.updateLayout { p ->
                                p.copy(customCatalogTitles = if (title.isBlank()) p.customCatalogTitles - row.tvKey() else p.customCatalogTitles + (row.tvKey() to title))
                            }
                            renaming = null
                        }
                    } else {
                        SettingsRow(
                            state.homeRowTitle(row),
                            listOfNotNull(row.addon.displayName, "Renamed".takeIf { custom != null }, "Pinned to the top of Home".takeIf { pinned }).joinToString(" · ")
                        ) {
                            RowButtons(movable, index, current.size, move) {
                                SecondaryButton("Rename", onClick = { renaming = key })
                                SecondaryButton(if (pinned) "Unpin" else "Pin to top", onClick = { state.setCatalogPinned(key, !pinned) })
                                SecondaryButton("Disable", onClick = { state.setCatalogEnabled(key, false) })
                            }
                        }
                    }
                }
                collection != null -> SettingsRow(collection.title, "Collection · ${collection.folders.size} folder${if (collection.folders.size == 1) "" else "s"}") {
                    RowButtons(movable, index, current.size, move) {
                        SecondaryButton("Hide", onClick = { state.setCatalogEnabled(key, false) })
                    }
                }
            }
        }
    }
    // Hidden rows and collections, to bring back.
    val hiddenRows = state.rows.filter { it.key in disabled }
    val hiddenCollections = collections.filter { !it.pinToTop && HomeLayout.collectionKey(it.id) in disabled }
    if (hiddenRows.isNotEmpty() || hiddenCollections.isNotEmpty()) {
        SettingsSection("Hidden from Home") {
            hiddenRows.forEach { row ->
                SettingsRow(state.homeRowTitle(row), row.addon.displayName) {
                    SecondaryButton("Enable", onClick = { state.setCatalogEnabled(row.key, true) })
                }
            }
            hiddenCollections.forEach { c ->
                SettingsRow(c.title, "Collection") {
                    SecondaryButton("Show", onClick = { state.setCatalogEnabled(HomeLayout.collectionKey(c.id), true) })
                }
            }
        }
    }
}

@Composable
private fun RowButtons(movable: Boolean, index: Int, size: Int, move: (Int) -> Unit, actions: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (movable) {
            CircleIconButton(FetchIcons.ArrowUp, "Move up", onClick = { move(-1) }, size = 36.dp, enabled = index > 0)
            CircleIconButton(FetchIcons.ArrowDown, "Move down", onClick = { move(1) }, size = 36.dp, enabled = index < size - 1)
        }
        actions()
    }
}

/** A text setting saved when the field loses focus or Enter is pressed. */
@Composable
fun LayoutTextRow(title: String, description: String?, value: String, width: Dp = 320.dp, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    SettingsRow(title, description) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(FetchPalette.Accent),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (text.trim() != value) onChange(text.trim()) }),
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(8.dp))
                .background(FetchPalette.Background)
                .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .onFocusChanged { focus -> if (!focus.isFocused && text.trim() != value) onChange(text.trim()) }
        )
    }
}
