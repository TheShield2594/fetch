package com.fetch.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.PlayerSession
import com.fetch.desktop.data.PlayerExtras
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.player.AudioDelayStore
import com.fetch.desktop.player.AudioExtras
import com.fetch.desktop.player.DebugStat
import com.fetch.desktop.player.DebugStatSeverity
import com.fetch.desktop.player.DebugStatsSampler
import com.fetch.desktop.player.MpvSnapshot
import com.fetch.desktop.player.SilenceSkipper
import com.fetch.desktop.player.SubtitleChoice
import com.fetch.desktop.player.SubtitleOrganisation
import com.fetch.desktop.player.SubtitleStyle
import com.fetch.desktop.player.embedded.EmbeddedMpv
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SkipButtonRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

// --- State kept per player session, beside PlayerSession ---

/** What the TV-parity extras remember while one built-in player is open. */
class PlayerSessionExtras {
    /** The live subtitle style panel is open. */
    var stylePanelOpen by mutableStateOf(false)
    /** The playing video is HDR (mpv's video-params/gamma is PQ or HLG). */
    var hdr by mutableStateOf(false)
    /** The addon subtitle auto sync matched, and the delay it applied. */
    var syncedSubtitleUrl by mutableStateOf<String?>(null)
    var syncedDelaySec: Double? = null
    /** "Auto sync: comparing with the video's subtitles…" while it works. */
    var autoSyncStatus by mutableStateOf<String?>(null)
    /** Re-timed copies Auto Sync loaded into mpv (file path → the addon subtitle's URL). */
    val syncedFiles = androidx.compose.runtime.mutableStateMapOf<String, String>()
    /** What Auto Sync did for each addon subtitle, so choosing one again reapplies it at once. */
    val syncResults = mutableMapOf<String, com.fetch.desktop.player.autosync.TimeMap>()
    /** The Skip button is on screen (subtitles move up out of its way). */
    var skipVisible by mutableStateOf(false)
    /** Where a marker recorded here goes on IntroDB; null hides the Mark button (no API key or IMDb id). */
    var markerTarget by mutableStateOf<com.fetch.desktop.skip.MarkerTarget?>(null)
    /** The start the Mark button recorded, waiting for the end. */
    var markerStartSec by mutableStateOf<Double?>(null)
    /** The audio output the delay was last applied for, and that delay. */
    var audioRoute: String? = null
    var appliedAudioDelay: Double? = null
}

private val extrasBySession = java.util.WeakHashMap<PlayerSession, PlayerSessionExtras>()

val PlayerSession.extras: PlayerSessionExtras
    get() = synchronized(extrasBySession) { extrasBySession.getOrPut(this) { PlayerSessionExtras() } }

/** Closes the style panel if it's open (Esc); true when it was. */
fun closeExtraPanels(session: PlayerSession): Boolean {
    val extras = session.extras
    if (!extras.stylePanelOpen) return false
    extras.stylePanelOpen = false
    return true
}

private val audioDelays by lazy { AudioDelayStore() }

private fun AppState.updateExtras(change: (PlayerExtras) -> PlayerExtras) =
    updateSettings { it.copy(player = it.player.copy(extras = change(it.player.extras))) }

private fun AppState.updatePlayerSettings(change: (PlayerSettings) -> PlayerSettings) =
    updateSettings { it.copy(player = change(it.player)) }

/** The languages the subtitle list puts first: yours, or the device's when none is set (as on TV). */
private fun preferredSubtitleKeys(p: PlayerSettings): List<String> =
    SubtitleOrganisation.preferredKeys(p.subtitleLanguage ?: Locale.getDefault().language, p.secondarySubtitleLanguage)

// --- Over the picture: Skip button, stats overlay, and the work behind subtitles and audio ---

/**
 * The built-in player's TV-parity extras under its controls: the Skip button with the TV's
 * wording and timing, the stats overlay, and (with no UI) the live subtitle style with HDR and
 * lift, dialogue boost, skip silence, the audio delay per output and automatic subtitle sync.
 */
