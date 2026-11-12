package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.fetch.desktop.AppState
import com.fetch.desktop.UpdateChecker
import com.fetch.desktop.AvailableUpdate
import com.fetch.desktop.ProfilesState
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.Screen
import kotlinx.coroutines.launch

@Composable
fun FetchApp() {
    val profiles = remember { ProfilesState() }
    val appScope = rememberCoroutineScope()
    // The start-up picker has no open profile to ask the website about a PIN set on another device.
    remember(profiles) {
        val website = com.fetch.desktop.website.FetchWebsiteState.shared
        profiles.remotePinCheck = { profile, pin -> website.verifyPin(profile.id, pin) }
        profiles.onLocalPinVerified = { profile, pin -> appScope.launch { website.onVerifiedLocally(profile.id, pin) } }
    }
    val active = profiles.active
    if (active == null) {
        // "Who's watching?" at start-up (several profiles, Remember Last Profile off) or after
        // Switch profile.
        FetchTheme { ProfilePicker(profiles) }
        return
    }
    // Each profile gets its own state, from its own files.
    key(active.id, active.usesPrimaryAddons) { ProfileApp(profiles, active) }
}

@Composable
private fun ProfileApp(profiles: ProfilesState, profile: DesktopProfile) {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope, profiles, profile, website = com.fetch.desktop.website.FetchWebsiteState.shared).also { FetchPalette.setAppearance(it.settings.appearance); FetchPalette.setTheme(it.settings.appTheme) } }
    LaunchedEffect(Unit) { state.start() }
    // The Fetch website's watch state and lists are read again when the window gets focus, and
    // every minute while it has it, as the TV reads them while it shows them.
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(state, windowFocused) { if (windowFocused) state.followWebsiteTracking() }
    // Links from the command line or a later launch (fetch://…, stremio://…), once a profile is open.
    LaunchedEffect(state) {
        com.fetch.desktop.deeplink.DeepLinks.pending.collect { links ->
            if (links.isNotEmpty()) com.fetch.desktop.deeplink.DeepLinks.take().forEach(state::openDeepLink)
        }
    }
    // Closing the window lets this profile's playback save, scrobble and sync first.
    androidx.compose.runtime.DisposableEffect(state) {
        val onClose: suspend () -> Unit = { state.shutdown() }
        com.fetch.desktop.AppShutdown.onClose = onClose
        onDispose { if (com.fetch.desktop.AppShutdown.onClose === onClose) com.fetch.desktop.AppShutdown.onClose = null }
    }

    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(tr(message))
            state.message = null
        }
    }

    // The accent follows Settings › Appearance.
    val theme = state.settings.appTheme
    val appearance = state.settings.appearance
    SideEffect {
        FetchPalette.setAppearance(appearance)
        FetchPalette.setTheme(theme)
    }

    val posterActions = remember(state) { AppPosterActions(state) }
    // Ambient Mode: the last mouse or key input, and whether it's showing.
    var lastInput by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    var ambient by remember { androidx.compose.runtime.mutableStateOf(false) }
    val input = {
        lastInput = System.currentTimeMillis()
        ambient = false
    }
    FetchTheme {
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        Box(
            Modifier.fillMaxSize()
                .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.input.pointer.PointerEventPass.Initial) { input() }
                .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll, androidx.compose.ui.input.pointer.PointerEventPass.Initial) { input() }
                .onPreviewKeyEvent {
                    val wasShowing = ambient
                    input()
                    // The key that closes Ambient Mode does nothing else, as on TV.
                    wasShowing
                }
        ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalPosterActions provides posterActions, LocalCardStyle provides cardStyleFor(state)) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(
                        data,
                        containerColor = FetchPalette.SurfaceHigh,
                        contentColor = FetchPalette.TextPrimary
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    // In Settings, Ctrl+F searches the settings.
                    event.isCtrlPressed && event.key == Key.F && state.screen is Screen.Settings -> {
                        SettingsSearchState.requestFocus()
                        true
                    }
                    // Escape clears a settings search before it goes back.
                    event.key == Key.Escape && state.screen is Screen.Settings && SettingsSearchState.query.isNotEmpty() -> {
                        SettingsSearchState.query = ""
                        true
                    }
                    // Ctrl+F (or Ctrl+K) opens search from anywhere; Ctrl+, opens Settings.
                    event.isCtrlPressed && (event.key == Key.F || event.key == Key.K) -> {
                        state.openSection(Screen.Search)
                        true
                    }
                    event.isCtrlPressed && event.key == Key.Comma -> {
                        state.openSection(Screen.Settings())
                        true
                    }
                    // Escape goes back, like Back on the TV remote. Backspace too on a title page,
                    // where there is no text field to type in.
                    (event.key == Key.Escape || (event.key == Key.Backspace && state.screen is Screen.Detail)) &&
                        state.canGoBack -> {
                        state.back()
                        true
                    }
                    else -> false
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
                AnimatedContent(
                    targetState = state.screen,
                    // Detail pages share one key, so going from one title to another doesn't fade;
                    // so do Settings pages, so the rail stays put.
                    contentKey = {
                        when (it) {
                            is Screen.Detail -> "detail"
                            is Screen.Settings -> "settings"
                            else -> it.toString()
                        }
                    },
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    modifier = Modifier.fillMaxSize().padding(start = SidebarWidth)
                ) { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen(state)
                        Screen.Search -> SearchScreen(state)
                        Screen.Discover -> DiscoverScreen(state)
                        Screen.Library -> LibraryScreen(state)
                        // Read the live page, since Settings pages share one transition key.
                        is Screen.Settings -> SettingsScreen(state, (state.screen as? Screen.Settings)?.page ?: screen.page)
                        // Read the live screen: a TMDB title's page swaps in its IMDb id once known.
                        is Screen.Detail -> DetailScreen(state, (state.screen as? Screen.Detail)?.preview ?: screen.preview)
                        is Screen.Person -> PersonScreen(state, screen)
                        is Screen.SeeAll -> SeeAllScreen(state, screen)
                        is Screen.Entity -> EntityScreen(state, screen)
                        is Screen.Folder -> FolderScreen(state, screen)
                        Screen.YouTube -> YouTubeScreen(state)
                        is Screen.YouTubeBrowse -> YouTubeBrowseScreen(state, screen.target)
                    }
                }
                // The TV sidebar: Home, Discover (when it has its own entry), Search, Library,
                // Settings. Addons lives in Settings › Content & Discovery, as on TV.
                Sidebar(
                    items = buildList {
                        add(SidebarItem(tr("Home"), FetchIcons.Home, state.screen == Screen.Home || state.screen is Screen.Detail ||
                            state.screen is Screen.Person || state.screen is Screen.Entity || state.screen is Screen.SeeAll || state.screen is Screen.Folder) {
                            state.openSection(Screen.Home)
                        })
                        if (state.settings.discoverInSidebar) {
                            add(SidebarItem(tr("Discover"), FetchIcons.Discover, state.screen == Screen.Discover) {
                                state.openSection(Screen.Discover)
                            })
                        }
                        add(SidebarItem(tr("Search"), FetchIcons.Search, state.screen == Screen.Search) {
                            state.openSection(Screen.Search)
                        })
                        add(SidebarItem(tr("Library"), FetchIcons.Library, state.screen == Screen.Library) {
                            state.openSection(Screen.Library)
                        })
                        // YouTube, while this profile is signed in to it and has no limits (as on TV).
                        if (state.youtube.available) {
                            add(SidebarItem("YouTube", YouTubeIcon, state.screen == Screen.YouTube || state.screen is Screen.YouTubeBrowse) {
                                state.openSection(Screen.YouTube)
                            })
                        }
                        add(SidebarItem(tr("Settings"), FetchIcons.Settings, state.screen is Screen.Settings) {
                            state.openSection(Screen.Settings())
                        })
                    },
                    profile = state.profiles.active,
                    onProfile = state.profiles::switchProfile,
                    profileFirst = state.limits.kidsProfile,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
        }
        }
        // The built-in player covers the whole window while it's open.
        state.playerSession?.let { session -> PlayerScreen(state, session) }
        // YouTube's end card, over the built-in player or (after mpv's window) the app.
        YouTubeUpNextOverlay(state)
        // "Prefer this next time", and a movie's recommendation after mpv's window closed.
        AppExtrasLayer(state)
        state.screenTimeBlock?.let { block -> ScreenTimeDialog(state, block) }
        // "Are you still watching?" after mpv's own window closed (the built-in player shows its own).
        state.stillWatching?.takeIf { state.playerSession == null }?.let { prompt ->
            StillWatchingOverlay(prompt.label, onContinue = { state.answerStillWatching(true) }, onStop = { state.answerStillWatching(false) })
        }
        if (state.playerChoice != null) PlayerChoiceDialog(state)
        ParentalTitleDialogHost(state)
        state.manageListsFor?.let { preview -> ManageListsDialog(state, preview, onDismiss = { state.manageListsFor = null }) }
        state.updates.available?.takeIf { !state.updates.dismissed && state.playerSession == null }?.let { update ->
            UpdateBanner(state, update)
        }
        AmbientHost(state, lastInput, showing = ambient, onShow = { ambient = true }, onDismiss = input)
        if (ambient) {
            // A click on Ambient Mode only closes it.
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = input))
        }
        // First run on a fresh install, then Essential's addon setup (FirstRunWizard.kt).
        FirstRunHost(state)
        }
    }
}

