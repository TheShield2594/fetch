package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.mdblist.MdbListSettings
import com.fetch.desktop.mdblist.formatMdbListRating
import com.fetch.desktop.mdblist.valueOf
import com.fetch.tv.domain.model.MDBListRatings
import com.fetch.tv.domain.model.RottenTomatoesStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// --- Rating badges ---

/**
 * MDBList's ratings in [order], as the TV's `MDBListRatingsRow`: each a small coloured mark for
 * its source, styled like [ImdbBadge], and the value. Rotten Tomatoes' marks follow its fresh,
 * rotten and certified states; Metacritic's colour follows the score.
 */
@Composable
fun MdbListRatingsRow(ratings: MDBListRatings, order: List<String>, modifier: Modifier = Modifier, maxItems: Int = Int.MAX_VALUE) {
    val shown = order.filter { ratings.valueOf(it) != null }.take(maxItems)
    if (shown.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        shown.forEach { provider -> MdbListBadge(provider, ratings.valueOf(provider)!!, ratings) }
    }
}

@Composable
private fun MdbListBadge(provider: String, value: Double, ratings: MDBListRatings) {
    val (mark, background, ink) = badgeStyle(provider, value, ratings)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            mark,
            color = ink,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(background).padding(horizontal = 5.dp, vertical = 1.dp)
        )
        Text(formatMdbListRating(provider, value), color = FetchPalette.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The mark's text, its colour and the text colour on it. */
private fun badgeStyle(provider: String, value: Double, ratings: MDBListRatings): Triple<String, Color, Color> = when (provider) {
    "imdb" -> Triple("IMDb", FetchPalette.Imdb, Color.Black)
    "tmdb" -> Triple("TMDB", Color(0xFF01B4E4), Color(0xFF0D253F))
    "trakt" -> Triple("Trakt", Color(0xFFED1C24), Color.White)
    "letterboxd" -> Triple("Letterboxd", Color(0xFF202830), Color(0xFF40BCF4))
    "mal" -> Triple("MAL", Color(0xFF2E51A2), Color.White)
    "metacritic" -> Triple(
        "Metacritic",
        when {
            value >= 61 -> Color(0xFF00CE7A)
            value >= 40 -> Color(0xFFFFBD3F)
            else -> Color(0xFFFF6874)
        },
        Color.Black
    )
    "tomatoes" -> when (ratings.tomatoesStatus) {
        RottenTomatoesStatus.CERTIFIED_FRESH -> Triple("Certified Fresh", Color(0xFFFA320A), Color.White)
        RottenTomatoesStatus.ROTTEN -> Triple("Rotten", Color(0xFF0AC855), Color.Black)
        else -> Triple("Tomatometer", Color(0xFFFA320A), Color.White)
    }
    "audience" -> when (ratings.audienceStatus) {
        RottenTomatoesStatus.VERIFIED_HOT -> Triple("Verified Hot", Color(0xFFFFB600), Color.Black)
        RottenTomatoesStatus.STALE -> Triple("Audience", Color(0xFF6E7A8A), Color.White)
        else -> Triple("Audience", Color(0xFFFFB600), Color.Black)
    }
    else -> Triple(provider, FetchPalette.SurfaceHigh, Color.White)
}

// --- Settings › MDBList ---

/**
 * Settings › MDBList: the TV's "MDBList Ratings" page (on, API key, Home hero, which ratings and
 * in what order) and its MDBList account, whose Watchlist and lists join the Library.
 */
@Composable
fun MdbListPage(state: AppState) {
    val m = state.settings.mdblist
    val update: ((MdbListSettings) -> MdbListSettings) -> Unit = { change -> state.updateSettings { it.copy(mdblist = change(it.mdblist)) } }
    val account = state.mdbListAccount
    PageHeader("MDBList", subtitle = "Ratings from IMDb, Rotten Tomatoes, Metacritic, Letterboxd and more on title pages, and your MDBList lists in the Library.")

    SettingsSection("Ratings") {
        SettingsToggle("Enable MDBList ratings", "Show ratings from other sites on title pages.", m.enabled) { v -> update { it.copy(enabled = v) } }
        ApiKeyRow(
            value = m.apiKey,
            description = if (account.connected && m.apiKey.isBlank()) "Using your connected MDBList account. A key here is used instead."
            else "Your key from mdblist.com › Preferences. Leave empty to use a connected MDBList account.",
            verify = state.mdbListRatings::verify,
            onSave = { key -> update { it.copy(apiKey = key) } }
        )
        SettingsToggle("Use ratings on Home's hero", "Show the first three ratings on Home's hero too.", m.showOnHero) { v -> update { it.copy(showOnHero = v) } }
        if (m.enabled && m.apiKey.isBlank() && !account.connected) {
            SettingsRow("No key", "Add an API key or connect your MDBList account below to see ratings.")
        }
    }

    SettingsSection("Which ratings, in order") {
        val order = m.fullOrder
        order.forEachIndexed { i, provider ->
            SettingsRow(MdbListSettings.label(provider)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(FetchIcons.ArrowUp, "Move up", onClick = { update { it.moved(provider, -1) } }, size = 34.dp, enabled = i > 0)
                    CircleIconButton(FetchIcons.ArrowDown, "Move down", onClick = { update { it.moved(provider, 1) } }, size = 34.dp, enabled = i < order.lastIndex)
                    Switch(
                        checked = m.shows(provider),
                        onCheckedChange = { v -> update { it.withShown(provider, v) } },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FetchPalette.OnAccent,
                            checkedTrackColor = FetchPalette.Accent,
                            uncheckedThumbColor = FetchPalette.TextSecondary,
                            uncheckedTrackColor = FetchPalette.SurfaceHigh,
                            uncheckedBorderColor = FetchPalette.Outline
                        )
                    )
                }
            }
        }
    }

    SettingsSection("Account") {
        val a = account.account
        if (a != null) {
            SettingsRow(
                "MDBList",
                "Connected" + (a.userName?.let { " as $it" } ?: "") + (if (a.usesApiKey) " with an API key" else "") +
                    ". Its Watchlist and lists are in the Library."
            ) { SecondaryButton("Disconnect", onClick = account::signOut) }
            SettingsRow(
                "Sync lists now",
                when {
                    account.refreshing -> "Syncing…"
                    account.lastSyncedAt != null -> "Last synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(account.lastSyncedAt!!))
                    else -> "Fetch syncs when it starts."
                },
                onClick = { state.lists.refresh() }
            ) {
                if (account.refreshing) CircularProgressIndicator(Modifier.size(22.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
            }
        } else {
            SettingsRow(
                "MDBList",
                if (account.deviceSignInAvailable) "Not connected. Connect to see your Watchlist and lists in the Library and add titles to them."
                else "Not connected. This build has no MDBList client ID (builds from source read MDBLIST_CLIENT_ID), so connect with an API key."
            ) {
                if (account.deviceSignInAvailable) PrimaryButton("Connect", icon = null, onClick = { account.signIn(onSignedIn = { state.lists.refresh() }) })
            }
            var key by remember { mutableStateOf(m.apiKey) }
            SettingsRow("Connect with an API key", "Your key from mdblist.com › Preferences.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeyField(key, onChange = { key = it })
                    SecondaryButton(
                        if (account.connecting) "Checking…" else "Connect",
                        enabled = key.isNotBlank() && !account.connecting,
                        onClick = { account.connectWithKey(key, onSignedIn = { state.lists.refresh() }) }
                    )
                }
            }
        }
    }

    val options = account.listOptions()
    if (account.connected && options.isNotEmpty()) {
        SettingsSection("Library lists") {
            SettingsRow("Which lists to show", "In the Library and in Manage lists. The Watchlist is always shown.")
            options.forEach { (list, visible) ->
                SettingsToggle(
                    list.name,
                    when (list.kind) {
                        com.fetch.desktop.mdblist.MdbListListKind.STATIC -> if (list.private) "Private list" else "Public list"
                        com.fetch.desktop.mdblist.MdbListListKind.DYNAMIC -> "Dynamic list (filled by its filters on MDBList)"
                        com.fetch.desktop.mdblist.MdbListListKind.EXTERNAL -> "External list" + (list.description?.let { " from $it" } ?: "")
                        com.fetch.desktop.mdblist.MdbListListKind.LIKED -> "A list you liked"
                        else -> null
                    },
                    visible
                ) { v -> account.setListVisible(list.key, v) }
            }
        }
    }

    account.pending?.let { code -> MdbListSignInDialog(code.userCode, code.verificationUrl, code.verificationUrlComplete, onCancel = account::cancelSignIn) }
}

