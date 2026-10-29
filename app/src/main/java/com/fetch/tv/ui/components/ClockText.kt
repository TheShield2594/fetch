package com.fetch.tv.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.ui.theme.FetchTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The wall clock [ClockText] shows; screenshot tests provide a fixed time. */
val LocalWallClock = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/**
 * The time in the TV's own style ("7:42 PM" or "19:42"), refreshed on the minute. Not focusable:
 * it is information in a corner, never a control.
 */
@Composable
fun ClockText(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val clock = LocalWallClock.current
    var minute by remember { mutableLongStateOf(clock() / 60_000L) }
    LaunchedEffect(clock) {
        while (true) {
            val now = clock()
            minute = now / 60_000L
            delay(60_000L - now % 60_000L)
        }
    }
    val is24Hour = DateFormat.is24HourFormat(context)
    val text = remember(minute, is24Hour, locale) {
        val formatter = if (is24Hour) {
            DateTimeFormatter.ofPattern("HH:mm", locale)
        } else {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        }
        formatter.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(minute * 60_000L))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = FetchTheme.colors.TextPrimary,
        maxLines = 1,
        modifier = modifier
    )
}
