package com.fetch.desktop.ui

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
import com.fetch.desktop.AvailableUpdate
import com.fetch.desktop.ProfilesState
import com.fetch.desktop.SyncAccountState
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.Screen

@Composable
fun FetchApp() {
    val profiles = remember { ProfilesState() }
    val appScope = rememberCoroutineScope()
    val account = remember { SyncAccountState(appScope) }
    val active = profiles.active
    if (active == null) {
        // "Who's watching?" at start-up (several profiles, Remember Last Profile off) or after
        // Switch profile.
        FetchTheme { ProfilePicker(profiles) }
        return
    }
    // Each profile gets its own state, from its own files.
    key(active.id, active.usesPrimaryAddons) { ProfileApp(profiles, active, account) }
}

@Composable
private fun ProfileApp(profiles: ProfilesState, profile: DesktopProfile, account: SyncAccountState) {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope, profiles, profile, account = account).also { FetchPalette.setTheme(it.settings.appTheme) } }
    LaunchedEffect(Unit) { state.start() }
    // Links from the command line or a later launch (fetch://…, stremio://…), once a profile is open.
    LaunchedEffect(state) {
        com.fetch.desktop.deeplink.DeepLinks.pending.collect { links ->
            if (links.isNotEmpty()) com.fetch.desktop.deeplink.DeepLinks.take().forEach(state::openDeepLink)
        }
    }
    // The watch party follows the sync sign-in, and ends with this profile.
    LaunchedEffect(account.signedIn) { state.party.refreshAvailability() }
    androidx.compose.runtime.DisposableEffect(state) { onDispose { state.party.dispose() } }

    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            state.message = null
        }
    }

    // The accent follows Settings › Appearance.
    val theme = state.settings.appTheme
    SideEffect { FetchPalette.setTheme(theme) }

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
                        add(SidebarItem("Home", FetchIcons.Home, state.screen == Screen.Home || state.screen is Screen.Detail ||
                            state.screen is Screen.Person || state.screen is Screen.Entity || state.screen is Screen.SeeAll || state.screen is Screen.Folder) {
                            state.openSection(Screen.Home)
                        })
                        if (state.settings.discoverInSidebar) {
                            add(SidebarItem("Discover", FetchIcons.Discover, state.screen == Screen.Discover) {
                                state.openSection(Screen.Discover)
                            })
                        }
                        add(SidebarItem("Search", FetchIcons.Search, state.screen == Screen.Search) {
                            state.openSection(Screen.Search)
                        })
                        add(SidebarItem("Library", FetchIcons.Library, state.screen == Screen.Library) {
                            state.openSection(Screen.Library)
                        })
                        // YouTube, while this profile is signed in to it and has no limits (as on TV).
                        if (state.youtube.available) {
                            add(SidebarItem("YouTube", YouTubeIcon, state.screen == Screen.YouTube || state.screen is Screen.YouTubeBrowse) {
                                state.openSection(Screen.YouTube)
                            })
                        }
                        add(SidebarItem("Settings", FetchIcons.Settings, state.screen is Screen.Settings) {
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
        state.screenTimeBlock?.let { block -> ScreenTimeDialog(state, block) }
        // "Are you still watching?" after mpv's own window closed (the built-in player shows its own).
        state.stillWatching?.takeIf { state.playerSession == null }?.let { prompt ->
            StillWatchingOverlay(prompt.label, onContinue = { state.answerStillWatching(true) }, onStop = { state.answerStillWatching(false) })
        }
        if (state.playerChoice != null) PlayerChoiceDialog(state)
        state.manageListsFor?.let { preview -> ManageListsDialog(state, preview, onDismiss = { state.manageListsFor = null }) }
        state.updates.available?.takeIf { !state.updates.dismissed && state.playerSession == null }?.let { update ->
            UpdateBanner(state, update)
        }
        AmbientHost(state, lastInput, showing = ambient, onShow = { ambient = true }, onDismiss = input)
        if (ambient) {
            // A click on Ambient Mode only closes it.
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = input))
        }
        }
    }
}

/** The TV's poster long-press options, as a right-click menu. */
private class AppPosterActions(private val state: AppState) : PosterActions {
    override fun menu(item: com.fetch.tv.domain.model.MetaPreview) = buildList {
        add(androidx.compose.foundation.ContextMenuItem("Play") { state.navigate(Screen.Detail(item, autoPlay = true)) })
        add(androidx.compose.foundation.ContextMenuItem("Go to details") { state.navigate(Screen.Detail(item)) })
        val inLibrary = state.isInLibrary(item.id)
        add(androidx.compose.foundation.ContextMenuItem(if (inLibrary) "Remove from library" else "Add to library") {
            state.withImdbId(item) { state.toggleLibrary(it) }
        })
        add(androidx.compose.foundation.ContextMenuItem("Manage lists…") { state.manageLists(item) })
        val watched = isWatched(item)
        add(androidx.compose.foundation.ContextMenuItem(if (watched) "Mark as unwatched" else "Mark as watched") {
            state.withImdbId(item) { state.setTitleWatched(it, !watched) }
        })
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
        title = { Text("Play with", color = Color.White) },
        text = { Text("Built-in plays inside Fetch's window with Fetch's controls; mpv opens its own window.", color = FetchPalette.TextSecondary) },
        confirmButton = { PrimaryButton("Built-in player", onClick = { state.answerPlayerChoice(true) }) },
        dismissButton = { SecondaryButton("mpv window", onClick = { state.answerPlayerChoice(false) }) }
    )
}

/** A newer Fetch is out: where to get it, as the TV's update banner says. */
@Composable
private fun UpdateBanner(state: AppState, update: AvailableUpdate) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomEnd) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FetchPalette.SurfaceRaised)
                .padding(20.dp)
        ) {
            Text("Fetch ${update.version} is available", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            update.notes?.let { Text(it, color = FetchPalette.TextSecondary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Download", icon = null, onClick = {
                    state.openInBrowser(update.packageUrl ?: update.pageUrl)
                    state.updates.dismissed = true
                })
                SecondaryButton("Later", onClick = { state.updates.dismissed = true })
            }
        }
    }
}