/** The ratings API key, checked with MDBList before it's saved, as on TV. */
@Composable
internal fun ApiKeyRow(value: String, description: String, verify: suspend (String) -> Boolean, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SettingsRow("API key", error ?: description) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyField(text, onChange = { text = it; error = null })
            if (text.trim() != value) {
                SecondaryButton(if (checking) "Checking…" else "Save", enabled = !checking, onClick = {
                    val key = text.trim()
                    if (key.isEmpty()) {
                        onSave("")
                        return@SecondaryButton
                    }
                    scope.launch {
                        checking = true
                        val ok = verify(key)
                        checking = false
                        if (ok) onSave(key) else error = "Couldn't verify this key. Check it matches the one in your MDBList account and that you're online."
                    }
                })
            }
        }
    }
}

@Composable
private fun KeyField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(200)) },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
        cursorBrush = SolidColor(FetchPalette.Accent),
        visualTransformation = PasswordVisualTransformation(),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(tr("Not set"), color = FetchPalette.TextMuted, fontSize = 15.sp)
                field()
            }
        },
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FetchPalette.Background)
            .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    )
}

@Composable
private fun MdbListSignInDialog(code: String, url: String, completeUrl: String, onCancel: () -> Unit) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(tr("Connect MDBList"), color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(tr("Open %1$s and enter this code, then approve the connection:", url), color = FetchPalette.TextSecondary)
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
                    Text(tr("Waiting for you to approve…"), color = FetchPalette.TextMuted)
                }
            }
        },
        confirmButton = { PrimaryButton("Open mdblist.com", icon = null, onClick = { uri.openUri(completeUrl) }) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Copy code", onClick = { clipboard.setText(AnnotatedString(code)) })
                SecondaryButton("Cancel", onClick = onCancel)
            }
        }
    )
}
