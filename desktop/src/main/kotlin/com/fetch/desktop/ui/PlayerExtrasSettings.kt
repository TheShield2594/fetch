package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.data.PlayerExtras
import com.fetch.desktop.data.SubtitleOrganization
import com.fetch.desktop.player.embedded.LibMpv
import com.fetch.desktop.skip.AnimeSkipClient
import com.fetch.desktop.skip.SegmentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun AppState.updateExtras(change: (PlayerExtras) -> PlayerExtras) =
    updateSettings { it.copy(player = it.player.copy(extras = change(it.player.extras))) }

/** The TV's names for what Skip Segments can skip automatically. */
internal fun autoSkipTitle(type: SegmentType): Pair<String, String> = when (type) {
    SegmentType.INTRO -> "Intro / Opening" to "Skip intros and anime openings automatically."
    SegmentType.RECAP -> "Recap" to "Skip recap segments automatically."
    SegmentType.OUTRO -> "Outro / Ending" to "Skip outros and anime endings automatically. Up Next starts sooner."
    SegmentType.MOVIE_CREDITS -> "End Credits" to "Skip movie credits automatically, keeping post-credits scenes."
    SegmentType.POST_CREDITS -> "Post-credits scene" to ""
}

/** Settings › Skip Segments › Anime: Anime-Skip with your client id, and what AniSkip needs. */
@Composable
fun AnimeSkipSettings(state: AppState) {
    val e = state.settings.player.extras
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsSection("Anime") {
        SettingsRow(
            "AniSkip",
            "Anime openings and endings from aniskip.com, merged with IntroDB's (IntroDB first, then Anime-Skip, then AniSkip). " +
                "Finding the anime needs this build's Simkl client id."
        )
        SettingsToggle("Enable Anime Skip", "Fetch skip timestamps from anime-skip.com.", e.animeSkipEnabled) { v ->
            state.updateExtras { it.copy(animeSkipEnabled = v) }
        }
        if (e.animeSkipEnabled) {
            SecretTextRow(
                "Client ID",
                error ?: if (checking) "Checking with Anime Skip…" else "Required to fetch skip timestamps from anime-skip.com. Create your own at anime-skip.com/account/settings.",
                e.animeSkipClientId
            ) { value ->
                error = null
                if (value.isBlank()) {
                    state.updateExtras { it.copy(animeSkipClientId = "") }
                    return@SecretTextRow
                }
                checking = true
                scope.launch {
                    val ok = AnimeSkipClient(okhttp3.OkHttpClient()).validate(value)
                    checking = false
                    if (ok) state.updateExtras { it.copy(animeSkipClientId = value.trim()) }
                    else error = "Couldn't verify this Client ID. Check it matches the one in your Anime Skip account and that you're online, then try again."
                }
            }
        }
    }
}

/** Settings › Subtitles › Organisation, HDR and Auto Sync. */
@Composable
fun SubtitleExtrasSettings(state: AppState) {
    val e = state.settings.player.extras
    SettingsSection("Subtitle list") {
        SettingsToggle("Show Only Preferred Languages", "Hide all other subtitle languages from the player's list.", e.subtitleOnlyPreferredLanguages) { v ->
            state.updateExtras { it.copy(subtitleOnlyPreferredLanguages = v) }
        }
        SettingsChoice(
            "Organise subtitles",
            "How the built-in player lists the file's subtitles and your addons'.",
            e.subtitleOrganization,
            SubtitleOrganization.entries.map { DropdownOption(it, it.label) }
        ) { v -> state.updateExtras { it.copy(subtitleOrganization = v) } }
    }
    SettingsSection("HDR") {
        SettingsToggle(
            "Separate HDR / Dolby Vision style",
            "Use a different text color and a dimmer brightness for subtitles on HDR and Dolby Vision video (built-in player).",
            e.subtitleHdrStyle
        ) { v -> state.updateExtras { it.copy(subtitleHdrStyle = v) } }
        if (e.subtitleHdrStyle) {
            SettingsChoice("HDR Text Color", null, e.subtitleHdrColor.uppercase().let { c -> HdrColors.firstOrNull { it.value == c }?.value ?: c }, HdrColors.withCurrent(e.subtitleHdrColor)) { v ->
                state.updateExtras { it.copy(subtitleHdrColor = v) }
            }
            SettingsChoice(
                "HDR Subtitle Brightness",
                "Lower is dimmer; 0 still leaves readable text.",
                e.subtitleHdrBrightness,
                (0..100 step 10).map { DropdownOption(it, "$it%") }.withCurrentInt(e.subtitleHdrBrightness)
            ) { v -> state.updateExtras { it.copy(subtitleHdrBrightness = v) } }
        }
    }
    SettingsSection("Auto Sync") {
        SettingsToggle(
            "Auto Sync Subtitles",
            "Match the selected addon subtitle to the timing of the subtitles embedded in the video: shifted, or re-timed for another frame rate or cut. Tries other subtitles in the language when it won't match (built-in player; image subtitles need a Matroska file or ffmpeg).",
            e.autoSync
        ) { v -> state.updateExtras { it.copy(autoSync = v) } }
        if (e.autoSync) {
            SettingsChoice(
                "Auto Sync Tolerance",
                "Keep the original timing when the correction needed is this small or smaller.",
                e.autoSyncToleranceMs,
                PlayerExtras.autoSyncToleranceOptionsMs.map { DropdownOption(it, if (it == 0) "Off" else tr("%1\$s ms", it)) }
            ) { v -> state.updateExtras { it.copy(autoSyncToleranceMs = v) } }
            SettingsToggle(
                "Thorough Auto Sync Search",
                "Keep comparing for a closer match before syncing. When off, the first strong match is used sooner. Only confident matches are ever applied.",
                e.autoSyncThorough
            ) { v -> state.updateExtras { it.copy(autoSyncThorough = v) } }
        }
    }
}

