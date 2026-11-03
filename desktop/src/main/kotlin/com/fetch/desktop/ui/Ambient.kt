package com.fetch.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.episodeTag
import com.fetch.desktop.data.remainingLabel
import com.fetch.tv.domain.model.AmbientFace
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One slide: a backdrop with the title's logo (or name) and genres, as the TV's AmbientSlide. */
data class AmbientSlide(val id: String, val title: String, val backdrop: String, val logo: String?, val genres: List<String>)

/**
 * The slides for Ambient Mode: up to 30 titles from each chosen Home row (up to three; the first
 * row when none are chosen), interleaved, at most 60, only those with a backdrop, shuffled — the
 * TV's AmbientSlideLoader, from rows Home already loaded.
 */
fun ambientSlides(rows: List<Pair<String, List<MetaPreview>>>, chosen: List<String>, hideUnreleased: Boolean): List<AmbientSlide> {
    val picked = chosen.mapNotNull { key -> rows.firstOrNull { it.first == key } }.ifEmpty { rows.take(1) }.take(3)
    val lists = picked.map { (_, items) ->
        items.filter { !(hideUnreleased && com.fetch.desktop.isUnreleased(it)) }.shuffled().take(30)
    }
    val longest = lists.maxOfOrNull { it.size } ?: 0
    return (0 until longest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        .distinctBy { "${it.apiType}:${it.id}" }
        .take(60)
        .mapNotNull { p ->
            val backdrop = p.background?.takeIf { it.isNotBlank() } ?: p.landscapePoster?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AmbientSlide("${p.apiType}:${p.id}", p.name, backdrop, p.logo?.takeIf { it.isNotBlank() }, p.genres.take(3))
        }
        .shuffled()
}

/** Ken Burns moves, as the TV's ambientMotions: (startScale, endScale, startX, endX, startY, endY). */
private val Motions = listOf(
    floatArrayOf(1.0f, 1.06f, 0f, 0f, 0f, 0f),
    floatArrayOf(1.07f, 1.01f, 0f, 0f, 0f, 0f),
    floatArrayOf(1.06f, 1.06f, -0.025f, 0.025f, 0f, 0f),
    floatArrayOf(1.06f, 1.06f, 0.025f, -0.025f, 0f, 0f),
    floatArrayOf(1.06f, 1.06f, 0f, 0f, 0.02f, -0.02f),
    floatArrayOf(1.02f, 1.08f, 0f, -0.025f, 0f, -0.02f),
    floatArrayOf(1.02f, 1.08f, 0f, 0.025f, 0f, 0.02f)
)

private const val SLIDE_HOLD_MS = 10_000L
private const val FADE_MS = 1_200

/**
 * Ambient Mode over the whole window: after the chosen minutes with no mouse or key input, and
 * never during playback. Any input closes it; that first input does nothing else.
 */
@Composable
fun AmbientHost(state: AppState, lastInput: Long, onDismiss: () -> Unit, showing: Boolean, onShow: () -> Unit) {
    val settings = state.settings.ambient
    LaunchedEffect(lastInput, settings.enabled, settings.timeoutMinutes, state.isPlaying) {
        if (!settings.enabled || state.isPlaying) return@LaunchedEffect
        delay(settings.timeoutMinutes.coerceAtLeast(1) * 60_000L)
        onShow()
    }
    LaunchedEffect(state.isPlaying, showing) { if (state.isPlaying && showing) onDismiss() }
    AnimatedVisibility(showing, enter = fadeIn(tween(800)), exit = fadeOut(tween(300)), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (settings.face) {
                AmbientFace.CLOCK -> AmbientClock(state.continueWatching.firstOrNull())
                AmbientFace.ARTWORK -> {
                    val slides = remember {
                        ambientSlides(state.rows.map { it.key to it.items.filter(state::allowed) }, settings.catalogKeys, state.settings.hideUnreleased)
                    }
                    if (slides.isEmpty()) AmbientClock(state.continueWatching.firstOrNull()) else AmbientSlideshow(slides)
                }
            }
        }
    }
}

