package com.fetch.tv.ui.screens.home

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.components.FetchCardDefaults
import com.fetch.tv.ui.components.LightweightCard
import com.fetch.tv.ui.theme.DotoFamily
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.SpaceMonoFamily
import com.fetch.tv.ui.util.asStable
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal const val MODERN_AT_A_GLANCE_ROW_KEY = "at_a_glance"

/** A row needs this many tiles; one tile would only repeat the card above it. */
private const val AT_A_GLANCE_MIN_TILES = 2

/** What an "At a glance" tile says about the title it points at. */
@Immutable
sealed class HomeWidget {
    /** The most recent unfinished title, and the clock time it would end if resumed now. */
    data class FinishBy(val remainingMs: Long) : HomeWidget()

    /** How many titles will start instantly; the tile opens the first. */
    data class Ready(val count: Int) : HomeWidget()

    /** The unfinished title with the least left. */
    data class AlmostDone(val remainingMinutes: Int) : HomeWidget()

    /** The next episode of a show, as "S3 E1". */
    data class NextUp(val episode: String) : HomeWidget()
}

/** The wall clock "Finish by" reads; screenshot tests provide a fixed time. */
internal val LocalHomeClock = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/**
 * Signal's "At a glance" row, built from cards Home already has: each tile is one of those cards
 * (same payload, same hero) drawn as a widget, so focusing it fills the hero and OK does what the
 * card would. Goes in after Continue Watching and the rows like it, before the catalogs.
 */
internal fun ModernHomePresentationState.withAtAGlanceRow(title: String): ModernHomePresentationState {
    val byKey = rows.list.associateBy { it.key }
    val continueWatching = byKey[MODERN_CONTINUE_WATCHING_ROW_KEY]?.items?.list.orEmpty()
    val inProgress = continueWatching.mapNotNull { item ->
        val progress = ((item.payload as? ModernPayload.ContinueWatching)?.item as? ContinueWatchingItem.InProgress)
            ?.progress ?: return@mapNotNull null
        val remaining = (progress.duration - progress.position).takeIf { progress.duration > 0 && it > 0 }
            ?: return@mapNotNull null
        item to remaining
    }
    val tiles = buildList {
        inProgress.firstOrNull()?.let { (item, remaining) ->
            add(item.asWidget("finish", HomeWidget.FinishBy(remaining)))
        }
        byKey[MODERN_READY_TONIGHT_ROW_KEY]?.items?.list?.takeIf { it.isNotEmpty() }?.let { ready ->
            add(ready.first().asWidget("ready", HomeWidget.Ready(ready.size)))
        }
        inProgress.drop(1).minByOrNull { it.second }?.let { (item, remaining) ->
            add(item.asWidget("almost", HomeWidget.AlmostDone((remaining / 60_000.0).roundToInt().coerceAtLeast(1))))
        }
        continueWatching.firstNotNullOfOrNull { item ->
            val nextUp = (item.payload as? ModernPayload.ContinueWatching)?.item as? ContinueWatchingItem.NextUp
            nextUp?.let { item to it.info }
        }?.let { (item, info) ->
            add(item.asWidget("next", HomeWidget.NextUp("S${info.season} E${info.episode}")))
        }
    }
    if (tiles.size < AT_A_GLANCE_MIN_TILES) return this
    val insertAt = rows.list.indexOfFirst { !isContinueWatchingStyleRow(it.key) }.let { if (it < 0) rows.list.size else it }
    val row = HeroCarouselRow(
        key = MODERN_AT_A_GLANCE_ROW_KEY,
        title = title,
        globalRowIndex = -1,
        items = tiles.asStable()
    )
    val newRows = rows.list.toMutableList().apply { add(insertAt, row) }
    return ModernHomePresentationState(rows = newRows.asStable(), lookups = buildCarouselRowLookups(newRows))
}

private fun ModernCarouselItem.asWidget(name: String, widget: HomeWidget) =
    copy(key = "glance_${name}_$key", widget = widget)