/** The TV's poster long-press options, as a right-click menu. */
private class AppPosterActions(private val state: AppState) : PosterActions {
    override fun menu(item: com.fetch.tv.domain.model.MetaPreview) = buildList {
        add(androidx.compose.foundation.ContextMenuItem(tr("Play")) { state.navigate(Screen.Detail(item, autoPlay = true)) })
        add(androidx.compose.foundation.ContextMenuItem(tr("Go to details")) { state.navigate(Screen.Detail(item)) })
        val inLibrary = state.isInLibrary(item.id)
        add(androidx.compose.foundation.ContextMenuItem(if (inLibrary) tr("Remove from library") else tr("Add to library")) {
            state.withImdbId(item) { state.toggleLibrary(it) }
        })
        add(androidx.compose.foundation.ContextMenuItem(tr("Manage lists…")) { state.manageLists(item) })
        queueMenuItem(state, item)?.let(::add)
        val watched = isWatched(item)
        add(androidx.compose.foundation.ContextMenuItem(if (watched) tr("Mark as unwatched") else tr("Mark as watched")) {
            state.withImdbId(item) { state.setTitleWatched(it, !watched) }
        })
        addAll(parentalTitleMenu(state, item))
    }

    override fun isWatched(item: com.fetch.tv.domain.model.MetaPreview): Boolean =
        item.type != com.fetch.tv.domain.model.ContentType.SERIES && state.isWatched(item.id)
}

