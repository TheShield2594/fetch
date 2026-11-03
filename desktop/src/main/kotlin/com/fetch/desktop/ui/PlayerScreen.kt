package com.fetch.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.fetch.desktop.AppState
import com.fetch.desktop.PlayerPanel
import com.fetch.desktop.PlayerSession
import com.fetch.desktop.data.VideoFit
import kotlinx.coroutines.delay
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/** The window, so the player can go fullscreen. */
val LocalWindowState = staticCompositionLocalOf<WindowState?> { null }

/** An invisible cursor, for while the controls are hidden. */
private val HiddenCursor: PointerIcon by lazy {
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"))
}

/**
 * The built-in player, with Fetch's own controls over the picture as on the TV app: title and
 * back at the top, a seek bar, play/pause, ±10 s, audio and subtitle tracks, volume and
 * fullscreen at the bottom, and a Skip button for intros and credits.
 *
 * Keys: Space or K play/pause, ←/→ (J/L) 10 s, ↑/↓ volume, M mute, F fullscreen, Esc leaves
 * fullscreen, then the player.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(state: AppState, session: PlayerSession) {
    val mpv = session.mpv
    // Pause, seek and speed: mpv's, or the watch party's while one follows this player.
    val controls = session.controls
    val partyAvailable by state.party.available.collectAsState()
    val inParty = state.party.state.collectAsState().value.party != null
    val window = LocalWindowState.current
    var controlsVisible by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    val focus = remember { FocusRequester() }
    val poke = {
        lastActivity = System.currentTimeMillis()
        controlsVisible = true
    }
    fun toggleFullscreen() {
        val w = window ?: return
        w.placement = if (w.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }
    fun leave() {
        if (window?.placement == WindowPlacement.Fullscreen) window.placement = WindowPlacement.Floating
        state.closePlayer()
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    // Controls hide after three seconds without the pointer moving, unless paused.
    LaunchedEffect(lastActivity, mpv.paused) {
        delay(3_000)
        if (!mpv.paused) controlsVisible = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPointerEvent(PointerEventType.Move) { poke() }
            .pointerHoverIcon(if (controlsVisible) PointerIcon.Default else HiddenCursor)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                poke()
                when (event.key) {
                    Key.Spacebar, Key.K -> controls.togglePause()
                    Key.I -> session.showInfo = !session.showInfo
                    Key.N -> if (session.nextEpisode != null) state.playNextNow() else return@onKeyEvent false
                    Key.Z -> mpv.changeSubtitleDelay(mpv.subtitleDelay - 0.1)
                    Key.X -> mpv.changeSubtitleDelay(mpv.subtitleDelay + 0.1)
                    Key.LeftBracket -> controls.changeSpeed(previousSpeed(controls.speed))
                    Key.RightBracket -> controls.changeSpeed(nextSpeed(controls.speed))
                    Key.Backspace -> controls.changeSpeed(1.0)
                    Key.DirectionLeft, Key.J -> controls.seekBy(-10.0)
                    Key.DirectionRight, Key.L -> controls.seekBy(10.0)
                    Key.DirectionUp -> mpv.changeVolume(mpv.volume + 5)
                    Key.DirectionDown -> mpv.changeVolume(mpv.volume - 5)
                    Key.M -> mpv.toggleMute()
                    Key.F -> toggleFullscreen()
                    Key.Escape -> when {
                        session.partyPanelOpen -> session.partyPanelOpen = false
                        state.extras.queuePanelOpen -> state.extras.queuePanelOpen = false
                        session.panel != null -> session.panel = null
                        window?.placement == WindowPlacement.Fullscreen -> toggleFullscreen()
                        else -> leave()
                    }
                    else -> return@onKeyEvent false
                }
                true
            }
    ) {
        // The picture, letterboxed. A click toggles pause, a double-click fullscreen.
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { mpv.setViewSize(it.width, it.height) }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    poke()
                    controls.togglePause()
                }
        ) {
            @Suppress("UNUSED_EXPRESSION") mpv.frameVersion // redraw on every new frame
            val frame = mpv.frame() ?: return@Canvas
            val scale = minOf(size.width / frame.width, size.height / frame.height)
            val w = (frame.width * scale).toInt()
            val h = (frame.height * scale).toInt()
            drawImage(
                frame.asComposeImageBitmap(),
                dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                dstSize = IntSize(w, h),
                filterQuality = FilterQuality.Medium
            )
        }

        if (mpv.buffering || mpv.frame() == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = FetchPalette.Accent)
        }
        mpv.error?.let { error ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(error, color = Color.White, fontSize = 18.sp)
                SecondaryButton("Close", onClick = ::leave)
            }
        }

        // Paused for a few seconds with the controls gone: what's on, as on TV.
        if (state.settings.player.pauseOverlay) PauseOverlay(session, visible = mpv.paused && !controlsVisible)
        if (session.showInfo) StreamInfoOverlay(session, Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 32.dp))
        session.upNextCountdown(mpv.position, mpv.duration, state.settings.player.upNextPromptSeconds)?.let { seconds ->
            UpNextCard(
                session = session,
                seconds = seconds,
                status = state.extras.upNextStatus,
                onPlay = state::playNextNow,
                onCancel = state::cancelUpNext,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 150.dp)
            )
        }

        // Skip intro / recap / credits, for segments not skipped automatically.
        session.skippable(mpv.position)?.takeIf { session.upNextCountdown(mpv.position, mpv.duration, state.settings.player.upNextPromptSeconds) == null }?.let { segment ->
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 150.dp)) {
                PrimaryButton("Skip ${segment.displayLabel}", icon = FetchIcons.ChevronRight, onClick = {
                    controls.seekTo(segment.endSec)
                })
            }
        }

        AnimatedVisibility(controlsVisible || mpv.paused, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top: back and title.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                ) {
                    CircleIconButton(FetchIcons.Back, "Back", onClick = ::leave)
                    Text(session.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Bottom: seek bar and buttons.
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .padding(horizontal = 32.dp, vertical = 20.dp)
                ) {
                    val duration = mpv.duration.coerceAtLeast(0.0)
                    val shown = seeking ?: mpv.position.toFloat()
                    Slider(
                        value = shown.coerceIn(0f, duration.toFloat().coerceAtLeast(0.01f)),
                        onValueChange = {
                            poke()
                            seeking = it
                        },
                        onValueChangeFinished = {
                            seeking?.let { controls.seekTo(it.toDouble()) }
                            seeking = null
                        },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(0.01f),
                        colors = SliderDefaults.colors(
                            thumbColor = FetchPalette.Accent,
                            activeTrackColor = FetchPalette.Accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircleIconButton(if (mpv.paused) FetchIcons.Play else FetchIcons.Pause, if (mpv.paused) "Play" else "Pause", onClick = controls::togglePause)
                        CircleIconButton(FetchIcons.Replay10, "Back 10 seconds", onClick = { controls.seekBy(-10.0) })
                        CircleIconButton(FetchIcons.Forward10, "Forward 10 seconds", onClick = { controls.seekBy(10.0) })
                        Text(
                            "${clock(shown.toDouble())} / ${clock(duration)}",
                            color = Color.White,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        CircleIconButton(if (mpv.muted) FetchIcons.VolumeOff else FetchIcons.VolumeUp, "Mute", onClick = mpv::toggleMute)
                        Slider(
                            value = mpv.volume.toFloat().coerceIn(0f, 130f),
                            onValueChange = { mpv.changeVolume(it.toDouble()) },
                            valueRange = 0f..130f,
                            modifier = Modifier.width(120.dp),
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                        )
                        CircleIconButton(FetchIcons.Fullscreen, "Fullscreen", onClick = ::toggleFullscreen)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (session.nextEpisode != null) {
                            SecondaryButton("Next episode", onClick = state::playNextNow, icon = FetchIcons.ChevronRight)
                        }
                        ValueMenu("Speed", speedLabel(controls.speed), Speeds.map { it to speedLabel(it) }, controls.speed) { controls.changeSpeed(it) }
                        ValueMenu("Picture", session.fit.label, VideoFit.entries.map { it to it.label }, session.fit) { state.setFit(it) }
                        PlayerPanel.entries.filter { panelAvailable(session, it) }.forEach { panel ->
                            SecondaryButton(panel.label, onClick = {
                                session.partyPanelOpen = false
                                session.panel = if (session.panel == panel) null else panel
                            })
                        }
                        SecondaryButton("Queue", icon = FetchIcons.List, onClick = {
                            session.panel = null
                            session.partyPanelOpen = false
                            state.extras.queuePanelOpen = !state.extras.queuePanelOpen
                        })
                        if (partyAvailable || inParty) {
                            SecondaryButton("Watch party", icon = PartyIcon, onClick = {
                                session.panel = null
                                session.partyPanelOpen = !session.partyPanelOpen
                            })
                        }
                        CircleIconButton(FetchIcons.Info, "Stream info (I)", onClick = { session.showInfo = !session.showInfo })
                    }
                }
            }
        }

        PartyPlayerPill(state, session, controlsVisible || mpv.paused, Modifier.align(Alignment.TopCenter).padding(top = 28.dp))
        session.panel?.let { panel ->
            PlayerSidePanel(state, session, panel, Modifier.align(Alignment.CenterEnd))
        }
        if (session.partyPanelOpen) PartyPlayerPanel(state, session, Modifier.align(Alignment.CenterEnd))
        // Content warnings, the Queue panel and "Up next in queue", the movie's recommendation.
        PlayerExtrasLayer(state, session)
        state.stillWatching?.let { prompt ->
            StillWatchingOverlay(prompt.label, onContinue = { state.answerStillWatching(true) }, onStop = { state.answerStillWatching(false) })
        }
    }
}

/** The speeds the TV player offers. */
internal val Speeds = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

private fun previousSpeed(current: Double) = Speeds.lastOrNull { it < current - 0.001 } ?: Speeds.first()
private fun nextSpeed(current: Double) = Speeds.firstOrNull { it > current + 0.001 } ?: Speeds.last()
internal fun speedLabel(speed: Double) = if (speed == 1.0) "Normal" else "${"%.2f".format(java.util.Locale.ROOT, speed).trimEnd('0').trimEnd('.')}×"

private fun panelAvailable(session: PlayerSession, panel: PlayerPanel): Boolean = when (panel) {
    PlayerPanel.EPISODES -> session.target?.episodes?.any { it.season != null } == true
    PlayerPanel.SOURCES -> session.target != null
    else -> true
}

/** A pill opening a menu of values, the current one highlighted. */
@Composable
private fun <T> ValueMenu(label: String, current: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton("$label: $current", onClick = { open = true }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = FetchPalette.SurfaceRaised) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text, color = if (value == selected) FetchPalette.Accent else Color.White) }, onClick = {
                    open = false
                    onPick(value)
                })
            }
        }
    }
}

internal fun clock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