/** One tile of the "At a glance" row, focused and clicked like the card it stands for. */
@Composable
internal fun HomeWidgetRowItem(
    item: ModernCarouselItem,
    widget: HomeWidget,
    requester: FocusRequester,
    isTargetItem: Boolean,
    width: Dp,
    height: Dp,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focusEventId by remember { mutableIntStateOf(0) }
    var isFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(focusEventId, isFocused) {
        if (focusEventId != 0 && isFocused) latestOnFocused()
    }
    LaunchedEffect(isTargetItem) {
        if (isTargetItem && !isFocused) runCatching { requester.requestFocus() }
    }

    val locale = LocalConfiguration.current.locales[0]
    val label = when (widget) {
        is HomeWidget.FinishBy -> stringResource(R.string.home_widget_finish_by)
        is HomeWidget.Ready -> stringResource(R.string.home_row_ready_tonight)
        is HomeWidget.AlmostDone -> stringResource(R.string.home_widget_almost_done)
        is HomeWidget.NextUp -> stringResource(R.string.cw_next_up)
    }.uppercase(locale)
    val value = when (widget) {
        is HomeWidget.FinishBy -> rememberFinishTime(widget.remainingMs)
        is HomeWidget.Ready -> widget.count.toString()
        is HomeWidget.AlmostDone -> stringResource(R.string.home_widget_minutes, widget.remainingMinutes)
        is HomeWidget.NextUp -> widget.episode
    }
    // Ready names its first title and how many more, so it doesn't repeat the tile beside it.
    val caption = if (widget is HomeWidget.Ready && widget.count > 1) {
        stringResource(R.string.home_widget_and_more, item.title, widget.count - 1)
    } else {
        item.title
    }
    val shape = FetchTheme.shapes.backdropCard

    LightweightCard(
        onClick = onClick,
        shape = shape,
        containerColor = FetchTheme.colors.BackgroundCard,
        focusedBorder = Border(border = FetchTheme.focusRing.border(FetchCardDefaults.FocusedBorderWidth), shape = shape),
        restingBorder = FetchCardDefaults.restingBorder(shape),
        modifier = Modifier
            .width(width)
            .height(height)
            .focusRequester(requester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) focusEventId += 1
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FetchTheme.spacing.lg)
                .clearAndSetSemantics { contentDescription = "$label, $value, $caption" },
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = WidgetLabelStyle,
                    color = FetchTheme.colors.TextTertiary,
                    maxLines = 1
                )
                // Ready is the one state the theme marks in its signal colour.
                val signal = FetchTheme.style.signal
                if (widget is HomeWidget.Ready && signal != null) {
                    Spacer(Modifier.width(FetchTheme.spacing.sm))
                    Box(Modifier.size(8.dp).background(signal, CircleShape))
                }
            }
            Text(
                text = value,
                style = WidgetValueStyle,
                color = FetchTheme.colors.TextPrimary,
                maxLines = 1
            )
            Text(
                text = caption,
                style = FetchTheme.textStyles.bodyCompact,
                color = FetchTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** "10:25": now plus what's left, in the TV's 12- or 24-hour style, refreshed each minute. */
@Composable
private fun rememberFinishTime(remainingMs: Long): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val clock = LocalHomeClock.current
    var minute by remember { mutableStateOf(clock() / 60_000L) }
    LaunchedEffect(clock) {
        while (true) {
            val now = clock()
            minute = now / 60_000L
            delay(60_000L - now % 60_000L)
        }
    }
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(minute, remainingMs, is24Hour, locale) {
        val pattern = if (is24Hour) "HH:mm" else "h:mm"
        DateTimeFormatter.ofPattern(pattern, locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(minute * 60_000L + remainingMs))
    }
}

private val WidgetLabelStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = SpaceMonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 1.5.sp
)

private val WidgetValueStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = DotoFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    letterSpacing = 1.sp
)