@Composable
fun BoxScope.PlayerExtrasLayer(state: AppState, session: PlayerSession, controlsVisible: Boolean) {
    val mpv = session.mpv
    val p = state.settings.player
    val extras = session.extras
    val density = LocalDensity.current
    var viewHeight by remember { mutableStateOf(0f) }
    Box(Modifier.matchParentSize().onSizeChanged { viewHeight = it.height.toFloat() })

    // HDR: PQ (HDR10, HDR10+, Dolby Vision) or HLG.
    LaunchedEffect(session) {
        while (true) {
            val hdr = SubtitleStyle.isHdr(mpv.property("video-params/gamma"), mpv.property("video-params/primaries"))
            if (hdr != extras.hdr) extras.hdr = hdr
            delay(2_000)
        }
    }

    // The subtitle style, live: re-applied when it changes, on HDR, and when the controls come and go.
    val lift = SubtitleStyle.liftPercent(
        previewConfiguredPosition = extras.stylePanelOpen,
        viewHeightPx = viewHeight,
        controlsVisible = controlsVisible,
        controlsClearancePx = with(density) { 170.dp.toPx() },
        skipVisible = extras.skipVisible,
        skipClearancePx = with(density) { 120.dp.toPx() }
    )
    // Each file starts with the options it was loaded with (no HDR, no lift), so this runs again per file.
    LaunchedEffect(p, extras.hdr, lift, mpv.duration, session.title, session.stream?.url) {
        SubtitleStyle.properties(p, extras.hdr, lift).forEach { (name, value) -> mpv.setProperty(name, value) }
    }

    // Dialogue boost: the centre channel's level when surround is mixed down.
    var lastCenterMix by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(p.extras.centerMixLevelDb) {
        val db = p.extras.centerMixLevelDb
        mpv.setProperty("audio-swresample-o", AudioExtras.swresampleOptions(db))
        // The first value came with the file's options; a change needs the audio output rebuilt.
        if (lastCenterMix != null && lastCenterMix != db) mpv.runCommand("ao-reload")
        lastCenterMix = db
    }

    SkipSilenceEffect(state, session)
    AudioDelayPerOutputEffect(state, session)
    AutoSyncEffect(state, session)

    // The Skip button (bottom left, as on TV), unless Up Next is up.
    val upNext = session.upNextCountdown(mpv.position, mpv.duration, p.upNextPromptSeconds) != null
    // The Mark button needs an IntroDB key and a title IntroDB knows by IMDb id, as on TV.
    val introDbKey = p.introDbApiKey?.takeIf { it.isNotBlank() }
    LaunchedEffect(session.target, introDbKey != null) {
        session.extras.markerStartSec = null
        session.extras.markerTarget = null
        val target = session.target ?: return@LaunchedEffect
        if (introDbKey != null) session.extras.markerTarget = state.markerTargetFor(target)
    }
    SkipSegmentButton(
        session = session,
        onReport = { segment -> state.reportSkipSegment(session, segment) },
        controlsVisible = controlsVisible,
        hidden = upNext,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 150.dp)
    )

    if (p.extras.statsHudButton && p.extras.statsHudActive && mpv.error == null) {
        DebugStatsOverlay(mpv, Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 96.dp))
    }
}

/** The style panel, over the controls like the other side panels. */
@Composable
fun BoxScope.PlayerExtrasPanels(state: AppState, session: PlayerSession) {
    if (session.extras.stylePanelOpen) {
        SubtitleStylePanel(state, session, Modifier.align(Alignment.CenterEnd))
    }
}

/** The stats overlay switch for the stream info box (shown when Settings › Player › Advanced allows it). */
@Composable
fun StatsHudToggle(state: AppState) {
    val extras = state.settings.player.extras
    if (!extras.statsHudButton) return
    Row(Modifier.padding(top = 10.dp)) {
        SecondaryButton(
            if (extras.statsHudActive) "Hide stats overlay" else "Show stats overlay",
            onClick = { state.updateExtras { it.copy(statsHudActive = !it.statsHudActive) } }
        )
    }
}

