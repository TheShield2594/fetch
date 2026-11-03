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
import androidx.compose.runtime.remember
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
import com.fetch.desktop.youtube.SponsorBlockCategory
import com.fetch.desktop.youtube.SponsorBlockMode
import com.fetch.desktop.youtube.YouTubeDeviceCode
import com.fetch.desktop.youtube.YouTubePlayback
import com.fetch.desktop.youtube.YouTubeSettings
import java.io.File

/**
 * Settings › YouTube, as on the TV: sign in with a code at youtube.com/activate, pick the channel,
 * then SponsorBlock, quality, autoplay, history, what the feeds leave out, the Home row, DeArrow
 * and Return YouTube Dislike.
 */
@Composable
fun YouTubeSettingsPage(state: AppState) {
    val yt = state.youtube
    val s = state.settings.youtube
    val update: ((YouTubeSettings) -> YouTubeSettings) -> Unit = { change -> state.updateSettings { it.copy(youtube = change(it.youtube)) } }
    PageHeader(
        "YouTube",
        subtitle = "Sign in to watch your YouTube home feed, subscriptions and history. YouTube appears in the sidebar while you're signed in."
    )
    val ytDlp = remember { findOnPath("yt-dlp") ?: findOnPath("youtube-dl") }
    if (ytDlp == null) {
        SettingsSection(null) {
            SettingsRow(
                "yt-dlp isn't installed",
                "Fetch plays YouTube in mpv, which needs yt-dlp to open videos. Install it (sudo apt install yt-dlp, sudo dnf install yt-dlp, or pipx install yt-dlp)."
            )
        }
    }
    if (state.limits.isRestricted) {
        SettingsSection(null) {
            SettingsRow("Not on this profile", "Profiles with a rating limit, screen time, bedtime or an autoplay cap don't show YouTube, as on the TV.")
        }
    }
    SettingsSection("Account") {
        if (yt.signedIn) {
            SettingsRow(
                "YouTube account",
                "Signed in as ${yt.accountName ?: "your Google account"}" + if (yt.signedOut) ". YouTube stopped accepting the sign-in: sign in again." else ""
            ) {
                SecondaryButton("Switch channel", onClick = yt::loadAccounts)
            }
            yt.accounts?.let { accounts ->
                val selected = yt.selectedPageId
                accounts.forEach { account ->
                    SettingsRow(
                        account.name ?: "your Google account",
                        account.handle ?: account.email,
                        onClick = { yt.selectAccount(account) }
                    ) {
                        if (account.pageId == selected) Text("Current", color = FetchPalette.Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (yt.accountsFailed) SettingsRow("Couldn't load your channels", "Check your connection and try again.")
            SettingsRow("Sign out of YouTube", "Removes YouTube from the sidebar on this profile", onClick = yt::signOut)
        } else {
            SettingsRow("Sign in to YouTube", yt.signInError ?: "Use the code shown on screen at youtube.com/activate") {
                PrimaryButton("Sign in", icon = null, onClick = yt::signIn)
            }
        }
    }
    SettingsSection("Playback") {
        SettingsToggle(
            "Skip sponsors",
            "Skips sponsor spots, self-promotion and subscribe reminders that SponsorBlock users have marked",
            s.sponsorBlockEnabled
        ) { on -> update { it.copy(sponsorBlockEnabled = on) } }
        SettingsChoice(
            "Video quality",
            "The highest quality to play, when the video has it",
            s.videoHeight,
            YouTubeSettings.VIDEO_HEIGHT_OPTIONS.map { DropdownOption(it, "Up to ${it}p") }
        ) { height -> update { it.copy(maxVideoHeight = height) } }
        SettingsChoice(
            "Play signed in",
            "Members-only, age-restricted and private videos need a sign-in to play. yt-dlp borrows the YouTube sign-in of the " +
                "browser you pick (close the browser first if it's Chrome-based); Fetch's own sign-in can't be handed to it",
            YouTubePlayback.cookiesBrowser(s),
            listOf(DropdownOption<String?>(null, "Off (signed out)")) + YouTubeSettings.COOKIE_BROWSERS.map { (name, label) -> DropdownOption<String?>(name, label) }
        ) { browser -> update { it.copy(playbackCookiesBrowser = browser) } }
        SettingsToggle("Autoplay next video", "When a video ends, count down ten seconds to YouTube's Up Next pick", s.autoplayEnabled) { on ->
            update { it.copy(autoplayEnabled = on) }
        }
        SettingsToggle(
            "Add to YouTube history",
            "Videos you watch here show up in your YouTube history, and pick up where you left off on other devices",
            s.watchHistoryEnabled
        ) { on -> update { it.copy(watchHistoryEnabled = on) } }
        SettingsToggle(
            "Show dislike counts",
            "Show like and dislike counts in the built-in player, and on mpv's window as a video starts, estimated by Return YouTube Dislike. It's told which videos you play",
            s.returnDislikeEnabled
        ) { on -> update { it.copy(returnDislikeEnabled = on) } }
    }
    if (s.sponsorBlockEnabled) {
        SettingsSection("SponsorBlock segments") {
            SettingsRow(
                "What happens with each kind of segment",
                "Skip jumps past it (in either player; the built-in player offers to go back and watch it). Ask shows a Skip button in the built-in player. " +
                    "Show plays it through, marked on the built-in player's seek bar in its colour, as the other kinds are."
            )
            SponsorBlockCategory.entries.forEach { category ->
                SettingsChoice(
                    category.label.replaceFirstChar { it.titlecase() },
                    null,
                    s.mode(category),
                    SponsorBlockMode.entries.map { DropdownOption(it, it.label) }
                ) { mode -> update { it.withMode(category, mode) } }
            }
        }
    }
    SettingsSection("Feeds") {
        SettingsToggle("Show Shorts", "Include Shorts in your feeds, search results and Up Next", s.showShorts) { on ->
            update { it.copy(showShorts = on) }
        }
        SettingsToggle("Show live streams", "Include streams that are live now in your feeds, search results and Up Next", s.showLive) { on ->
            update { it.copy(showLive = on) }
        }
        SettingsToggle("Hide watched videos", "Leave out videos you've watched to the end. History still lists them", s.hideWatched) { on ->
            update { it.copy(hideWatched = on) }
        }
        SettingsToggle("YouTube row on Home", "Show your newest subscription videos on Fetch's home screen", s.homeRowEnabled) { on ->
            update { it.copy(homeRowEnabled = on) }
        }
        SettingsToggle(
            "Community titles and thumbnails",
            "Show less clickbaity titles and thumbnails that DeArrow users have submitted, where there are any",
            s.deArrowEnabled
        ) { on -> update { it.copy(deArrowEnabled = on) } }
    }
    Text(
        "Fetch signs in the way YouTube's TV app does. YouTube can change this without notice, and an account sign-in may not be " +
            "allowed by YouTube's terms. Videos play in mpv through yt-dlp, without ads; captions in your subtitle languages are " +
            "loaded and can be turned on in the player.",
        color = FetchPalette.TextMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    yt.pendingCode?.let { code -> YouTubeSignInDialog(code, onCancel = yt::cancelSignIn) }
}

@Composable
private fun YouTubeSignInDialog(code: YouTubeDeviceCode, onCancel: () -> Unit) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text("Sign in to YouTube", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("On your phone or computer, go to ${code.verificationUrl.removePrefix("https://")}, then enter this code:", color = FetchPalette.TextSecondary)
                Text(
                    code.userCode,
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(FetchPalette.Background).padding(horizontal = 20.dp, vertical = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
                    Text("Waiting for you to approve on YouTube…", color = FetchPalette.TextMuted)
                }
            }
        },
        confirmButton = { PrimaryButton("Open ${code.verificationUrl.removePrefix("https://")}", icon = null, onClick = { uri.openUri(code.verificationUrl) }) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Copy code", onClick = { clipboard.setText(AnnotatedString(code.userCode)) })
                SecondaryButton("Cancel", onClick = onCancel)
            }
        }
    )
}

private fun findOnPath(name: String): String? = System.getenv("PATH").orEmpty()
    .split(File.pathSeparatorChar)
    .filter { it.isNotBlank() }
    .map { File(it, name) }
    .firstOrNull { it.isFile && it.canExecute() }
    ?.absolutePath