@Composable
private fun AmbientSlideshow(slides: List<AmbientSlide>) {
    var index by remember { mutableStateOf(0) }
    var motion by remember { mutableStateOf(Motions.random()) }
    val backdrop = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            progress.snapTo(0f)
            kotlinx.coroutines.coroutineScope {
                launch {
                    progress.animateTo(1f, tween((SLIDE_HOLD_MS + 3 * FADE_MS).toInt(), easing = LinearEasing))
                }
                backdrop.animateTo(1f, tween(FADE_MS, easing = FastOutSlowInEasing))
                text.animateTo(1f, tween(FADE_MS))
                delay(SLIDE_HOLD_MS)
                text.animateTo(0f, tween(FADE_MS / 2))
                backdrop.animateTo(0f, tween(FADE_MS, easing = FastOutSlowInEasing))
            }
            delay(400)
            index = (index + 1) % slides.size
            motion = Motions.filter { it !== motion }.random()
        }
    }
    val slide = slides[index]
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = slide.backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val p = progress.value
                val scale = lerp(motion[0], motion[1], p)
                scaleX = scale
                scaleY = scale
                translationX = lerp(motion[2], motion[3], p) * size.width
                translationY = lerp(motion[4], motion[5], p) * size.height
                alpha = backdrop.value
            }
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))
            )
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 56.dp).widthIn(max = 640.dp)
                .graphicsLayer { alpha = text.value }
        ) {
            TitleArt(slide.title, slide.logo, maxLogoHeight = 110.dp, fontSize = 44)
            if (slide.genres.isNotEmpty()) {
                Text(slide.genres.joinToString(" · "), color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp)
            }
        }
        SmallClock(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 56.dp))
    }
}

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L - now % 1_000L)
        }
    }
    return now
}

@Composable
private fun SmallClock(modifier: Modifier = Modifier) {
    val now = rememberNow()
    Text(
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(now)),
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 30.sp,
        fontWeight = FontWeight.Light,
        modifier = modifier
    )
}

private val Mono = TextStyle(fontFamily = FontFamily.Monospace)

/**
 * The TV's clock face: a large time whose colon blinks in the accent, the date, and what to
 * resume. It drifts a little each minute so it doesn't burn in.
 */
@Composable
fun AmbientClock(resume: ProgressEntry?) {
    val now = rememberNow()
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochMilli(now)
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(instant)
    val date = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).withZone(zone).format(instant).uppercase()
    val colonLit = (now / 1_000L) % 2L == 0L
    val drift = DriftSteps[((now / 60_000L) % DriftSteps.size).toInt()]
    Box(Modifier.fillMaxSize().background(FetchPalette.Background)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).offset(x = 24.dp * drift.first, y = 24.dp * drift.second)
        ) {
            Text(
                buildAnnotatedString {
                    time.forEach { c ->
                        if (c == ':') withStyle(SpanStyle(color = if (colonLit) FetchPalette.Accent else FetchPalette.Accent.copy(alpha = 0.2f))) { append(c) }
                        else append(c)
                    }
                },
                style = Mono.copy(fontSize = 152.sp, fontWeight = FontWeight.Light),
                color = Color.White
            )
            Text(date, style = Mono.copy(fontSize = 20.sp, letterSpacing = 2.sp), color = FetchPalette.TextSecondary)
            if (resume != null) ResumeWidget(resume, Modifier.padding(top = 36.dp))
        }
    }
}

private val DriftSteps = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f, -1f to 1f, -1f to 0f, -1f to -1f, 0f to -1f, 1f to -1f)

@Composable
private fun ResumeWidget(entry: ProgressEntry, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.width(420.dp).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp)).padding(20.dp)
    ) {
        Text(if (entry.upNext) "UP NEXT" else "RESUME", style = Mono.copy(fontSize = 14.sp, letterSpacing = 1.sp), color = FetchPalette.TextMuted)
        Text(entry.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = listOfNotNull(episodeTag(entry.season, entry.episode), remainingLabel(entry)).joinToString(" · ")
        if (line.isNotEmpty()) Text(line, style = Mono.copy(fontSize = 14.sp), color = FetchPalette.TextSecondary)
        if (!entry.upNext) {
            // The progress in dots, as on TV.
            val filled = (entry.fraction() * 20).toInt()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(20) { i ->
                    Box(
                        Modifier.width(8.dp).height(8.dp).background(
                            if (i < filled) FetchPalette.Accent else Color.White.copy(alpha = 0.2f),
                            RoundedCornerShape(50)
                        )
                    )
                }
            }
        }
    }
}
