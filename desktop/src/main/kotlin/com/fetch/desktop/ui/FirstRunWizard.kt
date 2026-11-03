package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.TrackingService
import com.fetch.desktop.data.AddonManagementAccess
import com.fetch.desktop.data.givesStreams
import com.fetch.desktop.data.needsEssentialAddonSetup
import com.fetch.tv.domain.model.ExperienceMode

/** The wizard's steps, in order (the TV's FirstRunStep). */
enum class FirstRunStep(val title: String) { SERVER("Server"), SOURCES("Sources"), METADATA("Metadata"), EXPERIENCE("Experience") }

/** Opening the wizard again from System › Advanced. */
object FirstRunState {
    var reopens by mutableIntStateOf(0)
        private set

    fun reopen() {
        reopens++
    }

    /**
     * The steps a profile sees: all four, or only Experience on a profile with limits, which
     * shouldn't change settings (the TV shows a PIN-gated profile only Experience).
     */
    fun steps(restricted: Boolean): List<FirstRunStep> =
        if (restricted) listOf(FirstRunStep.EXPERIENCE) else FirstRunStep.entries

    /** Whether a step counts as set up, so its button reads Continue instead of Skip for now. */
    fun isDone(step: FirstRunStep, status: FirstRunStatus): Boolean = when (step) {
        FirstRunStep.SERVER -> status.serverSignedIn
        FirstRunStep.SOURCES -> status.streamAddons > 0 || status.mediaServers.isNotEmpty()
        FirstRunStep.METADATA -> status.tmdbEnabled || status.mdbListReady || status.trackingConnected.isNotEmpty()
        FirstRunStep.EXPERIENCE -> true
    }
}

/** What is set up so far, shown on the steps (the TV's FirstRunStatus). */
data class FirstRunStatus(
    val serverSignedIn: Boolean = false,
    val streamAddons: Int = 0,
    val mediaServers: List<String> = emptyList(),
    val tmdbEnabled: Boolean = false,
    val mdbListReady: Boolean = false,
    val trackingConnected: List<String> = emptyList()
)

private fun AppState.firstRunStatus() = FirstRunStatus(
    serverSignedIn = account?.signedIn == true,
    streamAddons = addons.count { it.givesStreams() },
    mediaServers = servers.accounts.map { it.name },
    tmdbEnabled = settings.tmdb.enabled,
    mdbListReady = settings.mdblist.apiKey.isNotBlank() || mdbListAccount.connected,
    trackingConnected = TrackingService.entries.filter { tracking.account(it) != null }.map { it.label }
)

/**
 * The first-run wizard on a fresh install, and Essential's addon setup after it, over the whole
 * window. A settings file from before Essential mode existed reads as Advanced, so existing users
 * never see the wizard; System › Advanced › First-run setup opens it again.
 */
@Composable
fun FirstRunHost(state: AppState) {
    var wizard by remember { mutableStateOf(state.settings.experience.mode == null) }
    LaunchedEffect(FirstRunState.reopens) { if (FirstRunState.reopens > 0) wizard = true }
    when {
        wizard -> FirstRunWizard(state, onFinished = { mode ->
            state.updateSettings { it.copy(experience = it.experience.copy(mode = mode)) }
            wizard = false
        })
        needsEssentialAddonSetup(
            state.settings.experience,
            state.addons.toList(),
            state.addonsLoading,
            AddonManagementAccess.isReadOnly(state.profile)
        ) -> EssentialAddonSetup(state, onSkip = {
            state.updateSettings { it.copy(experience = it.experience.copy(addonSetupSkipped = true)) }
        })
    }
}

/** A full-window layer that keeps clicks from reaching the app underneath. */
@Composable
private fun SetupLayer(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(FetchPalette.Background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) { content() }
}

/**
 * Server → Sources → Metadata → Experience, each with Skip for now, as the TV's
 * FirstRunWizardScreen. Desktop has a keyboard, so each step has its fields right here instead of
 * the TV's phone code. Skipping everything leaves the app as a fresh install: local only, with the
 * default addons, in Essential mode.
 */
