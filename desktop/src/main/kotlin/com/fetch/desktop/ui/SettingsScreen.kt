package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppInfo
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen
import com.fetch.desktop.SettingsPage
import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.DesktopDefaults
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.tv.core.streams.ranking.AudioFormat
import com.fetch.tv.core.streams.ranking.ReleaseSource
import com.fetch.tv.core.streams.ranking.StreamLanguage
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.core.streams.ranking.StreamResolution
import com.fetch.tv.core.streams.ranking.VideoCodec
import com.fetch.tv.domain.model.AppTheme

/** The pages in the rail, under their group headings. Sub-pages (Addons…) open from their hub. */
private val RailPages = SettingsPage.entries.filter { it.parent == null }

/**
 * Settings, laid out like the TV app: a rail of pages grouped under Playback, Sources, Home and
 * System, and the chosen page beside it. Addons lives here, under Content & Discovery.
 */
@Composable
fun SettingsScreen(state: AppState, page: SettingsPage) {
    Row(Modifier.fillMaxSize()) {
        SettingsRail(
            current = page.railPage,
            onPick = { state.openSection(Screen.Settings(it)) },
            modifier = Modifier.width(260.dp).fillMaxHeight()
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (page) {
                // Addons keeps its own scrolling list.
                SettingsPage.ADDONS -> Column(Modifier.fillMaxSize()) {
                    SubPageHeader("Addons", onBack = state::back, modifier = Modifier.padding(start = PageGutter, top = 32.dp)) {
                        SecondaryButton("Refresh addons", onClick = state::refreshAddons)
                    }
                    AddonsScreen(state)
                }
                else -> {
                    val scroll = rememberScrollState()
                    ScrollbarBox(scroll, Modifier.fillMaxSize()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                            modifier = Modifier
                                .verticalScroll(scroll)
                                .padding(horizontal = PageGutter, vertical = 40.dp)
                                .widthIn(max = 880.dp)
                                .fillMaxWidth()
                        ) {
                            when (page) {
                                SettingsPage.STREAM_SELECTION -> StreamSelectionPage(state)
                                SettingsPage.CONTENT_DISCOVERY -> ContentDiscoveryPage(state)
                                SettingsPage.HOME_CATALOGS -> HomeCatalogsPage(state)
                                SettingsPage.APPEARANCE -> AppearancePage(state)
                                SettingsPage.LAYOUT -> LayoutPage(state)
                                SettingsPage.ABOUT -> AboutPage(state)
                                SettingsPage.ADDONS -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRail(current: SettingsPage, onPick: (SettingsPage) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.background(FetchPalette.Surface.copy(alpha = 0.5f)).padding(top = 40.dp, start = 20.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(start = 12.dp, bottom = 16.dp))
        RailPages.forEachIndexed { index, page ->
            if (index == 0 || RailPages[index - 1].group != page.group) {
                Text(
                    page.group.uppercase(),
                    style = OverlineStyle,
                    color = FetchPalette.TextMuted,
                    modifier = Modifier.padding(start = 12.dp, top = if (index == 0) 0.dp else 18.dp, bottom = 6.dp)
                )
            }
            RailEntry(page.label, selected = page == current, onClick = { onPick(page) })
        }
    }
}

@Composable
private fun RailEntry(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        label,
        fontSize = 16.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            hovered -> Color.Black
            selected -> Color.White
            else -> FetchPalette.TextSecondary
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    hovered -> Color.White
                    selected -> FetchPalette.AccentDim
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

@Composable
private fun SubPageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
    ) {
        CircleIconButton(FetchIcons.Back, "Back", onClick = onBack, size = 40.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.weight(1f, fill = false))
        actions()
    }
}

// --- Playback › Stream Selection ---

private val QualityOptions = listOf(
    StreamResolution.UHD_2160, StreamResolution.QHD_1440, StreamResolution.FHD_1080, StreamResolution.HD_720, StreamResolution.SD_480
).map { DropdownOption(it, it.label) }

private val PreferredSizeOptions = listOf<Double?>(null, 5.0, 10.0, 15.0, 25.0, 40.0, 60.0).map { sizeOption(it) }
private val MinSizeOptions = listOf<Double?>(null, 0.5, 1.0, 2.0, 5.0, 10.0).map { sizeOption(it) }
private val LimitOptions = listOf(0, 5, 10, 20, 50).map { DropdownOption(it, if (it == 0) "No limit" else "$it") }

private fun sizeOption(gb: Double?) = DropdownOption(
    gb,
    when {
        gb == null -> "No limit"
        gb % 1.0 == 0.0 -> "${gb.toInt()} GB"
        else -> "$gb GB"
    }
)

@Composable
private fun StreamSelectionPage(state: AppState) {
    val rules = state.settings.rankingRules
    val update: (StreamRankingRules) -> Unit = { new -> state.updateSettings { it.copy(rankingRules = new) } }

    PageHeader("Stream Selection", subtitle = "How Fetch picks the best match when you press Play. Every source stays in All sources.")

    SettingsSection("Quality") {
        SettingsChoice("Preferred quality", "Aim for this; higher still plays but ranks lower.", rules.preferredResolution, QualityOptions) {
            update(rules.copy(preferredResolution = it))
        }
        SettingsChoice("Maximum quality", "Anything above this isn't auto-played.", rules.maxResolution, QualityOptions) {
            update(rules.copy(maxResolution = it))
        }
        SettingsChoice(
            "Preferred video codec",
            null,
            rules.preferredCodecs.firstOrNull(),
            listOf<DropdownOption<VideoCodec?>>(DropdownOption(null, "Any")) +
                listOf(VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.AVC).map { DropdownOption(it, it.label) }
        ) { update(rules.copy(preferredCodecs = listOfNotNull(it))) }
    }

    SettingsSection("File size") {
        SettingsChoice("Preferred file size", "Larger files still play but rank lower.", rules.preferredMaxSizeGb, PreferredSizeOptions) {
            update(rules.copy(preferredMaxSizeGb = it))
        }
        SettingsChoice("Maximum file size", null, rules.maxSizeGb, PreferredSizeOptions) { update(rules.copy(maxSizeGb = it)) }
        SettingsChoice("Minimum file size", null, rules.minSizeGb, MinSizeOptions) { update(rules.copy(minSizeGb = it)) }
    }

    SettingsSection("Languages") {
        SettingsRow("Preferred audio languages", "Most wanted first. Click in the order you want them.")
        ChipPicker(
            options = StreamLanguage.entries.map { DropdownOption(it, it.label) },
            selected = rules.preferredLanguages,
            onChange = { update(rules.copy(preferredLanguages = it)) },
            ordered = true
        )
        SettingsRow("Skip audio languages", "A source in only these languages isn't auto-played.")
        ChipPicker(
            options = StreamLanguage.entries.map { DropdownOption(it, it.label) },
            selected = rules.excludedLanguages.toList(),
            onChange = { update(rules.copy(excludedLanguages = it.toSet())) }
        )
    }

    SettingsSection("Skip") {
        SettingsRow("Skip release types")
        ChipPicker(
            options = ReleaseSource.entries.filter { it != ReleaseSource.UNKNOWN }.map { DropdownOption(it, it.label) },
            selected = rules.excludedSources.toList(),
            onChange = { update(rules.copy(excludedSources = it.toSet())) }
        )
        SettingsRow("Skip audio formats", "A source with only these formats isn't auto-played.")
        ChipPicker(
            options = AudioFormat.entries.map { DropdownOption(it, it.label) },
            selected = rules.excludedAudio.toList(),
            onChange = { update(rules.copy(excludedAudio = it.toSet())) }
        )
        SettingsToggle("Never auto-play CAM or telesync", null, rules.excludeCamAndTelesync) {
            update(rules.copy(excludeCamAndTelesync = it))
        }
        SettingsToggle("Skip Dolby Vision profile 5", "Profile 5 shows purple and green colours on many screens.", 5 in rules.excludedDolbyVisionProfiles) {
            update(rules.copy(excludedDolbyVisionProfiles = if (it) rules.excludedDolbyVisionProfiles + 5 else rules.excludedDolbyVisionProfiles - 5))
        }
    }

    SettingsSection("Release groups") {
        TextListRow("Preferred release groups", rules.preferredReleaseGroups) { update(rules.copy(preferredReleaseGroups = it)) }
        TextListRow("Skip release groups", rules.excludedReleaseGroups) { update(rules.copy(excludedReleaseGroups = it)) }
    }

    SettingsSection("Results") {
        SettingsToggle("Prefer cached debrid results", "Cached links start instantly.", rules.preferCachedDebrid) {
            update(rules.copy(preferCachedDebrid = it))
        }
        SettingsChoice("Results to keep", null, rules.maxResults, LimitOptions) { update(rules.copy(maxResults = it)) }
        SettingsChoice("Results per resolution", null, rules.maxPerResolution, LimitOptions) { update(rules.copy(maxPerResolution = it)) }
    }

    Row {
        SecondaryButton("Reset to defaults", onClick = { update(DesktopDefaults.rankingRules) }, enabled = rules != DesktopDefaults.rankingRules)
    }
}

/** A comma-separated list, saved when the field loses focus. */
@Composable
private fun TextListRow(title: String, values: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember(values) { mutableStateOf(values.joinToString(", ")) }
    SettingsRow(title, "Comma-separated, any case.") {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(FetchPalette.Accent),
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(FetchPalette.Background)
                .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .onFocusChanged { focus ->
                    if (!focus.isFocused) {
                        val parsed = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                        if (parsed != values) onChange(parsed)
                    }
                }
        )
    }
}

// --- Sources › Content & Discovery ---

@Composable
private fun ContentDiscoveryPage(state: AppState) {
    PageHeader("Content & Discovery", subtitle = "Add-ons, catalogs, and discovery sources")
    SettingsSection(null) {
        SettingsRow(
            "Addons",
            "Install, order and remove addons. ${state.addons.size} installed.",
            onClick = { state.navigate(Screen.Settings(SettingsPage.ADDONS)) }
        ) { Chevron() }
        SettingsRow(
            "Home catalogs",
            "Reorder home catalogs: pin rows to the top or hide them.",
            onClick = { state.navigate(Screen.Settings(SettingsPage.HOME_CATALOGS)) }
        ) { Chevron() }
    }
}

@Composable
private fun Chevron() {
    Icon(FetchIcons.ChevronRight, contentDescription = null, tint = FetchPalette.TextMuted)
}

@Composable
private fun HomeCatalogsPage(state: AppState) {
    SubPageHeader("Home catalogs", onBack = state::back)
    Text(
        "Pinned rows lead Home in the order you pin them; the rest follow your addon order.",
        color = FetchPalette.TextSecondary
    )
    if (state.rows.isEmpty()) {
        Text("No home catalogs available yet.", color = FetchPalette.TextMuted)
        return
    }
    val pinned = state.settings.pinnedCatalogs
    val disabled = state.settings.disabledCatalogs.toSet()
    // Shown in Home's order, hidden rows after.
    val ordered = state.homeRows + state.rows.filter { it.key in disabled }
    SettingsSection(null) {
        ordered.forEach { row ->
            val isPinned = row.key in pinned
            val isEnabled = row.key !in disabled
            SettingsRow(
                title = row.title(state.settings.showCatalogType),
                description = listOfNotNull(
                    row.addon.displayName,
                    "Pinned to the top of Home".takeIf { isPinned && isEnabled },
                    "Disabled on Home".takeIf { !isEnabled }
                ).joinToString(" · ")
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isEnabled) {
                        SecondaryButton(if (isPinned) "Unpin" else "Pin to top", onClick = { state.setCatalogPinned(row.key, !isPinned) })
                    }
                    SecondaryButton(if (isEnabled) "Disable" else "Enable", onClick = { state.setCatalogEnabled(row.key, !isEnabled) })
                }
            }
        }
    }
}

// --- Home › Appearance and Layout ---

@Composable
private fun AppearancePage(state: AppState) {
    PageHeader("Appearance", subtitle = "Color Theme")
    val current = state.settings.appTheme
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppTheme.entries.filter { it != AppTheme.CUSTOM }.forEach { theme ->
            ThemeSwatch(theme, selected = theme == current) { state.updateSettings { it.copy(theme = theme.name) } }
        }
    }
}

@Composable
private fun ThemeSwatch(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .width(128.dp)
            .clip(shape)
            .background(FetchPalette.Surface)
            .border(
                if (selected || hovered) 2.dp else 1.dp,
                when {
                    hovered -> Color.White
                    selected -> accentFor(theme)
                    else -> FetchPalette.SurfaceHigh
                },
                shape
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 18.dp)
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(accentFor(theme)))
        Text(theme.displayName, color = if (selected) Color.White else FetchPalette.TextSecondary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun LayoutPage(state: AppState) {
    val s = state.settings
    PageHeader("Layout")
    SettingsSection("Home") {
        SettingsToggle("Show Continue Watching", "The row of titles you started, first on Home.", s.showContinueWatching) { v ->
            state.updateSettings { it.copy(showContinueWatching = v) }
        }
        SettingsToggle("Show Catalog Type", "Add “- Movies” or “- Series” to row titles.", s.showCatalogType) { v ->
            state.updateSettings { it.copy(showCatalogType = v) }
        }
        SettingsToggle("Show Addon Name", "Name the addon beside each row title.", s.showAddonName) { v ->
            state.updateSettings { it.copy(showAddonName = v) }
        }
    }
    SettingsSection("Posters") {
        SettingsToggle("Show Poster Labels", "Title and year under each poster.", s.showPosterLabels) { v ->
            state.updateSettings { it.copy(showPosterLabels = v) }
        }
    }
    SettingsSection("Navigation") {
        SettingsChoice(
            "Discover Location",
            "Where Discover lives.",
            s.discoverInSidebar,
            listOf(DropdownOption(false, "In Search"), DropdownOption(true, "Show in Side Panel"))
        ) { v -> state.updateSettings { it.copy(discoverInSidebar = v) } }
    }
}

// --- System › About ---

@Composable
private fun AboutPage(state: AppState) {
    val uri = LocalUriHandler.current
    val mpv = remember { ExternalPlayer.findMpv() }
    PageHeader("About", subtitle = "Fetch for desktop")
    SettingsSection(null) {
        SettingsRow("Version ${AppInfo.version}", "Same version as the TV app.")
        SettingsRow(
            "Player",
            mpv?.let { "mpv at $it" } ?: "mpv isn't installed, so streams open in your default video player and Continue Watching can't follow them. Install mpv (sudo apt install mpv, sudo dnf install mpv)."
        )
        SettingsRow("Settings folder", AppDirs.config.absolutePath)
        SettingsRow("Privacy Policy", "No telemetry, no required account.", onClick = { uri.openUri(AppInfo.PRIVACY_URL) }) { Chevron() }
        SettingsRow("Licenses & Attribution", null, onClick = { uri.openUri(AppInfo.LICENSE_URL) }) { Chevron() }
        SettingsRow("Source code", AppInfo.REPO_URL, onClick = { uri.openUri(AppInfo.REPO_URL) }) { Chevron() }
    }
}
