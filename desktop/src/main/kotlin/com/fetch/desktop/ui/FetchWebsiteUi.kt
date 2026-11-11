package com.fetch.desktop.ui

import androidx.compose.foundation.background
import com.fetch.desktop.i18n.tr
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.parental.PinGate
import com.fetch.desktop.website.FetchWebsiteState
import com.fetch.desktop.website.WebsitePairing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false, width: Int = 420) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(FetchPalette.Accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(tr(placeholder), color = FetchPalette.TextMuted, fontSize = 16.sp)
                field()
            }
        },
        modifier = Modifier.width(width.dp).clip(RoundedCornerShape(8.dp)).background(FetchPalette.Background)
            .border(1.dp, FetchPalette.Outline, RoundedCornerShape(8.dp)).padding(12.dp)
    )
}

/** A code in large monospaced letters on a tinted panel, as the TV shows pairing and sync codes. */
@Composable
private fun BigCode(code: String) {
    Text(
        code,
        color = FetchPalette.Accent,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 4.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(FetchPalette.Accent.copy(alpha = 0.12f)).padding(horizontal = 22.dp, vertical = 12.dp)
    )
}

/**
 * Settings › Account › Fetch website, as on TV: pair this computer with a Fetch website by a
 * code (and a QR code of the website's confirm page) confirmed there; then lists made on the
 * website can be collection sources. Sign out keeps everything here as it is.
 */
@Composable
fun FetchWebsiteSection(state: AppState) {
    val website = FetchWebsiteState.shared
    LaunchedEffect(Unit) { website.checkSession() }
    val session = website.session
    SettingsSection("Fetch website") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(20.dp)) {
            if (website.file.signedOutByWebsite && session == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("This computer was signed out from the Fetch website. Sign in again to show its lists here."),
                        color = FetchPalette.Error,
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton("Dismiss", onClick = website::dismissSignedOutNotice)
                }
            }
            if (session != null) {
                Text(tr("Signed in as %1\$s", session.deviceName) + " · " + session.serverUrl, color = Color.White)
                Text(
                    "Profiles, addons, settings, Home rows and Home folders sync with the website, as on TV. " +
                        "Lists made on the website can be added to folder rows (Content & Discovery › Home folders › Website list).",
                    color = FetchPalette.TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("Sync now", enabled = !state.accountSyncing, onClick = state::syncAccount)
                    SecondaryButton("Sign out", onClick = {
                        website.cancelPairing()
                        website.signOut()
                    })
                    if (state.accountSyncing) CircularProgressIndicator(Modifier.size(20.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
                }
                return@Column
            }
            // Typing the address was chosen over a code from the pairing hub.
            var manual by remember { mutableStateOf(false) }
            // Paired: the website's profiles, addons and settings come down now.
            val onPaired = { java.awt.EventQueue.invokeLater(state::syncAccount) }
            LaunchedEffect(manual) {
                if (!manual && website.pairing == WebsitePairing.EnterAddress) website.startHubPairing(onPaired)
            }
            when (val pairing = website.pairing) {
                WebsitePairing.EnterAddress, is WebsitePairing.Failed -> {
                    if (!manual && pairing !is WebsitePairing.Failed) {
                        Waiting(tr("Getting a code…"))
                        return@Column
                    }
                    var address by remember { mutableStateOf(website.file.lastServerUrl.orEmpty()) }
                    if (pairing is WebsitePairing.Failed && pairing.hubUnavailable) {
                        Text(tr("Couldn’t reach watchfetch.tv to get a code. Enter your Fetch website’s address instead."), color = FetchPalette.TextSecondary)
                    }
                    Text(
                        tr("Enter the address you open your Fetch website at, such as fetch.example.com or 192.168.1.25:7002."),
                        color = FetchPalette.TextSecondary
                    )
                    Field(address, { address = it }, "fetch.example.com")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Continue", icon = null, enabled = address.isNotBlank(), onClick = {
                            manual = true
                            website.startPairing(address, onPaired = onPaired)
                        })
                        SecondaryButton("Use a code instead", onClick = {
                            manual = false
                            website.startHubPairing(onPaired)
                        })
                    }
                    if (pairing is WebsitePairing.Failed && !pairing.hubUnavailable) {
                        Text(tr("Couldn’t reach the website: %1\$s", pairing.message ?: tr("check the address")), color = FetchPalette.Error)
                    }
                }
                WebsitePairing.Starting -> Waiting(tr(if (manual) "Finding your Fetch website…" else "Getting a code…"))
                is WebsitePairing.Connecting -> Waiting(tr("Code confirmed. Signing in to %1\$s…", pairing.serverUrl))
                is WebsitePairing.ShowingCode -> PairingCode(
                    pairing,
                    onOpen = state::openInBrowser,
                    onCancel = website::cancelPairing,
                    onTypeAddress = {
                        manual = true
                        website.cancelPairing()
                    }
                )
                WebsitePairing.Expired -> {
                    Text(tr("The code expired before it was confirmed."), color = FetchPalette.TextSecondary)
                    SecondaryButton("Get a new code", onClick = {
                        if (manual) website.resetPairing() else website.startHubPairing(onPaired)
                    })
                }
                is WebsitePairing.Paired -> Text(tr("Signed in as %1\$s.", pairing.deviceName), color = Color.White)
            }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
        Text(text, color = FetchPalette.TextSecondary)
    }
}