// --- Skip button ---

@Composable
private fun SkipSegmentButton(
    session: PlayerSession,
    onReport: (Segment) -> Unit,
    controlsVisible: Boolean,
    hidden: Boolean,
    modifier: Modifier
) {
    val mpv = session.mpv
    val candidates = if (session.skipButton) session.segments.filter { !it.skipsAutomatically(session.autoSkip) } else emptyList()
    val active: Segment? = if (hidden) null else SkipButtonRules.active(candidates, mpv.position)
    var shown by remember { mutableStateOf<Segment?>(null) }
    if (active != null) shown = active
    var autoHidden by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val timeout = SkipButtonRules.autoHideMs(active)
    LaunchedEffect(active?.startSec, active?.type) {
        autoHidden = false
        progress.snapTo(0f)
    }
    // The countdown runs while the controls are hidden, for the length of the segment.
    LaunchedEffect(active != null, autoHidden, controlsVisible) {
        if (active != null && !autoHidden && !controlsVisible) {
            val remaining = ((1f - progress.value) * timeout).toInt().coerceAtLeast(1)
            progress.animateTo(1f, tween(remaining, easing = LinearEasing))
            autoHidden = true
        }
    }
    val visible = SkipButtonRules.visible(active != null, dismissed = false, controlsVisible = controlsVisible, autoHidden = autoHidden)
    LaunchedEffect(visible) { session.extras.skipVisible = visible }
    val segment = shown ?: return
    val targetsPostCredits = SkipButtonRules.followingPostCredits(segment, session.segments, mpv.duration) != null
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val interaction = remember { MutableInteractionSource() }
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                .clickable(interactionSource = interaction, indication = null) {
                    session.controls.seekTo(SkipButtonRules.target(segment, session.segments, mpv.duration))
                }
                .pointerHoverIcon(PointerIcon.Hand)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Icon(FetchIcons.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(SkipButtonRules.label(segment, targetsPostCredits), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                // An AniSkip marker can be reported as wrong (the TV's long press on the button).
                if (com.fetch.desktop.skip.SkipMarkerRules.canReport(segment)) {
                    Text(
                        "Wrong?",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onReport(segment) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            // The countdown bar: how long the button stays when left alone.
            val countdownHidden = controlsVisible || autoHidden
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = if (countdownHidden) 0f else 0.15f))
                    .drawBehind {
                        val f = progress.value.coerceIn(0f, 1f)
                        if (!countdownHidden && f > 0f) drawRect(Color.White.copy(alpha = 0.7f), size = Size(size.width * f, size.height))
                    }
            )
        }
    }
}

// --- Skip silence ---

@Composable
private fun SkipSilenceEffect(state: AppState, session: PlayerSession) {
    val mpv = session.mpv
    val enabled = state.settings.player.extras.skipSilence
    // A watch party keeps everyone at one speed.
    val inParty = state.party.state.collectAsState().value.party != null
    // Per file too: loading one with a volume boost replaces the filter list.
    LaunchedEffect(enabled, inParty, session.title, session.stream?.url) {
        mpv.runCommand("af", "remove", "@${AudioExtras.SILENCE_FILTER_LABEL}")
        if (!enabled || inParty) return@LaunchedEffect
        mpv.runCommand("af", "add", AudioExtras.SILENCE_FILTER)
        val skipper = SilenceSkipper()
        var base = mpv.speed
        try {
            while (true) {
                delay(150)
                if (mpv.paused || mpv.buffering) {
                    if (skipper.reset() != null) mpv.changeSpeed(base)
                    continue
                }
                if (!skipper.hurrying) base = mpv.speed
                val level = AudioExtras.rmsLevel(mpv.property("af-metadata/${AudioExtras.SILENCE_FILTER_LABEL}"))
                skipper.sample(level, System.nanoTime() / 1e9)?.let { factor -> mpv.changeSpeed(base * factor) }
            }
        } finally {
            if (skipper.hurrying) mpv.changeSpeed(base)
            mpv.runCommand("af", "remove", "@${AudioExtras.SILENCE_FILTER_LABEL}")
        }
    }
}