/** Settings › Audio: skip silence, dialogue boost and the delay per output. */
@Composable
fun AudioExtrasSettings(state: AppState) {
    val e = state.settings.player.extras
    SettingsSection("Playback") {
        SettingsToggle("Skip Silence", "Skip silent portions of audio during playback (built-in player).", e.skipSilence) { v ->
            state.updateExtras { it.copy(skipSilence = v) }
        }
        SettingsToggle(
            "Remember audio delay per device",
            "Save and restore the built-in player's audio delay for the current audio output.",
            e.rememberAudioDelayPerDevice
        ) { v -> state.updateExtras { it.copy(rememberAudioDelayPerDevice = v) } }
        SettingsChoice(
            "Dialogue boost",
            "The centre channel's level when surround is mixed down to fewer speakers, relative to the usual −3 dB. Also in the player's Audio panel.",
            e.centerMixLevelDb,
            listOf(-6, -3, 0, 3, 6, 9, 12, 18, 24, 30).map { DropdownOption(it, if (it == 0) "Off" else "%+d dB".format(it)) }.withCurrentInt(e.centerMixLevelDb)
        ) { v -> state.updateExtras { it.copy(centerMixLevelDb = v) } }
    }
}

/** Settings › Player › Advanced: the stats overlay. */
@Composable
fun StatsHudSetting(state: AppState) {
    val e = state.settings.player.extras
    SettingsToggle(
        "Playback stats overlay",
        "Show a button in the built-in player's stream info (I) to toggle live cpu, memory, buffer, bitrate, network and temperature readings.",
        e.statsHudButton
    ) { v -> state.updateExtras { it.copy(statsHudButton = v) } }
}

/** Settings › Layout › Trailers. */
@Composable
fun TrailerSettings(state: AppState) {
    val e = state.settings.player.extras
    val l = state.settings.layout
    SettingsSection("Trailers") {
        // The TV's one trailer_enabled / trailer_delay_seconds, shared with Home's hover trailer.
        SettingsToggle(
            "Auto-play Trailers",
            "Play a title's trailer on its page after a few seconds, in the built-in player (when it's your Default Player), and in Home's hero when that's on.",
            l.trailersEnabled
        ) { v -> state.updateSettings { it.copy(layout = it.layout.copy(trailersEnabled = v)) } }
        if (l.trailersEnabled) {
            SettingsChoice("Trailer Delay", null, l.trailerDelaySeconds, listOf(3, 5, 7, 10, 15, 20, 30).map { DropdownOption(it, tr("%1\$s s", it)) }.withCurrentInt(l.trailerDelaySeconds)) { v ->
                state.updateSettings { it.copy(layout = it.layout.copy(trailerDelaySeconds = v)) }
            }
        }
        SettingsToggle("4K Trailers", "Play trailers above 1080p when available. Uses much more bandwidth and memory.", e.trailer4k) { v ->
            state.updateExtras { it.copy(trailer4k = v) }
        }
    }
}

/**
 * Auto-play Trailers on a title page: after the delay, with nothing else going on, the trailer
 * plays in the built-in player and closes back to the page at its end. Once per visit.
 */
@Composable
fun TrailerAutoplay(state: AppState, ytId: String?, title: String) {
    val l = state.settings.layout
    val p = state.settings.player
    val enabled = l.trailersEnabled && ytId != null && p.builtInPlayer && !p.askPlayer && LibMpv.available
    val screen = state.screen
    LaunchedEffect(ytId, enabled) {
        if (!enabled || ytId == null) return@LaunchedEffect
        delay(l.trailerDelaySeconds.coerceIn(0, 60) * 1000L)
        if (state.screen == screen && !state.isPlaying && !state.sourcesPanelOpen) state.playTrailer(ytId, title)
    }
}

private val HdrColors = listOf(
    "#FFFFFF" to "White", "#D9D9D9" to "Light gray", "#FFD700" to "Yellow", "#00E5FF" to "Cyan", "#FF5C5C" to "Red", "#00FF88" to "Green"
).map { DropdownOption(it.first, it.second) }

private fun List<DropdownOption<String>>.withCurrent(current: String): List<DropdownOption<String>> =
    if (any { it.value.equals(current, ignoreCase = true) }) this else this + DropdownOption(current.uppercase(), current.uppercase())

private fun List<DropdownOption<Int>>.withCurrentInt(current: Int): List<DropdownOption<Int>> =
    if (any { it.value == current }) this else (this + DropdownOption(current, "$current")).sortedBy { it.value }

/** A text setting shown masked, saved when Enter is pressed or the field loses focus. */
@Composable
private fun SecretTextRow(title: String, description: String?, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    SettingsRow(title, description) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(FetchPalette.Accent),
                modifier = Modifier
                    .width(260.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(FetchPalette.Background)
                    .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            )
            SecondaryButton("Save", onClick = { onSave(text.trim()) }, enabled = text.trim() != value)
        }
    }
}