@Composable
fun FirstRunWizard(state: AppState, onFinished: (ExperienceMode) -> Unit, initialStep: Int = 0) {
    val steps = remember { FirstRunState.steps(state.limits.isRestricted) }
    var index by remember { mutableIntStateOf(initialStep.coerceIn(0, steps.lastIndex)) }
    val step = steps[index]
    val status = state.firstRunStatus()
    val next: () -> Unit = { if (index < steps.lastIndex) index += 1 else onFinished(ExperienceMode.ESSENTIAL) }

    SetupLayer {
        Column(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp)
        ) {
            if (steps.size > 1) StepIndicator(steps, index)
            val scroll = rememberScrollState()
            ScrollbarBox(scroll, Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().verticalScroll(scroll), contentAlignment = Alignment.CenterStart) {
                    when (step) {
                        FirstRunStep.SERVER -> ServerStep(state, status)
                        FirstRunStep.SOURCES -> SourcesStep(state, status)
                        FirstRunStep.METADATA -> MetadataStep(state, status)
                        FirstRunStep.EXPERIENCE -> ExperienceStep(onChoose = onFinished)
                    }
                }
            }
            state.message?.let { Text(it, color = FetchPalette.TextSecondary) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) SecondaryButton("Back", onClick = { index -= 1 })
                if (index == steps.lastIndex) {
                    // The cards are the answer here; skipping takes the recommended one.
                    SecondaryButton("Skip for now", onClick = { onFinished(ExperienceMode.ESSENTIAL) })
                } else if (FirstRunState.isDone(step, status)) {
                    PrimaryButton("Continue", icon = null, onClick = next)
                } else {
                    SecondaryButton("Skip for now", onClick = next)
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(steps: List<FirstRunStep>, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        steps.forEachIndexed { i, step ->
            Text(
                "${i + 1}  ${step.title}",
                fontSize = 15.sp,
                fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    i == current -> FetchPalette.TextPrimary
                    i < current -> FetchPalette.TextSecondary
                    else -> FetchPalette.TextMuted
                }
            )
        }
    }
}

@Composable
private fun StepIntro(title: String, body: String, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = modifier) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = FetchPalette.TextPrimary)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = FetchPalette.TextSecondary)
    }
}

/** A label and what is set up for it; [done] marks it so a glance shows what's left. */
@Composable
private fun StatusLine(label: String, value: String, done: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = FetchPalette.TextSecondary, fontSize = 14.sp)
        Text(
            if (done) "✓ $value" else value,
            fontSize = 17.sp,
            fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
            color = if (done) FetchPalette.TextPrimary else FetchPalette.TextMuted
        )
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = FetchPalette.TextMuted, fontSize = 14.sp)

// --- Server ---

@Composable
private fun ServerStep(state: AppState, status: FirstRunStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
        StepIntro(
            "Connect your Fetch server",
            "A self-hosted Fetch website keeps profiles, parental limits, collections and watch history in sync with your TVs. " +
                "No server? Skip this and Fetch works on this computer on its own.",
            Modifier.weight(1f)
        )
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.weight(1.2f)) {
            val account = state.account
            if (status.serverSignedIn && account != null) {
                StatusLine("Fetch server", listOfNotNull(account.state.session?.email, account.state.server?.url).joinToString(" · "), done = true)
            } else {
                AccountPage(state, header = false)
            }
        }
    }
}

// --- Sources ---

@Composable
private fun SourcesStep(state: AppState, status: FirstRunStatus) {
    var showServers by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.weight(1.3f)) {
            StepIntro("Add something to watch", "Streams come from Stremio addons. Paste a stream addon's link; it usually ends in manifest.json.")
            AddonLinkField(state)
            if (state.addons.isNotEmpty()) {
                Text("Installed: " + state.addons.joinToString(", ") { it.displayName }, color = FetchPalette.TextSecondary, fontSize = 14.sp)
            }
            if (showServers) {
                MediaServersPage(state, header = false)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.weight(1f)) {
            StatusLine(
                "Stream addons",
                if (status.streamAddons > 0) "${status.streamAddons} installed" else "None yet. Movies and shows will list, but won't play.",
                done = status.streamAddons > 0
            )
            StatusLine(
                "Media servers",
                status.mediaServers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None yet. Jellyfin, Emby and Plex add your own files.",
                done = status.mediaServers.isNotEmpty()
            )
            if (!showServers) SecondaryButton("Connect a media server", onClick = { showServers = true })
            Hint("Real-Debrid, Premiumize and TorBox sign in on a stream addon's own setup page, which opens in your browser when the addon has one.")
        }
    }
}

/** A manifest link and Install, as Settings › Addons has. Enter installs too. */
@Composable
internal fun AddonLinkField(state: AppState) {
    var url by remember { mutableStateOf("") }
    val install = {
        if (url.isNotBlank()) {
            state.installAddon(url.trim())
            url = ""
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            textStyle = TextStyle(color = FetchPalette.TextPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(FetchPalette.Accent),
            decorationBox = { field ->
                Box {
                    if (url.isEmpty()) Text("https://example.com/manifest.json", color = FetchPalette.TextMuted, fontSize = 16.sp)
                    field()
                }
            },
            modifier = Modifier.weight(1f).widthIn(min = 280.dp)
                .clip(RoundedCornerShape(10.dp)).background(FetchPalette.Surface)
                .border(1.dp, FetchPalette.Outline, RoundedCornerShape(10.dp)).padding(14.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                        install(); true
                    } else false
                }
        )
        PrimaryButton("Install", icon = null, enabled = url.isNotBlank(), onClick = install)
    }
}