// --- Audio delay per output ---

@Composable
private fun AudioDelayPerOutputEffect(state: AppState, session: PlayerSession) {
    val mpv = session.mpv
    val extras = session.extras
    val remember = state.settings.player.extras.rememberAudioDelayPerDevice
    // Which output plays, checked every few seconds (the TV listens for device changes).
    LaunchedEffect(session, remember) {
        if (!remember) return@LaunchedEffect
        while (true) {
            val route = withContext(Dispatchers.IO) {
                AudioExtras.routeKey(mpv.property("audio-device"), mpv.property("current-ao"), AudioExtras.defaultSink())
            }
            if (route != null && route != extras.audioRoute) {
                extras.audioRoute = route
                val seconds = (audioDelays.delayMs(route) ?: 0) / 1000.0
                extras.appliedAudioDelay = seconds
                mpv.changeAudioDelay(seconds)
            }
            delay(5_000)
        }
    }
    // A delay set in the player is kept for that output.
    LaunchedEffect(mpv.audioDelay, remember) {
        if (!remember) return@LaunchedEffect
        val route = extras.audioRoute ?: return@LaunchedEffect
        val applied = extras.appliedAudioDelay
        if (applied != null && kotlin.math.abs(applied - mpv.audioDelay) < 0.0005) return@LaunchedEffect
        delay(800)
        extras.appliedAudioDelay = mpv.audioDelay
        withContext(Dispatchers.IO) { audioDelays.save(route, Math.round(mpv.audioDelay * 1000).toInt()) }
    }
}

// --- Stats overlay ---

@Composable
private fun DebugStatsOverlay(mpv: EmbeddedMpv, modifier: Modifier) {
    val sampler = remember { DebugStatsSampler() }
    var stats by remember { mutableStateOf(emptyList<DebugStat>()) }
    LaunchedEffect(mpv) {
        while (true) {
            val snapshot = MpvSnapshot(
                aheadSec = mpv.property("demuxer-cache-duration")?.toDoubleOrNull(),
                videoBitrate = mpv.property("video-bitrate")?.toDoubleOrNull(),
                audioBitrate = mpv.property("audio-bitrate")?.toDoubleOrNull(),
                fileSizeBytes = mpv.property("file-size")?.toLongOrNull(),
                durationSec = mpv.duration.takeIf { it > 0 },
                readBytesPerSec = mpv.property("cache-speed")?.toDoubleOrNull(),
                droppedFrames = (mpv.property("frame-drop-count")?.toIntOrNull() ?: 0) + (mpv.property("decoder-frame-drop-count")?.toIntOrNull() ?: 0)
            )
            stats = withContext(Dispatchers.IO) { sampler.sample(snapshot) }
            delay(1_000)
        }
    }
    if (stats.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.78f)).padding(14.dp)
    ) {
        stats.forEach { stat ->
            Row {
                Text(stat.label, modifier = Modifier.width(72.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = FetchPalette.TextMuted)
                val (color, weight) = when (stat.severity) {
                    DebugStatSeverity.DANGER -> FetchPalette.Error to FontWeight.Bold
                    DebugStatSeverity.WARNING -> Color(0xFFF5A524) to FontWeight.Bold
                    DebugStatSeverity.NORMAL -> Color.White to FontWeight.Normal
                }
                Text(stat.value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = weight, color = color)
            }
        }
    }
}

// --- The Subtitles panel's list ---

/**
 * The Subtitles panel, organised as on TV: the delay, the style panel, then the file's tracks and
 * the addons' subtitles grouped by language (your languages first) or by source, optionally only
 * your languages.
 */
