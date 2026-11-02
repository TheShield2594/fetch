package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import java.text.DateFormat
import java.util.Date

/**
 * Settings › Account: the optional sync server. Fetch has no hosted backend; this is your own
 * self-hosted Fetch server, the same one the TV app signs in to.
 */
@Composable
fun AccountPage(state: AppState) {
    val account = state.account ?: return
    PageHeader(
        "Account",
        subtitle = "Sync Library, Continue Watching and watched history with your own Fetch sync server, the same one the TV app uses."
    )
    val server = account.state.server
    if (account.signedIn && server != null) {
        SettingsSection(null) {
            SettingsRow("Signed in", listOfNotNull(account.state.session?.email, server.url).joinToString(" · "))
            SettingsRow(
                "Sync now",
                when {
                    state.accountSyncing -> "Syncing ${state.profile.name}…"
                    account.lastSyncedAt != null -> "Last synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(account.lastSyncedAt!!))
                    else -> "Fetch syncs when it starts and after you watch something."
                },
                onClick = state::syncAccount
            ) {
                if (state.accountSyncing) CircularProgressIndicator(Modifier.size(22.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
            }
            SettingsRow("Sign out", "This computer stops syncing; nothing is deleted.", onClick = account::signOut)
        }
        account.error?.let { Text(it, color = FetchPalette.Error) }
        return
    }

    var address by remember { mutableStateOf(server?.url.orEmpty()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val found = account.discovered
    SettingsSection("Server") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(20.dp)) {
            AccountField("Server address", address, { address = it }, placeholder = "https://fetch.example.com")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("Find server", onClick = { account.findServer(address) }, enabled = address.isNotBlank() && !account.busy)
                if (account.busy) CircularProgressIndicator(Modifier.size(20.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
                if (found != null) Text("Found ${found.config.url}", color = FetchPalette.TextSecondary)
            }
        }
    }
    if (found != null) {
        SettingsSection("Sign in") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(20.dp)) {
                if (found.tvLogin) {
                    Text("Approve a code from your phone or browser, where you're already signed in:", color = FetchPalette.TextSecondary)
                    SecondaryButton("Sign in with a code", onClick = account::startDeviceLogin)
                }
                if (found.emailPasswordAuth) {
                    AccountField("Email", email, { email = it })
                    AccountField("Password", password, { password = it }, secret = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Sign in", icon = null, enabled = email.isNotBlank() && password.isNotBlank() && !account.busy, onClick = {
                            account.signIn(email, password)
                        })
                        SecondaryButton("Create account", enabled = email.isNotBlank() && password.length >= 6 && !account.busy, onClick = {
                            account.signUp(email, password)
                        })
                    }
                }
            }
        }
    }
    account.error?.let { Text(it, color = FetchPalette.Error) }
    account.deviceLogin?.let { login -> DeviceCodeDialog(login.userCode, login.verificationUrlComplete, onCancel = account::cancelDeviceLogin) }
}

@Composable
private fun AccountField(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", secret: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = FetchPalette.TextSecondary, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(FetchPalette.Accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { field ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = FetchPalette.TextMuted, fontSize = 16.sp)
                    field()
                }
            },
            modifier = Modifier.width(420.dp).clip(RoundedCornerShape(8.dp)).background(FetchPalette.Background)
                .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp)).padding(12.dp)
        )
    }
}

@Composable
private fun DeviceCodeDialog(code: String, url: String, onCancel: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("Sign in with a code", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Open this link where you're signed in and approve this code:", color = FetchPalette.TextSecondary)
                Text(url, color = FetchPalette.TextPrimary, fontSize = 14.sp)
                Text(
                    code,
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 6.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(FetchPalette.Background).padding(horizontal = 20.dp, vertical = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
                    Text("Waiting for approval…", color = FetchPalette.TextMuted)
                }
            }
        },
        confirmButton = { PrimaryButton("Open link", icon = null, onClick = { uri.openUri(url) }) },
        dismissButton = { SecondaryButton("Cancel", onClick = onCancel) }
    )
}
