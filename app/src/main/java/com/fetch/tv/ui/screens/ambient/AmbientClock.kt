package com.fetch.tv.ui.screens.ambient

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.ui.components.DotProgressRow
import com.fetch.tv.ui.components.formatContinueWatchingProgressLabel
import com.fetch.tv.ui.theme.DotoFamily
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.SpaceMonoFamily
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How far the face drifts from centre, so a clock left up for hours doesn't burn into an OLED. */
private val DRIFT = 16.dp

/** Drift positions, one per minute, as fractions of [DRIFT]: a slow loop around the centre. */
private val DRIFT_STEPS = listOf(0f to 0f, 1f to -0.5f, 0.5f to 1f, -1f to 0.5f, -0.5f to -1f)

/**
 * Ambient Mode's clock face: the time in dot-matrix figures, the date in monospace, and the
 * member's most recent unfinished title with its progress in dots, on the theme's background (true
 * black in Signal) so an OLED panel is mostly off. The colon blinks once a second unless motion is
 * reduced.
 */
@Composable
fun AmbientClock(
    resume: WatchProgress?,
    modifier: Modifier = Modifier,
    /** The wall clock; screenshot tests pass a fixed time. */
    now: () -> Long = System::currentTimeMillis
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var nowMs by remember { mutableLongStateOf(now()) }
    val fullMotion = FetchTheme.fullMotion
    LaunchedEffect(now) {
        while (true) {
            nowMs = now()
            // Wake on the next second, not a second from now, so the colon keeps time.
            delay(1_000L - nowMs % 1_000L)
        }
    }

    val is24Hour = DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochMilli(nowMs)
    val time = remember(nowMs / 60_000L, is24Hour) {
        DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm", locale).withZone(zone).format(instant)
    }
    val amPm = if (is24Hour) {
        null
    } else {
        remember(nowMs / 60_000L) { DateTimeFormatter.ofPattern("a", locale).withZone(zone).format(instant) }
    }
    val date = remember(nowMs / 60_000L, locale) {
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
        DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(instant).uppercase(locale)
    }
    val colonLit = !fullMotion || (nowMs / 1_000L) % 2L == 0L
    val signal = FetchTheme.style.signal ?: FetchTheme.colors.TextPrimary
    val drift = DRIFT_STEPS[((nowMs / 60_000L) % DRIFT_STEPS.size).toInt()]

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FetchTheme.colors.Background)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = DRIFT * drift.first, y = DRIFT * drift.second),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = buildAnnotatedString {
                        time.forEach { char ->
                            if (char == ':') {
                                // The one red thing on screen, like a device's status light.
                                withStyle(SpanStyle(color = if (colonLit) signal else signal.copy(alpha = 0.2f))) {
                                    append(char)
                                }
                            } else {
                                append(char)
                            }
                        }
                    },
                    style = ClockStyle,
                    color = FetchTheme.colors.TextPrimary
                )
                if (amPm != null) {
                    Text(
                        text = amPm.uppercase(locale),
                        style = MonoStyle.copy(fontSize = 24.sp),
                        color = FetchTheme.colors.TextTertiary,
                        modifier = Modifier.padding(start = 16.dp, bottom = 36.dp)
                    )
                }
            }
            Text(
                text = date,
                style = MonoStyle,
                color = FetchTheme.colors.TextSecondary
            )
            if (resume != null) {
                ResumeWidget(resume = resume, modifier = Modifier.padding(top = 36.dp))
            }
        }
    }
}

/** Nothing-style widget: hairline frame, label, title, time left and the progress in dots. */
@Composable
private fun ResumeWidget(resume: WatchProgress, modifier: Modifier = Modifier) {
    val strResume = stringResource(R.string.cw_resume)
    val strPercentWatched = stringResource(R.string.cw_percent_watched)
    val strHoursMinLeft = stringResource(R.string.cw_hours_min_left)
    val strMinLeft = stringResource(R.string.cw_min_left)
    val remaining = remember(resume.position, resume.duration, resume.progressPercent) {
        formatContinueWatchingProgressLabel(resume, strResume, strPercentWatched, strHoursMinLeft, strMinLeft)
    }
    val episode = if (resume.season != null && resume.episode != null) "S${resume.season} E${resume.episode}" else null
    val shape = RoundedCornerShape(FetchTheme.radii.xl)
    Column(
        modifier = modifier
            .width(360.dp)
            .border(FetchTheme.strokes.hairline, FetchTheme.colors.TextPrimary.copy(alpha = 0.2f), shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = strResume.uppercase(LocalConfiguration.current.locales[0]),
            style = MonoStyle.copy(fontSize = 14.sp),
            color = FetchTheme.colors.TextTertiary
        )
        Text(
            text = resume.name,
            style = FetchTheme.textStyles.cardTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
            color = FetchTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOfNotNull(episode, remaining).joinToString("  ·  "),
            style = MonoStyle.copy(fontSize = 14.sp, letterSpacing = 0.5.sp),
            color = FetchTheme.colors.TextSecondary
        )
        DotProgressRow(
            progress = resume.progressPercentage,
            active = false,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private val ClockStyle = TextStyle(
    fontFamily = DotoFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 152.sp,
    lineHeight = 152.sp,
    letterSpacing = 4.sp
)

private val MonoStyle = TextStyle(
    fontFamily = SpaceMonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 20.sp,
    lineHeight = 28.sp,
    letterSpacing = 3.sp
)