internal fun LazyListScope.subtitlePanelItems(state: AppState, session: PlayerSession) {
    val mpv = session.mpv
    val p = state.settings.player
    val extras = session.extras
    item { DelayRow("Subtitle delay (Z / X)", mpv.subtitleDelay, step = 0.1, onChange = mpv::changeSubtitleDelay) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            SecondaryButton("Style", onClick = {
                session.panel = null
                extras.stylePanelOpen = true
            })
        }
    }
    extras.autoSyncStatus?.let { status -> item { LoadingRow(status) } }
    val subs = mpv.tracks.filter { it.type == "sub" }
    item { PanelRow("Off", selected = subs.none { it.selected }) { mpv.selectSubtitle(null) } }
    val preferred = preferredSubtitleKeys(p)
    val groups = SubtitleOrganisation.organise(
        tracks = mpv.tracks,
        addonSubtitles = session.addonSubtitles.orEmpty(),
        mode = p.extras.subtitleOrganization,
        preferred = preferred,
        onlyPreferred = p.extras.subtitleOnlyPreferredLanguages,
        addonOrder = state.addons.map { it.displayName },
        // Auto Sync's re-timed copies stand for their addon subtitle.
        aliases = extras.syncedFiles.toMap()
    )
    groups.forEach { group ->
        if (group.label.isNotBlank()) item(key = "group:${group.key}") { PanelHeading(group.label) }
        items(group.choices, key = { choice ->
            when (choice) {
                is SubtitleChoice.Track -> "track:${choice.track.id}"
                is SubtitleChoice.Addon -> "addon:${choice.subtitle.url}"
            }
        }) { choice ->
            when (choice) {
                is SubtitleChoice.Track -> {
                    val t = choice.track
                    PanelRow(
                        t.label,
                        detail = listOfNotNull("Built-in".takeIf { !t.external }, "Forced".takeIf { t.forced }, "Default".takeIf { t.isDefault })
                            .joinToString(" · ").ifEmpty { null },
                        selected = t.selected
                    ) { mpv.selectSubtitle(t.id) }
                }
                is SubtitleChoice.Addon -> {
                    val sub = choice.subtitle
                    val added = choice.added
                    val synced = added?.selected == true && (extras.syncedSubtitleUrl == sub.url || added.externalFilename in extras.syncedFiles)
                    PanelRow(
                        SubtitleOrganisation.languageLabel(choice.languageKey),
                        detail = listOfNotNull(
                            "Auto synced".takeIf { synced },
                            sub.addon.ifBlank { "Addon" },
                            sub.url.substringAfterLast('/').substringBefore('?').take(60)
                        ).joinToString(" · "),
                        selected = added?.selected == true
                    ) {
                        if (added != null) mpv.selectSubtitle(added.id)
                        else mpv.addSubtitle(sub.url, SubtitleOrganisation.languageLabel(choice.languageKey), sub.language)
                    }
                }
            }
        }
    }
    when {
        session.addonSubtitlesLoading -> item { LoadingRow("Asking your addons…") }
        session.addonSubtitles.isNullOrEmpty() && session.target != null -> item {
            Text("No addon subtitles.", color = FetchPalette.TextMuted, modifier = Modifier.padding(8.dp))
        }
    }
    if (p.extras.subtitleOnlyPreferredLanguages) {
        val hidden = SubtitleOrganisation.hiddenCount(mpv.tracks, session.addonSubtitles.orEmpty(), preferred, p.extras.subtitleOrganization, extras.syncedFiles.toMap())
        if (hidden > 0) item {
            PanelRow(
                "Show all languages",
                detail = "$hidden in other languages hidden (Settings › Subtitles › Show only preferred languages)",
                selected = false
            ) { state.updateExtras { it.copy(subtitleOnlyPreferredLanguages = false) } }
        }
    }
}

