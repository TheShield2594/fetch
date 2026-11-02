package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.servers.ServerKind

/** Settings › Content & Discovery › Media servers. */
@Composable
fun MediaServersPage(state: AppState) {
    val servers = state.servers
    var adding by remember { mutableStateOf<ServerKind?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircleIconButton(FetchIcons.Back, "Back", onClick = state::back, size = 40.dp)
        PageHeader("Media servers", subtitle = "Your Jellyfin, Emby and Plex files as sources, ranked first, and your libraries on Home.")
    }
    if (servers.accounts.isEmpty()) {
        Text("No servers yet. Add one below.", color = FetchPalette.TextMuted)
    }
    servers.accounts.forEach { account ->
        SettingsSection(null) {
            SettingsToggle(
                title = account.name,
                description = "${account.kind.label} · ${account.userName} · ${account.address}",
                checked = account.enabled
            ) { servers.setEnabled(account, it) }
            if (account.libraries.isNotEmpty()) {
                SettingsRow("Libraries", "Fetch looks for titles and makes Home rows only in the ones picked.")
                ChipPicker(
                    options = account.libraries.map { DropdownOption(it.id, it.name) },
                    selected = account.libraries.filter { it.selected }.map { it.id },
                    onChange = { picked -> account.libraries.forEach { lib -> if ((lib.id in picked) != lib.selected) servers.setLibrarySelected(account, lib, lib.id in picked) } }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 20.dp, bottom = 16.dp, top = 4.dp)) {
                SecondaryButton("Refresh libraries", onClick = { servers.refreshLibraries(account) })
                SecondaryButton("Remove", icon = FetchIcons.Delete, onClick = { servers.remove(account) })
            }
        }
    }
    if (servers.accounts.isNotEmpty()) {
        SettingsSection("Playback") {
            SettingsChoice(
                "Media server quality",
                "Original plays the file as it is. A limit makes the server transcode, for slow connections.",
                state.settings.player.serverMaxBitrateKbps,
                listOf<Int?>(null, 20_000, 10_000, 8_000, 4_000, 2_000).map { kbps ->
                    DropdownOption(kbps, kbps?.let { "${it / 1000} Mbps" } ?: "Original")
                }
            ) { kbps -> state.updateSettings { it.copy(player = it.player.copy(serverMaxBitrateKbps = kbps)) } }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("Add Jellyfin", icon = FetchIcons.Add, onClick = { adding = ServerKind.JELLYFIN })
        SecondaryButton("Add Emby", icon = FetchIcons.Add, onClick = { adding = ServerKind.EMBY })
        SecondaryButton("Add Plex", icon = FetchIcons.Add, onClick = servers::startPlexLink)
    }

    adding?.let { kind ->
        PasswordSignInDialog(state, kind, onDone = {
            adding = null
            servers.clearSignInError()
        })
    }
    servers.plexLink?.let { pending -> PlexLinkDialog(pending.link.code, pending.link.url, onCancel = servers::cancelPlexLink) }
}

@Composable
private fun PasswordSignInDialog(state: AppState, kind: ServerKind, onDone: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val busy = state.servers.signingIn
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("Add ${kind.label}", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(420.dp)) {
                DialogField("Server address", address, { address = it }, placeholder = "http://192.168.1.10:8096", modifier = Modifier.focusRequester(focus))
                DialogField("User name", user, { user = it })
                DialogField("Password", password, { password = it }, secret = true)
                state.servers.signInError?.let { Text(it, color = FetchPalette.Error, fontSize = 14.sp) }
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
                        Text("Signing in…", color = FetchPalette.TextMuted)
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton("Sign in", icon = null, enabled = !busy && address.isNotBlank() && user.isNotBlank(), onClick = {
                state.servers.signIn(kind, address.trim(), user.trim(), password) { ok -> if (ok) onDone() }
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDone, enabled = !busy) }
    )
}

@Composable
private fun DialogField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = FetchPalette.TextSecondary, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(FetchPalette.Accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            decorationBox = { field ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = FetchPalette.TextMuted, fontSize = 16.sp)
                    field()
                }
            },
            modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FetchPalette.Background)
                .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp)).padding(12.dp)
        )
    }
}

@Composable
private fun PlexLinkDialog(code: String, url: String, onCancel: () -> Unit) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("Add Plex", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Open $url, sign in to Plex and enter this code:", color = FetchPalette.TextSecondary)
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
                    Text("Waiting for you to enter the code… Fetch adds every server on that account.", color = FetchPalette.TextMuted)
                }
            }
        },
        confirmButton = { PrimaryButton("Open ${url.removePrefix("https://")}", icon = null, onClick = { uri.openUri(url) }) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Copy code", onClick = { clipboard.setText(AnnotatedString(code)) })
                SecondaryButton("Cancel", onClick = onCancel)
            }
        }
    )
}
