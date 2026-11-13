package com.fetch.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fetch.tv.core.util.WatchedWhen
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What "Mark as watched on…" was opened for: the dialog's [title], whether the item has a
 * release date to offer, and what to do with the choice. A season's "Release dates" is each
 * episode's own air date, so it is always on offer there.
 */
internal class WatchedOnRequest(
    val title: String,
    val releaseDateAvailable: Boolean,
    val isSeason: Boolean,
    val onConfirm: (WatchedWhen) -> Unit
)

/**
 * "Mark as watched on…" — when it was watched: its release date, a date and time picked on a
 * calendar, or not known. Now is plain "Mark as watched", which stays one click.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchedOnDialog(request: WatchedOnRequest, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val now = LocalTime.now(zone)
    // The calendar works in UTC midnights; it can't go past today.
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = (today.year - 80)..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)

            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    )
    val timeState = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute)
    val confirm = { choice: WatchedWhen ->
        request.onConfirm(choice)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(request.title, color = Color.White) },
        text = {
            if (!picking) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(360.dp)) {
                    SecondaryButton(
                        when {
                            request.isSeason -> "Release dates (each episode as it aired)"
                            request.releaseDateAvailable -> "Release date"
                            else -> "No release date"
                        },
                        onClick = { confirm(WatchedWhen.ReleaseDate) },
                        enabled = request.releaseDateAvailable,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SecondaryButton("Pick a date and time", onClick = { picking = true }, modifier = Modifier.fillMaxWidth())
                    SecondaryButton("Unknown date", onClick = { confirm(WatchedWhen.Unknown) }, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DatePicker(state = dateState, showModeToggle = true, title = null, headline = null)
                    TimeInput(state = timeState)
                }
            }
        },
        confirmButton = {
            if (picking) {
                PrimaryButton(
                    "Log watch",
                    icon = null,
                    enabled = dateState.selectedDateMillis != null,
                    onClick = {
                        val millis = dateState.selectedDateMillis ?: return@PrimaryButton
                        val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        val at = LocalDateTime.of(day, LocalTime.of(timeState.hour, timeState.minute)).atZone(zone)
                        // A time later today than now is clamped to now by watchedAtFor.
                        confirm(WatchedWhen.At(at.toInstant().toEpochMilli()))
                    }
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(if (picking) "Back" else "Cancel", onClick = { if (picking) picking = false else onDismiss() })
            }
        }
    )
}
