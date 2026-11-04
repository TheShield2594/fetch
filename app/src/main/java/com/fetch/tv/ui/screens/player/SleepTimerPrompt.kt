package com.fetch.tv.ui.screens.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The sleep timer ran out: playback is paused, and the player closes when [secondsLeft] reaches
 * zero. "Keep watching" is focused first so one press carries on; Back stops.
 */
@Composable
internal fun SleepTimerPrompt(
    secondsLeft: Int,
    onKeepWatching: () -> Unit,
    onExtend: () -> Unit,
    onStop: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    val primary = ButtonDefaults.colors(
        containerColor = FetchTheme.colors.Primary,
        contentColor = FetchTheme.colors.OnPrimary
    )
    val secondary = ButtonDefaults.colors(
        containerColor = FetchTheme.colors.BackgroundCard,
        contentColor = FetchTheme.colors.TextPrimary
    )
    FetchDialog(
        onDismiss = onStop,
        title = stringResource(R.string.still_watching_title),
        subtitle = pluralStringResource(R.plurals.player_sleep_timer_prompt, secondsLeft, secondsLeft),
        width = 480.dp,
        suppressFirstKeyUp = false
    ) {
        Button(
            onClick = onKeepWatching,
            modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            colors = primary
        ) {
            Text(stringResource(R.string.player_sleep_timer_keep_watching))
        }
        Button(onClick = onExtend, modifier = Modifier.fillMaxWidth(), colors = secondary) {
            Text(pluralStringResource(R.plurals.player_sleep_timer_extend, SLEEP_TIMER_EXTEND_MINUTES, SLEEP_TIMER_EXTEND_MINUTES))
        }
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth(), colors = secondary) {
            Text(stringResource(R.string.player_sleep_timer_stop))
        }
    }
}

/** The sleep timer button's label: what it is set to now, or just its name while it is off. */
@Composable
internal fun sleepTimerButtonLabel(label: SleepTimerLabel?, isMovie: Boolean): String = when (label) {
    null -> stringResource(R.string.player_sleep_timer)
    is SleepTimerLabel.MinutesLeft -> pluralStringResource(R.plurals.player_sleep_timer_minutes_left, label.minutes, label.minutes)
    SleepTimerLabel.EndOfTitle -> stringResource(
        if (isMovie) R.string.player_sleep_timer_end_of_movie else R.string.player_sleep_timer_end_of_episode
    )
}