@Composable
private fun PairingCode(
    pairing: WebsitePairing.ShowingCode,
    onOpen: (String) -> Unit,
    onCancel: () -> Unit,
    onTypeAddress: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pairing) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = ((pairing.expiresAtMs - now) / 1000).coerceAtLeast(0)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        pairing.confirmUrl?.let { url -> QrImage(url, 150.dp, description = tr("QR code that opens the Fetch website to confirm this computer")) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                tr(
                    when {
                        pairing.viaHub -> "Open your Fetch website and enter this code under Settings → TVs & Devices, or scan the QR code with your phone. No address needed."
                        pairing.confirmUrl != null -> "Scan the code with your phone, or open the website and enter the code under Settings → TVs & Devices."
                        else -> "Open the website and enter this code under Settings → TVs & Devices."
                    }
                ),
                color = FetchPalette.TextSecondary
            )
            BigCode(pairing.code)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
                Text(tr("Waiting for the code to be confirmed… Expires in %1\$s", "%d:%02d".format(left / 60, left % 60)), color = FetchPalette.TextMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // On a computer the website is one click away, usually already signed in. For a
                // hub code the link opens watchfetch.tv, which goes on to the website.
                pairing.confirmUrl?.let { url ->
                    PrimaryButton("Confirm in your browser", icon = null, onClick = { onOpen(url) })
                }
                // A hub code has nothing to cancel back to: stopping it is choosing the address.
                if (pairing.viaHub) SecondaryButton("Enter the address instead", onClick = onTypeAddress)
                else SecondaryButton("Cancel", onClick = onCancel)
            }
        }
    }
}

/**
 * The PIN of a website profile that is locked there, asked where one of its lists couldn't load.
 * The website checks it and hands back a profile token, kept for a few hours as on TV; then
 * [onUnlocked] loads the list again.
 */
@Composable
fun WebsiteProfileUnlock(slot: Int, onUnlocked: () -> Unit) {
    val website = FetchWebsiteState.shared
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun unlock() {
        busy = true
        error = null
        scope.launch {
            try {
                val result = website.verifyPin(slot, pin)
                error = when (result) {
                    PinGate.Result.Ok -> null
                    is PinGate.Result.Wrong -> result.message
                    is PinGate.Result.Locked -> tr("Too many wrong PINs. Try again in %1\$s seconds.", result.seconds)
                    null -> tr("Couldn't reach the Fetch website.")
                }
                if (result == PinGate.Result.Ok) onUnlocked()
            } finally {
                busy = false
                pin = ""
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp)) {
        Field(pin, { pin = it.take(8) }, "PIN", secret = true, width = 220)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Unlock", icon = null, enabled = pin.length >= 4 && !busy, onClick = { unlock() })
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
        }
        error?.let { Text(it, color = FetchPalette.Error) }
    }
}
