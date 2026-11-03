package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.TrackingService
import java.text.DateFormat
import java.util.Date

/** Settings › Tracking: connect Trakt and Simkl with a code, as on the TV app. */
@Composable
fun TrackingPage(state: AppState, header: Boolean = true) {
    val tracking = state.tracking
    if (header) PageHeader(
        "Tracking",
        subtitle = "Sync Continue Watching, what you've watched and your watchlist, and scrobble what you play in mpv."
    )
    SettingsSection(null) {
        TrackingService.entries.forEach { service ->
            val account = tracking.account(service)
            val available = tracking.available(service)
            SettingsRow(
                title = service.label,
                description = when {
                    account != null -> "Connected" + (account.userName?.let { " as $it" } ?: "")
                    !available -> "This build has no ${service.label} client ID. Builds from source read " +
                        (if (service == TrackingService.TRAKT) "TRAKT_CLIENT_ID and TRAKT_CLIENT_SECRET" else "SIMKL_CLIENT_ID") +
                        " from the environment or local.properties."
                    else -> "Not connected"
                }
            ) {
                when {
                    account != null -> SecondaryButton("Disconnect", onClick = { tracking.signOut(service) })
                    available -> PrimaryButton("Connect", icon = null, onClick = { tracking.signIn(service, onSignedIn = state::syncTracking) })
                }
            }
        }
    }
    if (tracking.connected.isNotEmpty()) {
        SettingsSection("Sync") {
            SettingsRow(
                "Sync now",
                when {
                    tracking.syncing -> "Syncing…"
                    tracking.lastSyncedAt != null -> "Last synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tracking.lastSyncedAt!!))
                    else -> "Fetch syncs when it starts."
                },
                onClick = state::syncTracking
            ) {
                if (tracking.syncing) CircularProgressIndicator(Modifier.size(22.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
            }
        }
    }
    TrackingOptionsSection(state)
    tracking.pending?.let { pending -> SignInDialog(pending.service, pending.code.userCode, pending.code.verificationUrl, onCancel = tracking::cancelSignIn) }
}

@Composable
private fun SignInDialog(service: TrackingService, code: String, url: String, onCancel: () -> Unit) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("Connect ${service.label}", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Open $url and enter this code:", color = FetchPalette.TextSecondary)
                Text(
                    code,
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 6.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(FetchPalette.Background).padding(horizontal = 20.dp, vertical = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
                    Text("Waiting for you to enter the code…", color = FetchPalette.TextMuted)
                }
            }
        },
        confirmButton = {
            PrimaryButton("Open ${url.removePrefix("https://")}", icon = null, onClick = { uri.openUri(url) })
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Copy code", onClick = { clipboard.setText(AnnotatedString(code)) })
                SecondaryButton("Cancel", onClick = onCancel)
            }
        }
    )
}
