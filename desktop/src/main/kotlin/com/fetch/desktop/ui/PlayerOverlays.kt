package com.fetch.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.PlayerPanel
import com.fetch.desktop.PlayerSession
import com.fetch.desktop.data.episodeTag
import com.fetch.desktop.player.embedded.MpvTrack
import com.fetch.tv.core.streams.SpecLine
import com.fetch.tv.core.streams.ranking.StreamLanguage
import kotlinx.coroutines.delay
import java.util.Locale

/** "You're watching": title, episode and synopsis after a few seconds paused, as on TV. */
@Composable
internal fun PauseOverlay(session: PlayerSession, visible: Boolean) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        shown = false
        if (visible) {
            delay(4_000)
            shown = true
        }
    }
    AnimatedVisibility(shown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.3f), Color.Transparent))
            )
        ) {
            val target = session.target
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 64.dp).widthIn(max = 620.dp)
            ) {
                Text("YOU'RE WATCHING", style = OverlineStyle, color = FetchPalette.TextMuted)
                Text(target?.name ?: session.title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                target?.video?.let { v ->
                    Text(
                        listOfNotNull(episodeTag(v.season, v.episode), v.title.takeIf { it.isNotBlank() }).joinToString(" · "),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                session.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = FetchPalette.TextSecondary, fontSize = 16.sp, lineHeight = 24.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                Text("Paused", color = FetchPalette.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** The playing file's details from mpv, as the TV's stream info overlay shows them. Toggled with I. */
@Composable
internal fun StreamInfoOverlay(session: PlayerSession, modifier: Modifier = Modifier, footer: @Composable () -> Unit = {}) {
    val mpv = session.mpv
    val lines by produceState(emptyList<Pair<String, String>>(), session) {
        while (true) {
            value = streamInfo(session)
            delay(1_000)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .widthIn(max = 460.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(18.dp)
    ) {
        Text("Stream info", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        lines.forEach { (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(k, color = FetchPalette.TextMuted, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                Text(v, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        @Suppress("UNUSED_EXPRESSION") mpv.position
        footer()
    }
}

private fun streamInfo(session: PlayerSession): List<Pair<String, String>> {
    val mpv = session.mpv
    fun p(name: String) = mpv.property(name)
    fun kbps(raw: String?) = raw?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.1f Mb/s".format(Locale.ROOT, it / 1_000_000) }
    val width = p("video-params/w")
    val height = p("video-params/h")
    return listOfNotNull(
        session.stream?.addonName?.takeIf { it.isNotBlank() }?.let { "Source" to it },
        (session.stream?.behaviorHints?.filename ?: p("filename"))?.let { "File" to it },
        p("file-format")?.let { "Container" to it },
        p("video-codec")?.let { "Video" to it },
        if (width != null && height != null) "Resolution" to "${width}×$height" else null,
        p("container-fps")?.toDoubleOrNull()?.let { "Frame rate" to "%.3f fps".format(Locale.ROOT, it) },
        listOfNotNull(p("video-params/gamma"), p("video-params/primaries")).takeIf { it.isNotEmpty() }?.let { "Color" to it.joinToString(" · ") },
        kbps(p("video-bitrate"))?.let { "Video bitrate" to it },
        p("hwdec-current")?.let { "Decoder" to if (it == "no") "Software" else "Hardware ($it)" },
        p("audio-codec-name")?.let { "Audio" to it },
        listOfNotNull(p("audio-params/hr-channels") ?: p("audio-params/channels"), p("audio-params/samplerate")?.let { "$it Hz" })
            .takeIf { it.isNotEmpty() }?.let { "Channels" to it.joinToString(" · ") },
        kbps(p("audio-bitrate"))?.let { "Audio bitrate" to it },
        p("demuxer-cache-duration")?.toDoubleOrNull()?.let { "Buffered" to "%.0f s".format(Locale.ROOT, it) },
        p("frame-drop-count")?.takeIf { it != "0" }?.let { "Dropped frames" to it }
    )
}

/** The Up Next card near the end of an episode: a countdown with Play now and Cancel. */
@Composable
internal fun UpNextCard(session: PlayerSession, seconds: Int, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val next = session.nextEpisode ?: return
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .width(380.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(FetchPalette.Surface.copy(alpha = 0.95f))
            .border(1.dp, FetchPalette.SurfaceHigh, RoundedCornerShape(14.dp))
            .padding(20.dp)
    ) {
        Text("UP NEXT IN $seconds S", style = OverlineStyle, color = FetchPalette.Accent)
        Text(
            listOfNotNull(episodeTag(next.season, next.episode), next.title.takeIf { it.isNotBlank() }).joinToString(" · "),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Play now", onClick = onPlay)
            SecondaryButton("Cancel", onClick = onCancel)
        }
    }
}

/** "Are you still watching?" after several episodes in a row. */
@Composable
internal fun StillWatchingOverlay(label: String, onContinue: () -> Unit, onStop: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Are you still watching?", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Up next: $label", color = FetchPalette.TextSecondary, fontSize = 17.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Keep watching", onClick = onContinue)
                SecondaryButton("Stop", onClick = onStop)
            }
        }
    }
}

/** The right-hand panel for Audio, Subtitles, Sources and Episodes, as in the TV player. */
@Composable
internal fun PlayerSidePanel(state: AppState, session: PlayerSession, panel: PlayerPanel, modifier: Modifier = Modifier) {
    LaunchedEffect(panel) {
        when (panel) {
            PlayerPanel.SOURCES -> state.loadPlayerSources()
            PlayerPanel.SUBTITLES -> state.loadAddonSubtitles()
            else -> Unit
        }
    }
    val listState = rememberLazyListState()
    Column(
        modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(FetchPalette.Background.copy(alpha = 0.96f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(top = 24.dp, start = 24.dp, end = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, bottom = 12.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(PlayerPanel.entries.filter { it != PlayerPanel.EPISODES || session.target?.episodes?.any { e -> e.season != null } == true }) { p ->
                    PanelTab(p.label, selected = p == panel) { session.panel = p }
                }
            }
            CircleIconButton(FetchIcons.Close, "Close", onClick = { session.panel = null }, size = 40.dp)
        }
        ScrollbarBox(listState, Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(end = 14.dp, bottom = 24.dp)
            ) {
                when (panel) {
                    PlayerPanel.AUDIO -> {
                        item { DelayRow("Audio delay", session.mpv.audioDelay, step = 0.05, onChange = session.mpv::changeAudioDelay) }
                        audioPanelExtraItems(state, session)
                        item { PanelHeading("Tracks") }
                        items(session.mpv.tracks.filter { it.type == "audio" }) { t ->
                            PanelRow(t.label, detail = trackDetail(t), selected = t.selected) { session.mpv.selectAudio(t.id) }
                        }
                    }
                    // Grouped and filtered as on TV (PlayerExtrasUi.kt).
                    PlayerPanel.SUBTITLES -> subtitlePanelItems(state, session)
                    PlayerPanel.SOURCES -> {
                        if (session.sourcesLoading) item { LoadingRow("Asking your addons…") }
                        items(session.sources, key = { it.stream.stableKey(it.originalIndex) }) { ranked ->
                            val playing = session.stream?.let { it.url == ranked.stream.url && it.addonName == ranked.stream.addonName } == true
                            val spec = SpecLine.of(labels = ranked.descriptor.summaryLabels(), isCached = ranked.descriptor.isDebridCached, cachedLabel = "Cached")?.text
                            PanelRow(
                                title = ranked.stream.addonName.ifBlank { "Source" } + if (playing) " · Playing" else "",
                                detail = listOfNotNull(spec, ranked.stream.behaviorHints?.filename ?: ranked.stream.title).joinToString("\n"),
                                selected = playing
                            ) { if (!playing) state.switchSource(ranked) }
                        }
                    }
                    PlayerPanel.EPISODES -> {
                        val target = session.target
                        val episodes = target?.episodes.orEmpty().filter { it.season != null }
                            .sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
                        items(episodes, key = { it.id }) { ep ->
                            val current = ep.id == target?.video?.id
                            PanelRow(
                                title = listOfNotNull(episodeTag(ep.season, ep.episode), ep.title.takeIf { it.isNotBlank() }).joinToString(" · "),
                                detail = when {
                                    current -> "Playing"
                                    state.isWatched(ep.id) -> "Watched"
                                    else -> ep.overview?.take(90)
                                },
                                selected = current
                            ) { if (!current) state.playEpisodeInPlayer(ep) }
                        }
                    }
                }
            }
        }
    }
}

private fun trackDetail(t: MpvTrack): String? = listOfNotNull("Forced".takeIf { t.forced }, "Default".takeIf { t.isDefault }).joinToString(" · ").ifEmpty { null }

/** "English" for "en" or "eng"; the code itself when it's not a language Fetch knows. */
internal fun languageName(code: String): String = StreamLanguage.fromCode(code)?.label ?: code.uppercase()

@Composable
private fun PanelTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
internal fun PanelHeading(text: String) {
    Text(text.uppercase(), style = OverlineStyle, color = FetchPalette.TextMuted, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp))
}

@Composable
internal fun LoadingRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
        Text(text, color = FetchPalette.TextSecondary)
    }
}

@Composable
internal fun PanelRow(title: String, detail: String? = null, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected -> FetchPalette.Accent.copy(alpha = 0.18f)
                    hovered -> Color.White.copy(alpha = 0.08f)
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = FetchPalette.TextMuted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) Icon(FetchIcons.Check, contentDescription = null, tint = FetchPalette.Accent, modifier = Modifier.size(20.dp))
    }
}

/** − value + for a delay in seconds, with a reset. */
@Composable
internal fun DelayRow(label: String, seconds: Double, step: Double, onChange: (Double) -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("−", onClick = { onChange(seconds - step) })
            Text(
                "%+.2f s".format(Locale.ROOT, seconds),
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.width(80.dp)
            )
            SecondaryButton("+", onClick = { onChange(seconds + step) })
            if (seconds != 0.0) SecondaryButton("Reset", onClick = { onChange(0.0) })
        }
    }
}