// --- Metadata ---

@Composable
private fun MetadataStep(state: AppState, status: FirstRunStatus) {
    val builtInTmdb = remember { com.fetch.desktop.ratings.TmdbCredentials.load() != null }
    Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.weight(1.3f)) {
            StepIntro("Ratings and tracking", "Richer title pages from TMDB, ratings from MDBList, and Trakt or Simkl to keep what you watch in sync.")
            SettingsSection(null) {
                SettingsToggle("Richer details from TMDB", "Artwork, cast, episode titles and trailers on top of what addons give.", status.tmdbEnabled) { v ->
                    state.updateSettings { it.copy(tmdb = it.tmdb.copy(enabled = v)) }
                }
                if (!builtInTmdb) {
                    SettingsTextRow("Your TMDB key", "This build has no TMDB key. Add a free one from themoviedb.org.", state.settings.tmdbApiKey.orEmpty()) { key ->
                        state.updateSettings { it.copy(tmdbApiKey = key.ifBlank { null }) }
                    }
                }
                ApiKeyRow(
                    value = state.settings.mdblist.apiKey,
                    description = "MDBList ratings: your key from mdblist.com › Preferences.",
                    verify = state.mdbListRatings::verify,
                    onSave = { key -> state.updateSettings { it.copy(mdblist = it.mdblist.copy(apiKey = key, enabled = key.isNotBlank() || it.mdblist.enabled)) } }
                )
            }
            TrackingPage(state, header = false)
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.weight(1f)) {
            StatusLine("MDBList ratings", if (status.mdbListReady) "Key added" else "No key yet", done = status.mdbListReady)
            StatusLine(
                "Trakt and Simkl",
                status.trackingConnected.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Connected: ") ?: "Not connected",
                done = status.trackingConnected.isNotEmpty()
            )
        }
    }
}

// --- Experience ---

@Composable
private fun ExperienceStep(onChoose: (ExperienceMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.widthIn(max = 760.dp)) {
        Text("Choose your Fetch experience", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = FetchPalette.TextPrimary)
        ExperienceModeCard(
            "Essential",
            "Focused setup, addons, playback basics, Trakt, and account settings.",
            recommended = true,
            onClick = { onChoose(ExperienceMode.ESSENTIAL) }
        )
        ExperienceModeCard(
            "Advanced",
            "Full settings, layout controls, catalog order, collections, integrations, and tuning.",
            recommended = false,
            onClick = { onChoose(ExperienceMode.ADVANCED) }
        )
        Text("Start simple or unlock every customization. You can switch anytime in Settings › Advanced.", color = FetchPalette.TextMuted)
    }
}

@Composable
private fun ExperienceModeCard(title: String, subtitle: String, recommended: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(16.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clip(shape)
            .background(if (hovered) FetchPalette.SurfaceHigh else FetchPalette.SurfaceRaised)
            .border(if (hovered) 2.dp else 1.dp, if (hovered) Color.White else FetchPalette.Outline, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(28.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = FetchPalette.TextPrimary)
            if (recommended) {
                Text(
                    "Recommended",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = FetchPalette.TextSecondary,
                    modifier = Modifier.border(1.dp, FetchPalette.TextMuted, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Text(subtitle, color = FetchPalette.TextSecondary)
    }
}

// --- Essential addon setup ---

/**
 * "Set up addons", as the TV's EssentialAddonSetupScreen: Essential mode with nothing that plays
 * yet. Install a stream addon's link, or Continue for now.
 */
@Composable
fun EssentialAddonSetup(state: AppState, onSkip: () -> Unit) {
    SetupLayer {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize().padding(72.dp)
        ) {
            Text("Set up addons", style = MaterialTheme.typography.headlineLarge, color = FetchPalette.TextPrimary)
            Text("Install one addon to start streaming. You can add more later from Addons.", color = FetchPalette.TextSecondary)
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FetchPalette.SurfaceRaised).padding(24.dp)
            ) {
                Text("Install addon", style = MaterialTheme.typography.titleMedium, color = FetchPalette.TextPrimary)
                AddonLinkField(state)
                Hint("Paste a stream addon's link; it usually ends in manifest.json. Debrid services sign in on the addon's own setup page.")
                if (state.addons.isNotEmpty()) {
                    Text("Installed: " + state.addons.joinToString(", ") { it.displayName }, color = FetchPalette.TextSecondary, fontSize = 14.sp)
                }
            }
            state.message?.let { Text(it, color = FetchPalette.TextSecondary) }
            SecondaryButton("Continue for now", onClick = onSkip)
        }
    }
}