/** "Default Player: Ask every time": where to play this one. */
@Composable
private fun PlayerChoiceDialog(state: AppState) {
    AlertDialog(
        onDismissRequest = { state.answerPlayerChoice(null) },
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(tr("Play with"), color = Color.White) },
        text = { Text(tr("Built-in plays inside Fetch's window with Fetch's controls; mpv opens its own window."), color = FetchPalette.TextSecondary) },
        confirmButton = { PrimaryButton("Built-in player", onClick = { state.answerPlayerChoice(true) }) },
        dismissButton = { SecondaryButton("mpv window", onClick = { state.answerPlayerChoice(false) }) }
    )
}

/**
 * A newer Fetch is out: where to get it, as the TV's update banner says. An install that follows
 * the APT repository gets the apt command instead of a download.
 */
@Composable
private fun UpdateBanner(state: AppState, update: AvailableUpdate) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomEnd) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FetchPalette.SurfaceRaised)
                .padding(20.dp)
        ) {
            Text(tr("Fetch %1\$s is available", update.version), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            update.notes?.let { Text(it, color = FetchPalette.TextSecondary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (update.viaApt) {
                Text(tr("Install it from a terminal:"), color = FetchPalette.TextSecondary, fontSize = 14.sp)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        UpdateChecker.APT_UPGRADE_COMMAND,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(FetchPalette.Surface).padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (update.viaApt) {
                    PrimaryButton("Copy command", icon = null, onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(UpdateChecker.APT_UPGRADE_COMMAND))
                        state.message = "Copied. Run it in a terminal, then restart Fetch."
                        state.updates.dismissed = true
                    })
                    SecondaryButton("What's new", onClick = { state.openInBrowser(update.pageUrl) })
                } else {
                    PrimaryButton("Download", icon = null, onClick = {
                        state.openInBrowser(update.packageUrl ?: update.pageUrl)
                        state.updates.dismissed = true
                    })
                }
                SecondaryButton("Later", onClick = { state.updates.dismissed = true })
            }
        }
    }
}
