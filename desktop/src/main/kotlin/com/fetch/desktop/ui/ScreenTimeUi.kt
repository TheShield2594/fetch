package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fetch.desktop.AppState
import com.fetch.desktop.parental.ScreenTimeBlock

/**
 * "That's all for today": daily screen time or bedtime stopped playback. A parent can add time
 * with the main profile's PIN, as on TV.
 */
@Composable
fun ScreenTimeDialog(state: AppState, block: ScreenTimeBlock) {
    var choosing by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf<PinStep?>(null) }
    val main = state.profiles.primary
    if (pin != null) {
        PinFlow(state.profiles, pin!!, onStep = { pin = it })
        return
    }
    AlertDialog(
        onDismissRequest = { state.screenTimeBlock = null },
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(if (choosing) tr("How much more time?") else block.title, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!choosing) {
                    Text(block.body, color = FetchPalette.TextSecondary)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60).forEach { minutes ->
                            SecondaryButton(tr("+%1\$s min", minutes), onClick = { state.addScreenTime(minutes) })
                        }
                    }
                    SecondaryButton("No limit for the rest of today", onClick = { state.addScreenTime(null) })
                }
            }
        },
        confirmButton = { PrimaryButton("OK", icon = null, onClick = { state.screenTimeBlock = null }) },
        dismissButton = {
            if (!choosing) {
                SecondaryButton("More time (parent)", onClick = {
                    if (main.isLocked && main.id != state.profile.id) {
                        pin = PinStep.Unlock(main, "Enter ${main.name}’s PIN for more time.", null) { choosing = true }
                    } else {
                        choosing = true
                    }
                })
            }
        }
    )
}