/** The Audio panel's dialogue boost (the TV's centre mix level), for surround tracks. */
internal fun LazyListScope.audioPanelExtraItems(state: AppState, session: PlayerSession) {
    val track = session.mpv.tracks.firstOrNull { it.type == "audio" && it.selected }
    val db = state.settings.player.extras.centerMixLevelDb
    val applies = AudioExtras.centerMixApplies(track?.channels)
    item {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dialogue boost (centre mix)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("−", enabled = applies && db > PlayerExtras.CENTER_MIX_MIN_DB, onClick = {
                    state.updateExtras { it.copy(centerMixLevelDb = (it.centerMixLevelDb - 1).coerceAtLeast(PlayerExtras.CENTER_MIX_MIN_DB)) }
                })
                Text("%+d dB".format(Locale.ROOT, db), color = Color.White, fontSize = 16.sp, modifier = Modifier.width(80.dp))
                SecondaryButton("+", enabled = applies && db < PlayerExtras.CENTER_MIX_MAX_DB, onClick = {
                    state.updateExtras { it.copy(centerMixLevelDb = (it.centerMixLevelDb + 1).coerceAtMost(PlayerExtras.CENTER_MIX_MAX_DB)) }
                })
                if (db != 0) SecondaryButton("Reset", onClick = { state.updateExtras { it.copy(centerMixLevelDb = 0) } })
            }
            Text(
                if (applies) "Raises the dialogue when surround is mixed down to fewer speakers (Settings › Audio › Channels)."
                else "Only for surround tracks mixed down to fewer speakers.",
                color = FetchPalette.TextMuted,
                fontSize = 12.sp
            )
        }
    }
}

// --- The live subtitle style panel ---

/**
 * The TV's subtitle style side panel: size, bold, text color and opacity (the HDR color and
 * brightness on HDR video when the HDR style is on), outline, background and position, applied
 * as you change them and saved as your defaults.
 */
