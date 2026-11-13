package com.fetch.tv.ui.screens.detail

import com.fetch.tv.core.util.WatchedWhen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** How far back the date picker goes. */
private const val PICKER_YEARS_BACK = 80L

/**
 * "Mark watched on…" — when something was watched, chosen with a remote.
 *
 * Three choices, then a date for the last: Release date, Unknown date, Pick a date. No time of day
 * and no "now": now is plain "Mark as watched", one press, and stepping hours and minutes with a
 * D-pad is more than a time is worth here.
 *
 * The date is three rows — day, month, year — each changed with Left/Right while Up/Down moves
 * between them, the way a TV settings value is changed. It can't go past today.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun MarkWatchedOnDialog(
    title: String,
    /** Whether "Release date" has a date to use. A season always offers it (each episode's own). */
    releaseDateAvailable: Boolean,
    isSeason: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (WatchedWhen) -> Unit,
    today: LocalDate = LocalDate.now()
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(picking) { runCatching { firstFocus.requestFocus() } }

    FetchDialog(
        onDismiss = { if (picking) picking = false else onDismiss() },
        title = title,
        subtitle = stringResource(if (picking) R.string.watched_on_pick_subtitle else R.string.watched_on_subtitle)
    ) {
        if (!picking) {
            DetailButton(
                onClick = { onConfirm(WatchedWhen.ReleaseDate) },
                enabled = releaseDateAvailable,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
            ) {
                Text(
                    stringResource(
                        when {
                            isSeason -> R.string.watched_on_release_dates
                            releaseDateAvailable -> R.string.watched_on_release_date
                            else -> R.string.watched_on_no_release_date
                        }
                    )
                )
            }
            DetailButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.watched_on_pick_date))
            }
            DetailButton(onClick = { onConfirm(WatchedWhen.Unknown) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.watched_on_unknown_date))
            }
        } else {
            var date by rememberSaveable { mutableStateOf(today.toEpochDay()) }
            val picked = LocalDate.ofEpochDay(date)
            val earliest = today.minusYears(PICKER_YEARS_BACK)
            fun set(next: LocalDate) {
                date = next.coerceIn(earliest, today).toEpochDay()
            }
            val locale = Locale.getDefault()

            DateStepperRow(
                label = stringResource(R.string.watched_on_day),
                value = picked.dayOfMonth.toString(),
                onStep = { step -> set(picked.plusDays(step.toLong())) },
                modifier = Modifier.focusRequester(firstFocus)
            )
            DateStepperRow(
                label = stringResource(R.string.watched_on_month),
                value = picked.month.getDisplayName(TextStyle.FULL, locale),
                onStep = { step -> set(picked.plusMonths(step.toLong())) }
            )
            DateStepperRow(
                label = stringResource(R.string.watched_on_year),
                value = picked.year.toString(),
                onStep = { step -> set(picked.plusYears(step.toLong())) }
            )
            DetailButton(
                onClick = { onConfirm(WatchedWhen.On(picked)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.watched_on_confirm))
            }
        }
    }
}

/**
 * One field of the date: "Day   ‹ 14 ›". Left and Right change it (mirrored in a right-to-left
 * layout, so the arrow pointing at "later" still means later); OK does the same as Right, so a
 * remote with only a select button can still get there.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DateStepperRow(
    label: String,
    value: String,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    DetailButton(
        onClick = { onStep(1) },
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val step = when (event.key) {
                    Key.DirectionLeft -> if (rtl) 1 else -1
                    Key.DirectionRight -> if (rtl) -1 else 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) onStep(step)
                true
            }
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = FetchTheme.colors.TextSecondary)
            Text("‹  $value  ›")
        }
    }
}
