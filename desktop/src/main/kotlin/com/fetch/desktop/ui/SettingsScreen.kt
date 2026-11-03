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
import androidx.compose.runtime.collectAsState
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
import com.fetch.desktop.data.HwDec
import com.fetch.desktop.data.AssOverride
import com.fetch.desktop.data.AudioChannels
import com.fetch.desktop.data.AutoPlayMode
import com.fetch.desktop.data.VideoFit
import com.fetch.desktop.skip.IntroDbClient
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.SubtitleMode
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.desktop.player.MpvArgs
import com.fetch.desktop.player.embedded.LibMpv
import com.fetch.desktop.skip.SegmentType
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
        // Watch party is listed only when the sync server offers it, as on TV.
        val partyAvailable by state.party.available.collectAsState()
        SettingsRail(
            hidden = if (partyAvailable) emptySet() else setOf(SettingsPage.WATCH_PARTY),
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
                                SettingsPage.PROFILES -> ProfilesSettingsPage(state)
                                SettingsPage.PLAYER -> PlayerPage(state)
                                SettingsPage.STREAM_SELECTION -> StreamSelectionPage(state)
                                SettingsPage.UP_NEXT -> UpNextPage(state)
                                SettingsPage.AUDIO -> AudioPage(state)
                                SettingsPage.SUBTITLES -> SubtitlesPage(state)
                                SettingsPage.SKIP_SEGMENTS -> SkipSegmentsPage(state)
                                SettingsPage.CONTENT_DISCOVERY -> ContentDiscoveryPage(state)
                                SettingsPage.HOME_CATALOGS -> HomeCatalogsPage(state)
                                SettingsPage.APPEARANCE -> AppearancePage(state)
                                SettingsPage.LAYOUT -> LayoutPage(state)
                                SettingsPage.TRACKING -> TrackingPage(state)
                                SettingsPage.YOUTUBE -> YouTubeSettingsPage(state)
                                SettingsPage.WATCH_PARTY -> WatchPartyPage(state)
                                SettingsPage.TMDB -> TmdbPage(state)
                                SettingsPage.MDBLIST -> MdbListPage(state)
                                SettingsPage.ACCOUNT -> AccountPage(state)
                                SettingsPage.MEDIA_SERVERS -> MediaServersPage(state)
                                SettingsPage.STREAM_BADGES -> StreamBadgesSettingsPage(state.badges, onBack = state::back)
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
private fun SettingsRail(current: SettingsPage, onPick: (SettingsPage) -> Unit, modifier: Modifier = Modifier, hidden: Set<SettingsPage> = emptySet()) {
    val pages = RailPages.filter { it !in hidden }
    // Scrolls when the window is shorter than the rail.
    Column(
        modifier.background(FetchPalette.Surface.copy(alpha = 0.5f)).verticalScroll(rememberScrollState())
            .padding(top = 40.dp, bottom = 32.dp, start = 20.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(start = 12.dp, bottom = 16.dp))
        pages.forEachIndexed { index, page ->
            if (index == 0 || pages[index - 1].group != page.group) {
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

// --- Playback › Player, Up Next, Audio, Subtitles ---

private fun AppState.updatePlayer(change: (PlayerSettings) -> PlayerSettings) =
    updateSettings { it.copy(player = change(it.player)) }

private val LanguageOptions: List<DropdownOption<String?>> =
    MpvArgs.languages.map { DropdownOption<String?>(it.code, it.label) }

@Composable
private fun PlayerPage(state: AppState) {
    val p = state.settings.player
    val mpv = remember { ExternalPlayer.findMpv() }
    PageHeader("Player", subtitle = "Fetch plays streams with mpv.")
    SettingsSection(null) {
        val libmpv = remember { LibMpv.available }
        SettingsChoice(
            "Default Player",
            when {
                p.askPlayer -> "Fetch asks each time you press Play."
                p.builtInPlayer && !libmpv -> "libmpv isn't installed (sudo apt install libmpv2), so mpv's window is used."
                p.builtInPlayer -> "Inside Fetch's window, with Fetch's controls."
                else -> mpv?.let { "mpv ($it), in its own window." }
                    ?: "mpv isn't installed, so streams open in your default video player. Install mpv for resume, Continue Watching, subtitles and Up Next."
            },
            if (p.askPlayer) null else p.builtInPlayer,
            listOf(DropdownOption<Boolean?>(false, "mpv window"), DropdownOption<Boolean?>(true, "Built-in (beta)"), DropdownOption<Boolean?>(null, "Ask every time"))
        ) { v -> state.updatePlayer { it.copy(builtInPlayer = v ?: it.builtInPlayer, askPlayer = v == null) } }
        if (p.builtInPlayer && libmpv) {
            SettingsToggle(
                "Draw with the graphics card",
                if (p.builtInGpu) "OpenGL: smooth scaling and HDR tone mapping. Without a graphics card Fetch draws in software. Turn off if video shows black or glitches."
                else "Software drawing: works everywhere, but heavy for 4K, and HDR isn't tone-mapped.",
                p.builtInGpu
            ) { v -> state.updatePlayer { it.copy(builtInGpu = v) } }
        }
        SettingsToggle("Start in fullscreen", null, p.fullscreen) { v -> state.updatePlayer { it.copy(fullscreen = v) } }
        SettingsChoice(
            "Hardware Decoding",
            "How mpv uses the graphics card to decode video.",
            p.hwdec,
            HwDec.entries.map { DropdownOption(it, it.label) }
        ) { v -> state.updatePlayer { it.copy(hwdec = v) } }
        SettingsChoice(
            "Picture",
            "How the video fills the screen. The built-in player can change it while playing.",
            p.videoFit,
            VideoFit.entries.map { DropdownOption(it, it.label) }
        ) { v -> state.updatePlayer { it.copy(videoFit = v) } }
    }
    SettingsSection("Player interface") {
        SettingsToggle("Pause overlay", "The title, episode and synopsis after a few seconds paused (built-in player).", p.pauseOverlay) { v ->
            state.updatePlayer { it.copy(pauseOverlay = v) }
        }
    }
    SettingsSection("Buffer") {
        SettingsChoice(
            "Cache size",
            "How much of the stream mpv reads ahead. More smooths out slow servers; it uses memory.",
            p.cacheMb,
            listOf<Int?>(null, 150, 300, 600, 1200).map { DropdownOption(it, it?.let { mb -> "$mb MB" } ?: "Default") }
        ) { v -> state.updatePlayer { it.copy(cacheMb = v) } }
    }
    SettingsSection("Advanced") {
        TextRow(
            "Extra mpv options",
            "Added to every playback, for example --volume=80 --sub-font=\"Noto Sans\"",
            p.extraMpvArgs
        ) { v -> state.updatePlayer { it.copy(extraMpvArgs = v) } }
    }
}

@Composable
private fun UpNextPage(state: AppState) {
    val p = state.settings.player
    PageHeader("Up Next", subtitle = "Next episode autoplay.")
    SettingsSection(null) {
        SettingsToggle(
            "Autoplay next episode",
            "When an episode plays to its end, start the next one's best match. Closing mpv early never does.",
            p.autoplayNextEpisode
        ) { v -> state.updatePlayer { it.copy(autoplayNextEpisode = v) } }
        if (p.autoplayNextEpisode) {
            SettingsChoice(
                "Up Next prompt",
                "When the built-in player shows the next episode with Play now, before the end (or at the credits).",
                p.upNextPromptSeconds,
                listOf(10, 20, 30, 45, 60, 90, 120).map { DropdownOption(it, "$it s before the end") }
            ) { v -> state.updatePlayer { it.copy(upNextPromptSeconds = v) } }
            SettingsToggle("Are you still watching?", "Ask before starting another episode after several in a row.", p.stillWatching) { v ->
                state.updatePlayer { it.copy(stillWatching = v) }
            }
            if (p.stillWatching) {
                SettingsChoice("Ask after", null, p.stillWatchingEpisodes, (1..6).map { DropdownOption(it, if (it == 1) "1 episode" else "$it episodes") }) { v ->
                    state.updatePlayer { it.copy(stillWatchingEpisodes = v) }
                }
            }
        }
    }
}

@Composable
private fun AudioPage(state: AppState) {
    val p = state.settings.player
    PageHeader("Audio")
    SettingsSection(null) {
        SettingsChoice(
            "Preferred Audio Language",
            "The audio track mpv picks when a file has several.",
            p.audioLanguage,
            listOf(DropdownOption<String?>(null, "Default (media file)")) + LanguageOptions
        ) { v -> state.updatePlayer { it.copy(audioLanguage = v) } }
        SettingsChoice(
            "Secondary Audio Language",
            "Used when the first isn't in the file.",
            p.secondaryAudioLanguage,
            listOf(DropdownOption<String?>(null, "None")) + LanguageOptions
        ) { v -> state.updatePlayer { it.copy(secondaryAudioLanguage = v) } }
        SettingsToggle(
            "Audio passthrough",
            "Send Dolby Digital, DD+, DTS and TrueHD to your receiver undecoded. Only with a receiver that supports them.",
            p.audioPassthrough
        ) { v -> state.updatePlayer { it.copy(audioPassthrough = v) } }
    }
    SettingsSection("Mixing") {
        SettingsChoice("Channels", "Mix surround down to fewer speakers, or keep what the file has.", p.audioChannels, AudioChannels.entries.map { DropdownOption(it, it.label) }) { v ->
            state.updatePlayer { it.copy(audioChannels = v) }
        }
        SettingsToggle("Normalize downmix", "Keep a downmixed track as loud as the original, so dialogue doesn't get quiet.", p.normalizeDownmix) { v ->
            state.updatePlayer { it.copy(normalizeDownmix = v) }
        }
        SettingsChoice(
            "Volume boost",
            "Amplify quiet files. Not applied to passthrough audio.",
            p.volumeBoostDb,
            listOf(0, 3, 6, 9, 12).map { DropdownOption(it, if (it == 0) "Off" else "+$it dB") }
        ) { v -> state.updatePlayer { it.copy(volumeBoostDb = v) } }
    }
}

@Composable
private fun SubtitlesPage(state: AppState) {
    val p = state.settings.player
    PageHeader("Subtitles")
    SettingsSection("Languages") {
        SettingsChoice(
            "Subtitle language",
            null,
            p.subtitleLanguage,
            listOf(DropdownOption<String?>(null, "Device language")) + LanguageOptions
        ) { v -> state.updatePlayer { it.copy(subtitleLanguage = v) } }
        SettingsChoice(
            "Secondary subtitle language",
            "Used when there's no subtitle in the first.",
            p.secondarySubtitleLanguage,
            listOf(DropdownOption<String?>(null, "None")) + LanguageOptions
        ) { v -> state.updatePlayer { it.copy(secondarySubtitleLanguage = v) } }
        SettingsChoice("Show subtitles", null, p.subtitleMode, SubtitleMode.entries.map { DropdownOption(it, it.label) }) { v ->
            state.updatePlayer { it.copy(subtitleMode = v) }
        }
        SettingsToggle(
            "Forward subtitles to external player",
            "Fetch subtitles in your language from addons (such as OpenSubtitles) and pass them to mpv.",
            p.forwardAddonSubtitles
        ) { v -> state.updatePlayer { it.copy(forwardAddonSubtitles = v) } }
        SettingsToggle("Use forced subtitles", "Show the forced track (signs, lines in another language) when no other subtitle is picked.", p.useForcedSubtitles) { v ->
            state.updatePlayer { it.copy(useForcedSubtitles = v) }
        }
        SettingsToggle("Remove hearing-impaired text", "Drop [MUSIC], (laughs) and speaker names.", p.stripSdh) { v ->
            state.updatePlayer { it.copy(stripSdh = v) }
        }
    }
    SettingsSection("Style") {
        SettingsChoice(
            "Size",
            null,
            p.subtitleScale,
            listOf(0.75, 1.0, 1.25, 1.5, 2.0).map { DropdownOption(it, "${(it * 100).toInt()}%") }
        ) { v -> state.updatePlayer { it.copy(subtitleScale = v) } }
        SettingsToggle("Bold", null, p.subtitleBold) { v -> state.updatePlayer { it.copy(subtitleBold = v) } }
        SettingsChoice("Text color", null, p.subtitleColor.uppercase(), TextColors) { v -> state.updatePlayer { it.copy(subtitleColor = v) } }
        SettingsToggle("Background box", "A dark box behind the text instead of an outline.", p.subtitleBackground) { v ->
            state.updatePlayer { it.copy(subtitleBackground = v) }
        }
        if (!p.subtitleBackground) {
            SettingsToggle("Outline", null, p.subtitleOutline) { v -> state.updatePlayer { it.copy(subtitleOutline = v) } }
            if (p.subtitleOutline) {
                SettingsChoice("Outline color", null, p.subtitleOutlineColor.uppercase(), OutlineColors) { v -> state.updatePlayer { it.copy(subtitleOutlineColor = v) } }
                SettingsChoice(
                    "Outline width",
                    null,
                    p.subtitleOutlineSize,
                    listOf(1.0, 2.0, 3.0, 4.0, 5.0).map { DropdownOption(it, if (it == 3.0) "Normal" else it.toInt().toString()) }
                ) { v -> state.updatePlayer { it.copy(subtitleOutlineSize = v) } }
            }
        }
        SettingsChoice(
            "Vertical position",
            "Move subtitles up from the bottom of the picture.",
            p.subtitlePosition,
            listOf(100, 95, 90, 85, 80, 70).map { DropdownOption(it, if (it == 100) "Bottom" else "${100 - it}% up") }
        ) { v -> state.updatePlayer { it.copy(subtitlePosition = v) } }
        SettingsChoice(
            "Styled subtitles (ASS/SSA)",
            "Anime and fansubs often carry their own fonts and colors.",
            p.assOverride,
            AssOverride.entries.map { DropdownOption(it, it.label) }
        ) { v -> state.updatePlayer { it.copy(assOverride = v) } }
    }
}

private val TextColors = listOf(
    "#FFFFFF" to "White", "#FFFF00" to "Yellow", "#00FFFF" to "Cyan", "#00FF00" to "Green", "#FF00FF" to "Magenta", "#C0C0C0" to "Light gray"
).map { DropdownOption(it.first, it.second) }

private val OutlineColors = listOf("#000000" to "Black", "#404040" to "Dark gray", "#FFFFFF" to "White", "#000080" to "Navy")
    .map { DropdownOption(it.first, it.second) }

/** The TV's TMDB languages (`AVAILABLE_TMDB_LANGUAGES`), so a synced choice reads the same. */
private val TmdbLanguages = listOf(
    "af" to "Afrikaans",
    "sq" to "Albanian",
    "am" to "Amharic",
    "ar" to "Arabic",
    "hy" to "Armenian",
    "az" to "Azerbaijani",
    "eu" to "Basque",
    "be" to "Belarusian",
    "bn" to "Bengali",
    "bs" to "Bosnian",
    "bg" to "Bulgarian",
    "my" to "Burmese",
    "ca" to "Catalan",
    "zh" to "Chinese",
    "zh-CN" to "Chinese (Simplified)",
    "zh-TW" to "Chinese (Traditional)",
    "hr" to "Croatian",
    "cs" to "Czech",
    "da" to "Danish",
    "nl" to "Dutch",
    "en" to "English",
    "et" to "Estonian",
    "tl" to "Filipino",
    "fi" to "Finnish",
    "fr" to "French",
    "gl" to "Galician",
    "ka" to "Georgian",
    "de" to "German",
    "el" to "Greek",
    "gu" to "Gujarati",
    "he" to "Hebrew",
    "hi" to "Hindi",
    "hu" to "Hungarian",
    "is" to "Icelandic",
    "id" to "Indonesian",
    "ga" to "Irish",
    "it" to "Italian",
    "ja" to "Japanese",
    "kn" to "Kannada",
    "kk" to "Kazakh",
    "km" to "Khmer",
    "ko" to "Korean",
    "lo" to "Lao",
    "lv" to "Latvian",
    "lt" to "Lithuanian",
    "mk" to "Macedonian",
    "ms" to "Malay",
    "ml" to "Malayalam",
    "mt" to "Maltese",
    "mr" to "Marathi",
    "mn" to "Mongolian",
    "ne" to "Nepali",
    "no" to "Norwegian",
    "pa" to "Punjabi",
    "fa" to "Persian",
    "pl" to "Polish",
    "pt" to "Portuguese (Portugal)",
    "pt-br" to "Portuguese (Brazil)",
    "ro" to "Romanian",
    "ru" to "Russian",
    "sr" to "Serbian",
    "si" to "Sinhala",
    "sk" to "Slovak",
    "sl" to "Slovenian",
    "es" to "Spanish",
    "es-419" to "Spanish (Latin America)",
    "sw" to "Swahili",
    "sv" to "Swedish",
    "ta" to "Tamil",
    "te" to "Telugu",
    "th" to "Thai",
    "tr" to "Turkish",
    "uk" to "Ukrainian",
    "ur" to "Urdu",
    "uz" to "Uzbek",
    "vi" to "Vietnamese",
    "cy" to "Welsh",
    "zu" to "Zulu",
    "en-AU" to "English (Australia)",
    "en-CA" to "English (Canada)",
    "en-GB" to "English (United Kingdom)"
).sortedBy { it.second }.map { DropdownOption(it.first, it.second) }

@Composable
private fun SkipSegmentsPage(state: AppState) {
    val p = state.settings.player
    PageHeader("Skip Segments", subtitle = "Intros, recaps and credits, from introdb.app. mpv jumps over them and says so on screen.")
    SettingsSection("Automatic Skipping") {
        SegmentType.entries.forEach { type ->
            SettingsToggle(
                "Skip ${type.label.lowercase()}",
                if (type == SegmentType.OUTRO) "Skipping the credits ends the episode, so Up Next starts sooner." else null,
                type in p.autoSkip
            ) { on -> state.updatePlayer { it.copy(autoSkip = if (on) it.autoSkip + type else it.autoSkip - type) } }
        }
    }
    SettingsSection("Skip button") {
        SettingsToggle(
            "Show Skip button",
            "The built-in player offers Skip for intros, recaps and credits that aren't skipped automatically.",
            p.skipButton
        ) { v -> state.updatePlayer { it.copy(skipButton = v) } }
    }
    SettingsSection("Advanced") {
        TextRow(
            "IntroDB server",
            "Leave empty for ${IntroDbClient.DEFAULT_URL}",
            p.introDbUrl.orEmpty()
        ) { v -> state.updatePlayer { it.copy(introDbUrl = v.ifBlank { null }) } }
    }
}

/** A free-text setting, saved when the field loses focus. */
@Composable
private fun TextRow(title: String, description: String?, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    SettingsRow(title, description) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
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

    SettingsSection("When you press Play") {
        val s = state.settings
        SettingsChoice("Play", "What Play starts once the sources are in.", s.autoPlayMode, AutoPlayMode.entries.map { DropdownOption(it, it.label) }) { v ->
            state.updateSettings { it.copy(autoPlayMode = v) }
        }
        if (s.autoPlayMode == AutoPlayMode.REGEX_MATCH) {
            TextRow("Pattern", "A regular expression matched against each source's name and release, any case. Example: 2160p.*REMUX", s.autoPlayRegex) { v ->
                state.updateSettings { it.copy(autoPlayRegex = v) }
            }
        }
        SettingsChoice(
            "Addon timeout",
            "How long to wait for each addon's sources. Slow addons are left out after this.",
            s.addonTimeoutSeconds,
            listOf(5, 10, 15, 20, 30, 60).map { DropdownOption(it, "$it s") }
        ) { v -> state.updateSettings { it.copy(addonTimeoutSeconds = v) } }
    }

    SettingsSection(null) {
        SettingsRow(
            "Stream badges",
            "Fusion badge URLs: labels and images on each source. ${if (state.badges.sources.isEmpty()) "None imported." else "${state.badges.sources.size} imported."}",
            onClick = { state.navigate(Screen.Settings(SettingsPage.STREAM_BADGES)) }
        ) { Chevron() }
    }

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

    SettingsSection("Addons") {
        TextListRow("Preferred addons", rules.preferredAddons) { update(rules.copy(preferredAddons = it)) }
    }

    SettingsSection("Release groups") {
        TextListRow("Preferred release groups", rules.preferredReleaseGroups) { update(rules.copy(preferredReleaseGroups = it)) }
        TextListRow("Skip release groups", rules.excludedReleaseGroups) { update(rules.copy(excludedReleaseGroups = it)) }
    }

    SettingsSection("Results") {
        SettingsToggle("Prefer my own library", "Files on your Jellyfin, Emby or Plex server beat anything streamed.", rules.preferOwnLibrary) {
            update(rules.copy(preferOwnLibrary = it))
        }
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

// --- Sources › TMDB ---

@Composable
private fun TmdbPage(state: AppState) {
    val builtIn = remember { com.fetch.desktop.ratings.TmdbCredentials.load() != null }
    val own = state.settings.tmdbApiKey?.takeIf { it.isNotBlank() }
    val t = state.settings.tmdb
    val update: ((com.fetch.desktop.data.TmdbSettings) -> com.fetch.desktop.data.TmdbSettings) -> Unit = { change ->
        state.updateSettings { it.copy(tmdb = change(it.tmdb)) }
    }
    PageHeader("TMDB", subtitle = "The Movie Database: richer title pages, More like this, people and studios, and ratings for profile limits.")
    SettingsSection(null) {
        SettingsRow(
            "Status",
            when {
                own != null -> "Using your key."
                builtIn -> "Using this build's key."
                else -> "No key: TMDB features are off, and rating limits only know ratings your addons give. Add a free key from themoviedb.org."
            }
        )
        TextRow("Your TMDB key", "An API key (v3) or read access token (v4). Leave empty to use the build's.", own.orEmpty()) { key ->
            state.updateSettings { it.copy(tmdbApiKey = key.ifBlank { null }) }
        }
    }
    SettingsSection("Enrichment") {
        SettingsToggle(
            "Use TMDB",
            "Fill title pages in from TMDB: artwork, details, cast and crew, episodes, trailers, More like this and collections. Ratings for profile limits work either way.",
            t.enabled
        ) { v -> update { it.copy(enabled = v) } }
        if (t.enabled) {
            SettingsChoice("Language", "For titles, descriptions, episode names and logos.", t.language, TmdbLanguages) { v -> update { it.copy(language = v) } }
        }
    }
    if (t.enabled) {
        SettingsSection("What TMDB fills in") {
            SettingsToggle("Artwork", "Backdrops and title logos.", t.useArtwork) { v -> update { it.copy(useArtwork = v) } }
            SettingsToggle("Title, description and genres", "In the language above. Also shows TMDB's rating.", t.useBasicInfo) { v -> update { it.copy(useBasicInfo = v) } }
            SettingsToggle("Details", "Runtime, status, country and language.", t.useDetails) { v -> update { it.copy(useDetails = v) } }
            SettingsToggle("Cast and crew", "With photos; click someone for their page.", t.useCredits) { v -> update { it.copy(useCredits = v) } }
            SettingsToggle("Production companies", "Logos that open a studio's titles.", t.useProductions) { v -> update { it.copy(useProductions = v) } }
            SettingsToggle("Networks", "Logos that open a network's shows.", t.useNetworks) { v -> update { it.copy(useNetworks = v) } }
            SettingsToggle("Episodes", "Episode names, descriptions, stills and ratings.", t.useEpisodes) { v -> update { it.copy(useEpisodes = v) } }
            SettingsToggle("Trailers", null, t.useTrailers) { v -> update { it.copy(useTrailers = v) } }
            SettingsToggle("More like this", "On title pages, and Because you watched on Home.", t.useMoreLikeThis) { v -> update { it.copy(useMoreLikeThis = v) } }
            SettingsToggle("Collections", "The other films in a series, such as a trilogy.", t.useCollections) { v -> update { it.copy(useCollections = v) } }
        }
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
            "Media servers",
            "Jellyfin, Emby and Plex: your own files as sources, and your libraries on Home. ${state.servers.accounts.size} connected.",
            onClick = { state.navigate(Screen.Settings(SettingsPage.MEDIA_SERVERS)) }
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
    AmbientSettingsSection(state)
}

/** Ambient Mode, as the TV's settings: on, after how long, artwork or clock, and which catalogs. */
@Composable
private fun AmbientSettingsSection(state: AppState) {
    val a = state.settings.ambient
    val update: (com.fetch.desktop.data.AmbientSettings) -> Unit = { new -> state.updateSettings { it.copy(ambient = new) } }
    Box(Modifier.padding(top = 24.dp))
    SettingsSection("Ambient Mode") {
        SettingsToggle("Ambient Mode", "Fill the window with backdrops after a while with no mouse or keyboard input. Never during playback.", a.enabled) {
            update(a.copy(enabled = it))
        }
        if (a.enabled) {
            SettingsChoice(
                "Start After",
                "How long to wait before Ambient Mode starts.",
                a.timeoutMinutes,
                listOf(1, 2, 5, 10, 15, 30).map { DropdownOption(it, if (it == 1) "1 minute" else "$it minutes") }
            ) { update(a.copy(timeoutMinutes = it)) }
            SettingsChoice(
                "Show",
                "Artwork from your catalogs, or a clock with what to resume.",
                a.face,
                listOf(DropdownOption(com.fetch.tv.domain.model.AmbientFace.ARTWORK, "Artwork"), DropdownOption(com.fetch.tv.domain.model.AmbientFace.CLOCK, "Clock"))
            ) { update(a.copy(face = it)) }
            if (a.face == com.fetch.tv.domain.model.AmbientFace.ARTWORK) {
                SettingsRow("Catalogs", "Choose up to 3 Home rows to take artwork from. None: the first row.")
                ChipPicker(
                    options = state.rows.map { DropdownOption(it.key, "${it.catalog.name} · ${it.addon.displayName}") },
                    selected = a.catalogKeys,
                    onChange = { update(a.copy(catalogKeys = it.take(3))) },
                    ordered = true
                )
            }
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
        if (s.showContinueWatching) {
            SettingsToggle("Show Unaired Next Up Episodes", "Include upcoming episodes in Continue Watching before they air.", s.showUnairedNextUp) { v ->
                state.updateSettings { it.copy(showUnairedNextUp = v) }
            }
            SettingsToggle(
                "Up Next From Furthest Episode",
                "Show the episode after the furthest one watched. Turn off for rewatches, to follow the most recent one instead.",
                s.nextUpFromFurthest
            ) { v -> state.updateSettings { it.copy(nextUpFromFurthest = v) } }
            SettingsChoice(
                "Sort Order",
                when (s.continueWatchingSort) {
                    com.fetch.tv.domain.model.ContinueWatchingSortMode.DEFAULT -> "Sort all items by recency."
                    com.fetch.tv.domain.model.ContinueWatchingSortMode.STREAMING_STYLE -> "Released items first, upcoming at the end."
                    com.fetch.tv.domain.model.ContinueWatchingSortMode.SPLIT_UPCOMING -> "Move unaired episodes to a separate Upcoming row."
                },
                s.continueWatchingSort,
                listOf(
                    DropdownOption(com.fetch.tv.domain.model.ContinueWatchingSortMode.DEFAULT, "Default"),
                    DropdownOption(com.fetch.tv.domain.model.ContinueWatchingSortMode.STREAMING_STYLE, "Streaming Style"),
                    DropdownOption(com.fetch.tv.domain.model.ContinueWatchingSortMode.SPLIT_UPCOMING, "Separate Upcoming Row")
                )
            ) { v -> state.updateSettings { it.copy(continueWatchingSort = v) } }
        }
        SettingsToggle("Show Catalog Type", "Add “- Movies” or “- Series” to row titles.", s.showCatalogType) { v ->
            state.updateSettings { it.copy(showCatalogType = v) }
        }
        SettingsToggle("Show Addon Name", "Name the addon beside each row title.", s.showAddonName) { v ->
            state.updateSettings { it.copy(showAddonName = v) }
        }
        SettingsToggle("Hide unreleased content", "Leave titles that aren't out yet off Home's rows.", s.hideUnreleased) { v ->
            state.updateSettings { it.copy(hideUnreleased = v) }
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
    }
    SettingsSection("Updates") {
        val s = state.settings
        val updates = state.updates
        SettingsToggle("Check for updates", "Look for a newer Fetch on GitHub at start-up. Installing stays with apt, dnf or the tarball.", s.checkForUpdates) { v ->
            state.updateSettings { it.copy(checkForUpdates = v) }
        }
        SettingsToggle("Include beta releases", null, s.betaUpdates) { v -> state.updateSettings { it.copy(betaUpdates = v) } }
        SettingsRow(
            if (updates.checking) "Checking…" else "Check now",
            updates.status,
            onClick = { updates.dismissed = false; updates.check(beta = s.betaUpdates) }
        ) { Chevron() }
        updates.available?.let { update ->
            SettingsRow("Download Fetch ${update.version}", update.packageUrl ?: update.pageUrl, onClick = { state.openInBrowser(update.packageUrl ?: update.pageUrl) }) { Chevron() }
        }
    }
    SettingsSection("Legal") {
        SettingsRow("Privacy Policy", "No telemetry, no required account.", onClick = { uri.openUri(AppInfo.PRIVACY_URL) }) { Chevron() }
        SettingsRow("Licenses & Attribution", null, onClick = { uri.openUri(AppInfo.LICENSE_URL) }) { Chevron() }
        SettingsRow("Source code", AppInfo.REPO_URL, onClick = { uri.openUri(AppInfo.REPO_URL) }) { Chevron() }
    }
}