@Composable
private fun SubtitleStylePanel(state: AppState, session: PlayerSession, modifier: Modifier) {
    val p = state.settings.player
    val e = p.extras
    val hdr = session.extras.hdr && e.subtitleHdrStyle
    Column(
        modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(FetchPalette.Background.copy(alpha = 0.96f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(top = 24.dp, start = 24.dp, end = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            CircleIconButton(FetchIcons.Back, "Subtitles", onClick = {
                session.extras.stylePanelOpen = false
                session.panel = com.fetch.desktop.PlayerPanel.SUBTITLES
            }, size = 40.dp)
            Text("Subtitle style", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CircleIconButton(FetchIcons.Close, "Close", onClick = { session.extras.stylePanelOpen = false }, size = 40.dp)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StyleCard("Font size") {
                Stepper("${Math.round(p.subtitleScale * 100)}%",
                    onMinus = { state.updatePlayerSettings { it.copy(subtitleScale = ((it.subtitleScale * 10).let(Math::round) - 1).coerceAtLeast(5) / 10.0) } },
                    onPlus = { state.updatePlayerSettings { it.copy(subtitleScale = ((it.subtitleScale * 10).let(Math::round) + 1).coerceAtMost(20) / 10.0) } })
            }
            StyleCard("Bold") {
                ToggleChip(p.subtitleBold) { state.updatePlayerSettings { it.copy(subtitleBold = !it.subtitleBold) } }
            }
            StyleCard(if (hdr) "Text color (HDR)" else "Text color") {
                val current = if (hdr) e.subtitleHdrColor else p.subtitleColor
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SubtitleStyle.TextPresets.forEach { preset ->
                            ColorChip(preset, selected = SubtitleStyle.withOpacity(current, 100).equals(preset, ignoreCase = true)) {
                                val color = SubtitleStyle.keepOpacity(preset, current)
                                if (hdr) state.updateExtras { it.copy(subtitleHdrColor = color) } else state.updatePlayerSettings { it.copy(subtitleColor = color) }
                            }
                        }
                    }
                    val opacity = SubtitleStyle.opacityPercent(current)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (hdr) "Opacity (HDR)" else "Opacity", color = FetchPalette.TextMuted, fontSize = 13.sp, modifier = Modifier.width(90.dp))
                        Stepper("$opacity%",
                            onMinus = {
                                val color = SubtitleStyle.withOpacity(current, (opacity - 10).coerceAtLeast(0))
                                if (hdr) state.updateExtras { it.copy(subtitleHdrColor = color) } else state.updatePlayerSettings { it.copy(subtitleColor = color) }
                            },
                            onPlus = {
                                val color = SubtitleStyle.withOpacity(current, (opacity + 10).coerceAtMost(100))
                                if (hdr) state.updateExtras { it.copy(subtitleHdrColor = color) } else state.updatePlayerSettings { it.copy(subtitleColor = color) }
                            })
                    }
                }
            }
            if (hdr) {
                StyleCard("Brightness (HDR)") {
                    Stepper("${e.subtitleHdrBrightness}%",
                        onMinus = { state.updateExtras { it.copy(subtitleHdrBrightness = (it.subtitleHdrBrightness - 10).coerceAtLeast(0)) } },
                        onPlus = { state.updateExtras { it.copy(subtitleHdrBrightness = (it.subtitleHdrBrightness + 10).coerceAtMost(100)) } })
                }
            }
            StyleCard("Outline") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleChip(p.subtitleOutline && !p.subtitleBackground) {
                        state.updatePlayerSettings { it.copy(subtitleOutline = !(it.subtitleOutline && !it.subtitleBackground), subtitleBackground = false) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SubtitleStyle.OutlinePresets.forEach { preset ->
                            ColorChip(preset, selected = p.subtitleOutline && p.subtitleOutlineColor.equals(preset, ignoreCase = true), dim = !p.subtitleOutline) {
                                state.updatePlayerSettings { it.copy(subtitleOutline = true, subtitleBackground = false, subtitleOutlineColor = preset) }
                            }
                        }
                    }
                }
            }
            StyleCard("Background box") {
                ToggleChip(p.subtitleBackground) { state.updatePlayerSettings { it.copy(subtitleBackground = !it.subtitleBackground) } }
            }
            StyleCard("Position") {
                Stepper(if (p.subtitlePosition >= 100) "Bottom" else "${100 - p.subtitlePosition}% up",
                    onMinus = { state.updatePlayerSettings { it.copy(subtitlePosition = (it.subtitlePosition + 5).coerceAtMost(SubtitleStyle.MAX_POSITION)) } },
                    onPlus = { state.updatePlayerSettings { it.copy(subtitlePosition = (it.subtitlePosition - 5).coerceAtLeast(SubtitleStyle.MIN_POSITION)) } },
                    minus = "▼", plus = "▲")
            }
            StyleCard("Defaults") {
                SecondaryButton("Reset", onClick = {
                    val d = PlayerSettings()
                    state.updateSettings {
                        it.copy(player = it.player.copy(
                            subtitleScale = d.subtitleScale, subtitleBold = d.subtitleBold, subtitleColor = d.subtitleColor,
                            subtitleOutline = d.subtitleOutline, subtitleOutlineColor = d.subtitleOutlineColor, subtitleOutlineSize = d.subtitleOutlineSize,
                            subtitleBackground = d.subtitleBackground, subtitlePosition = d.subtitlePosition,
                            extras = it.player.extras.copy(subtitleHdrColor = d.extras.subtitleHdrColor, subtitleHdrBrightness = d.extras.subtitleHdrBrightness)
                        ))
                    }
                })
            }
            Text("Changes apply now and become your defaults.", color = FetchPalette.TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StyleCard(title: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)).padding(14.dp)
    ) {
        Text(title, color = FetchPalette.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, minus: String = "−", plus: String = "+") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(minus, onClick = onMinus)
        Text(value, color = Color.White, fontSize = 16.sp, modifier = Modifier.width(90.dp))
        SecondaryButton(plus, onClick = onPlus)
    }
}

@Composable
private fun ToggleChip(on: Boolean, onClick: () -> Unit) {
    SecondaryButton(if (on) "On" else "Off", onClick = onClick, icon = if (on) FetchIcons.Check else null)
}

@Composable
private fun ColorChip(hex: String, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    val argb = com.fetch.desktop.player.Argb.parse(hex) ?: com.fetch.desktop.player.Argb.WHITE
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(argb).copy(alpha = if (dim) 0.35f else 1f))
            .border(if (selected) 3.dp else 1.dp, if (selected) FetchPalette.Accent else Color.White.copy(alpha = 0.3f), CircleShape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    )
}
