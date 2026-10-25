@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import com.fetch.tv.ui.screens.party.PartySettingsContent
import com.fetch.tv.ui.screens.party.PartyViewModel
import com.fetch.tv.ui.theme.FetchTheme

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.fetch.tv.BuildConfig
import com.fetch.tv.core.build.AppFeaturePolicy
import com.fetch.tv.R
import com.fetch.tv.domain.model.ExperienceMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

internal enum class SettingsSectionDestination {
    Inline,
    External
}

internal data class SettingsSectionSpec(
    val category: SettingsCategory,
    val title: String,
    val icon: ImageVector? = null,
    @param:RawRes val rawIconRes: Int? = null,
    val destination: SettingsSectionDestination
)

private const val SETTINGS_DETAIL_FOCUS_DELAY_MS = 120L
// The rail gets the same treatment as the options pane: bring the item into view, then keep
// asking for a short while as it settles.
private const val SETTINGS_RAIL_FOCUS_RETRY_WINDOW_MS = 200L

/**
 * Stands for "no attempt handed focus to the rail container". Attempts are numbered from one, so
 * this has to sit outside that range: at zero it matches the number the counter starts on, and the
 * first time the rail takes focus the mark reads as set and the restoration is skipped.
 */
private const val NO_RAIL_FALLBACK = -1L
private const val SETTINGS_TAB_FOCUS_SELECT_DELAY_MS = 140L
private const val SETTINGS_DETAIL_ANIM_IN_DURATION_MS = 200
private const val SETTINGS_DETAIL_ANIM_OUT_DURATION_MS = 180

// The focus window has two parts: SETTINGS_DETAIL_FOCUS_DELAY_MS before the first request, then
// this long retrying once per frame, so a category has both added together before focus falls
// back to a directional move. This part is taken from the enter animation so the two cannot
// drift apart, and is timed rather than counted in frames since a frame count assumes a
// refresh rate. The worst case wait before the fallback is SETTINGS_DETAIL_FOCUS_DELAY_MS plus
// this window, not this window alone.
private const val SETTINGS_DETAIL_FOCUS_RETRY_WINDOW_MS = SETTINGS_DETAIL_ANIM_IN_DURATION_MS

private sealed interface ExperienceModeLoadState {
    data object Loading : ExperienceModeLoadState
    data class Loaded(val mode: ExperienceMode?) : ExperienceModeLoadState
}

@Composable
private fun settingsSectionSpec(category: SettingsCategory): SettingsSectionSpec = when (category) {
    SettingsCategory.SEARCH -> SettingsSectionSpec(category, stringResource(R.string.settings_search_title), Icons.Default.Search, destination = SettingsSectionDestination.Inline)
    SettingsCategory.ACCOUNT -> SettingsSectionSpec(category, stringResource(R.string.settings_account), Icons.Default.Person, destination = SettingsSectionDestination.Inline)
    SettingsCategory.PROFILES -> SettingsSectionSpec(category, stringResource(R.string.settings_profiles), Icons.Default.People, destination = SettingsSectionDestination.Inline)
    SettingsCategory.WATCH_PARTY -> SettingsSectionSpec(category, stringResource(R.string.party_title), Icons.Rounded.Groups, destination = SettingsSectionDestination.Inline)
    SettingsCategory.PHONE -> SettingsSectionSpec(category, stringResource(R.string.phone_hub_title), Icons.Rounded.PhoneAndroid, destination = SettingsSectionDestination.Inline)
    SettingsCategory.APPEARANCE -> SettingsSectionSpec(category, stringResource(R.string.appearance_title), Icons.Default.Palette, destination = SettingsSectionDestination.Inline)
    SettingsCategory.LAYOUT -> SettingsSectionSpec(category, stringResource(R.string.settings_layout), Icons.Default.GridView, destination = SettingsSectionDestination.Inline)
    SettingsCategory.CONTENT_DISCOVERY -> SettingsSectionSpec(category, stringResource(R.string.settings_content_discovery), Icons.Default.Explore, destination = SettingsSectionDestination.Inline)
    SettingsCategory.PLAYBACK -> SettingsSectionSpec(category, stringResource(R.string.settings_playback), Icons.Rounded.PlayArrow, destination = SettingsSectionDestination.Inline)
    SettingsCategory.TRACKING -> SettingsSectionSpec(category, stringResource(R.string.settings_tracking_title), Icons.Default.Sync, destination = SettingsSectionDestination.Inline)
    SettingsCategory.YOUTUBE -> SettingsSectionSpec(category, stringResource(R.string.youtube_name), rawIconRes = R.raw.youtube_logo, destination = SettingsSectionDestination.Inline)
    SettingsCategory.TMDB -> SettingsSectionSpec(category, "TMDB", rawIconRes = R.raw.mdblist_tmdb, destination = SettingsSectionDestination.Inline)
    SettingsCategory.MDBLIST -> SettingsSectionSpec(category, stringResource(R.string.mdblist_title), rawIconRes = R.raw.mdblist_logo, destination = SettingsSectionDestination.Inline)
    SettingsCategory.ANIME_SKIP -> SettingsSectionSpec(category, "Anime-Skip", Icons.Default.FastForward, destination = SettingsSectionDestination.Inline)
    SettingsCategory.ADVANCED -> SettingsSectionSpec(category, stringResource(R.string.settings_advanced), Icons.Default.Build, destination = SettingsSectionDestination.Inline)
    SettingsCategory.ABOUT -> SettingsSectionSpec(category, stringResource(R.string.about_title), Icons.Default.Info, destination = SettingsSectionDestination.Inline)
    SettingsCategory.DEBUG -> SettingsSectionSpec(category, stringResource(R.string.settings_debug), Icons.Default.BugReport, destination = SettingsSectionDestination.Inline)
    SettingsCategory.EXPERIENCE -> SettingsSectionSpec(category, stringResource(R.string.settings_experience), Icons.Default.Tune, destination = SettingsSectionDestination.Inline)
}

@Composable
private fun settingsGroupTitle(group: SettingsRailGroup): String = when (group) {
    SettingsRailGroup.PROFILES -> stringResource(R.string.settings_profiles)
    SettingsRailGroup.PLAYBACK -> stringResource(R.string.settings_playback)
    SettingsRailGroup.SOURCES -> stringResource(R.string.sources_title)
    SettingsRailGroup.HOME -> stringResource(R.string.nav_home)
    SettingsRailGroup.ACCOUNTS -> stringResource(R.string.settings_group_accounts)
    SettingsRailGroup.SYSTEM -> stringResource(R.string.settings_group_system)
}

@Composable
fun SettingsScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToAddons: () -> Unit = {},
    onNavigateToAuthQrSignIn: () -> Unit = {},
    onNavigateToManageProfiles: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    onNavigateToWatchParty: () -> Unit = {},
    onNavigateToPhoneHub: () -> Unit = {},
    profileViewModel: ProfileSettingsViewModel = hiltViewModel(),
    experienceModeViewModel: ExperienceModeSettingsViewModel = hiltViewModel(),
    partyViewModel: PartyViewModel = hiltViewModel()
) {
    val isPrimaryProfileActive by profileViewModel.isPrimaryProfileActive.collectAsStateWithLifecycle()
    val isWatchPartyAvailable by partyViewModel.party.available.collectAsStateWithLifecycle()
    val experienceModeState by remember(experienceModeViewModel) {
        experienceModeViewModel.mode.map<ExperienceMode?, ExperienceModeLoadState> {
            ExperienceModeLoadState.Loaded(it)
        }
    }.collectAsStateWithLifecycle(initialValue = ExperienceModeLoadState.Loading)
    val loadedExperienceMode = (experienceModeState as? ExperienceModeLoadState.Loaded)?.mode
    val experienceModeLoaded = experienceModeState is ExperienceModeLoadState.Loaded

    if (!experienceModeLoaded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FetchTheme.colors.Background)
        )
        return
    }

    val isEssentialMode = loadedExperienceMode == ExperienceMode.ESSENTIAL

    val visibleCategories = remember(isPrimaryProfileActive, isEssentialMode, isWatchPartyAvailable) {
        visibleSettingsCategories(
            isPrimaryProfile = isPrimaryProfileActive,
            isEssentialMode = isEssentialMode,
            isDebugBuild = BuildConfig.IS_DEBUG_BUILD,
            isWatchPartyAvailable = isWatchPartyAvailable,
            isYouTubeAvailable = AppFeaturePolicy.youTubeSectionEnabled
        )
    }
    val visibleSections = visibleCategories.map { category -> settingsSectionSpec(category) }

    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    var selectedCategory by rememberSaveable {
        mutableStateOf(
            visibleSections.firstOrNull()?.category ?: SettingsCategory.APPEARANCE
        )
    }
    val railFocusRequesters = remember(visibleSections) {
        visibleSections.associate { it.category to FocusRequester() }
    }
    val contentFocusRequesters = remember {
        SettingsCategory.entries.associateWith { FocusRequester() }
    }
    val railContainerFocusRequester = remember { FocusRequester() }
    var pendingContentFocusCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var pendingContentFocusRequestId by remember { mutableLongStateOf(0L) }
    // Saveable so it survives a trip out to one of the screens a category opens. The pane bounces
    // focus back to the rail whenever it gains focus while this is false, so a plain remember made
    // every return from those screens land on the rail rather than where the user had been.
    var allowDetailAutofocus by rememberSaveable { mutableStateOf(false) }
    var detailHasFocus by remember { mutableStateOf(false) }
    // The rail item that last had focus. Not the same as the selected category: an external
    // category such as account sign-in never becomes the selected one, and moving along the rail without
    // opening anything does not change it either. Saveable so returning from the sidebar, or from
    // a screen a category opened, comes back to the item the user left from.
    var railFocusCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val railFocusCategory = railFocusCategoryName
        ?.let { name -> SettingsCategory.entries.firstOrNull { it.name == name } }
        // The name surviving is not enough. Which categories the rail shows depends on the profile
        // and on essential mode, so the one that was left can be gone by the time it is returned
        // to. Restoring to it would spend the whole retry window on an item that is not there.
        ?.takeIf { category -> visibleSections.any { it.category == category } }
        ?: selectedCategory
    val railListState = rememberLazyListState()
    val railScope = rememberCoroutineScope()

    // The rail is a lazy list, so an item that has scrolled out of view has no attached requester
    // and cannot take focus. Bring it back into view first, then ask, retrying while it composes.
    // Without this, restoring to a scrolled out item silently failed and focus was left to the
    // geometric search, which is how coming back from the sidebar landed on the wrong item.
    var railHadFocus by remember { mutableStateOf(false) }
    var railFocusJob by remember { mutableStateOf<Job?>(null) }
    // The attempt whose fallback handed focus to the rail container, or 0. The container gaining
    // focus is what triggers a restoration, so without this the fallback asks for the same
    // unavailable item again instead of ending the attempt. Numbered rather than a flag so a
    // newer attempt cannot inherit an older one's mark.
    var railFallbackAttempt by remember { mutableLongStateOf(NO_RAIL_FALLBACK) }
    // The category an attempt is currently working towards, so that item can end the attempt when
    // it takes focus by any route. Only that item: cancelling on any other is how an earlier
    // version let the rail's own choice of child kill a restoration and drift away from the user.
    var railRestoringCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    // Identifies an attempt. Cancellation is cooperative, so a stopped attempt can still run as
    // far as its next suspension point, and without this its cleanup would clear state belonging
    // to the attempt that replaced it.
    var railFocusAttempt by remember { mutableLongStateOf(0L) }
    val focusRailCategory: (SettingsCategory) -> Unit = { category ->
        railFocusJob?.cancel()
        railFocusJob = null
        // Cleared here, not only by the attempt that set it. A cancelled attempt is forbidden from
        // clearing it once the number has moved on, and an attempt that lands straight away never
        // sets one, so without this a target could outlive every attempt behind it.
        railRestoringCategory = null
        railFocusAttempt += 1L
        val attempt = railFocusAttempt
        // Try straight away first, without a coroutine: a suspension point before the request
        // leaves a gap for the rail to settle on a child of its own, and that child then records
        // itself as the item to come back to.
        //
        // The rail's own focusRestorer covers the ordinary case, where the item being returned to
        // is still composed and is the one the rail last had focus on. This is for what it cannot
        // do: a target that is off screen and so has no node to restore to, and a target that is
        // not the child the rail last held, such as the category a Back press names. Only those
        // reach the scroll and retry below.
        val landed = railFocusRequesters[category]
            ?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
        if (!landed) {
            // Set before launching, so the attempt advertises its target from the start.
            railRestoringCategory = category
            railFocusJob = railScope.launch {
                try {
                    val index = visibleSections.indexOfFirst { it.category == category }
                    // Only when it is not on screen at all. An item that is even partly on screen
                    // can usually take focus as it is, and scrollToItem would drag it to the start
                    // of the viewport and move the whole rail under the user for nothing.
                    // Visibility is not the same as having an attached requester, which is what
                    // the retry below is for.
                    if (index >= 0 && !railListState.isItemVisible(index)) {
                        runCatching { railListState.scrollToItem(index) }
                    }
                    val deadline =
                        System.nanoTime() + SETTINGS_RAIL_FOCUS_RETRY_WINDOW_MS * 1_000_000L
                    var focused = false
                    while (!focused && attempt == railFocusAttempt && System.nanoTime() < deadline) {
                        focused = railFocusRequesters[category]
                            ?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
                        if (!focused) withFrameNanos { }
                    }
                    // Only when the rail does not already hold focus. If it does, focus is
                    // somewhere sensible in it already, and asking the container again would
                    // neither move anything nor produce the transition that clears the mark.
                    if (!focused && !railHadFocus && attempt == railFocusAttempt) {
                        val handedOver = runCatching {
                            railContainerFocusRequester.requestFocus()
                        }.getOrDefault(false)
                        if (handedOver) railFallbackAttempt = attempt
                    }
                } finally {
                    // Also on cancellation, so a stopped attempt does not leave its target or its
                    // job behind, and only if this is still the current attempt.
                    if (attempt == railFocusAttempt) {
                        railRestoringCategory = null
                        railFocusJob = null
                    }
                }
            }
        }
    }

    // Back inside the options pane returns to the category rail before it leaves settings, the
    // way back inside a row returns to the start of that row. Anything the pane declares itself,
    // such as the integrations sub sections, composes later and so is asked first.
    BackHandler(enabled = detailHasFocus) {
        // Cleared up front rather than after the request. The rail reports its own focus a frame
        // later, and until it does a second quick press would be consumed here again instead of
        // leaving settings. The result of the request is deliberately not read: either way this
        // press was the step back, and with the flag already cleared the next one carries on out.
        detailHasFocus = false
        focusRailCategory(railFocusCategory)
    }

    val focusManager = LocalFocusManager.current

    LaunchedEffect(visibleSections) {
        if (visibleSections.none { it.category == selectedCategory }) {
            selectedCategory = visibleSections.firstOrNull()?.category ?: SettingsCategory.APPEARANCE
        }
    }

    LaunchedEffect(Unit) {
        // Categories such as addons open a destination of their own, so Settings leaves
        // composition and comes back with nothing asking for the options pane: the rail is simply
        // the first thing able to take focus. Landing there loses the user's place for a trip they
        // did not take through the rail. The saved flag says the pane was where they were, so aim
        // for it and leave the rail to the case where it really was the last thing focused.
        if (allowDetailAutofocus) {
            pendingContentFocusCategory = selectedCategory
            pendingContentFocusRequestId += 1L
        } else {
            runCatching { railContainerFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(pendingContentFocusRequestId) {
        val category = pendingContentFocusCategory ?: return@LaunchedEffect
        delay(SETTINGS_DETAIL_FOCUS_DELAY_MS)
        // Asked once per frame until the pane takes focus or the window closes, rather than
        // betting on one fixed delay. The pane is not always laid out when the delay expires,
        // and the fallback below moves by direction, which lands wherever is nearest the rail
        // row the user came from, usually the middle of the options.
        var requested = false
        val deadline = System.nanoTime() + SETTINGS_DETAIL_FOCUS_RETRY_WINDOW_MS * 1_000_000L
        while (!requested && System.nanoTime() < deadline) {
            requested = contentFocusRequesters[category]
                ?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
            if (!requested) withFrameNanos { }
        }
        if (!requested) {
            focusManager.moveFocus(FocusDirection.Right)
        }
        pendingContentFocusCategory = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FetchTheme.colors.Background)
            .padding(
                start = FetchTheme.spacing.xxl,
                end = FetchTheme.spacing.xxl,
                top = if (showBuiltInHeader) FetchTheme.spacing.xl else 68.dp,
                bottom = FetchTheme.spacing.xl
            )
    ) {
        SettingsWorkspaceSurface(
            modifier = Modifier
                .fillMaxSize()
        ) {

            val onSectionClick: (SettingsSectionSpec) -> Unit = { section ->
                if (section.destination == SettingsSectionDestination.External) {
                    // These have no options of their own to come back to: the rail row is the
                    // whole category, and it is the rail the user is leaving from. Without this
                    // the flag keeps whatever an earlier visit to the pane left in it, and the
                    // return aims at the options of a category that was never opened.
                    allowDetailAutofocus = false
                    when (section.category) {
                        SettingsCategory.ACCOUNT -> onNavigateToAuthQrSignIn()
                        else -> Unit
                    }
                } else {
                    allowDetailAutofocus = true
                    selectedCategory = section.category
                    pendingContentFocusCategory = section.category
                    pendingContentFocusRequestId += 1L
                }
            }

            var focusedTabCategory by remember { mutableStateOf<SettingsCategory?>(null) }
            var lastRailFocusChangeMs by remember { mutableLongStateOf(0L) }
            val selectFocusedTab: (SettingsCategory) -> Unit = { category ->
                if (selectedCategory != category) {
                    allowDetailAutofocus = false
                    selectedCategory = category
                }
            }

            LaunchedEffect(focusedTabCategory) {
                val category = focusedTabCategory ?: return@LaunchedEffect
                val now = android.os.SystemClock.uptimeMillis()
                val msSinceLast = now - lastRailFocusChangeMs
                lastRailFocusChangeMs = now
                if (msSinceLast > SETTINGS_TAB_FOCUS_SELECT_DELAY_MS) {
                    selectFocusedTab(category)
                } else {
                    delay(SETTINGS_TAB_FOCUS_SELECT_DELAY_MS)
                    selectFocusedTab(category)
                }
            }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
        ) {
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
            ) {
                LazyColumn(
                    state = railListState,
                    modifier = Modifier
                        .focusRequester(railContainerFocusRequester)
                        // Lets the rail resolve its own focus enter to the item that was on it when
                        // it last lost focus. Without this the enter is resolved by stepping on from
                        // whatever holds focus, which lands an item further along and drags the rail
                        // with it. It agrees with focusRailCategory rather than competing with it:
                        // both name the item the rail was left on. It cannot restore an item that is
                        // no longer composed, which is what the retry there is for.
                        .focusRestorer()
                        .fillMaxSize()
                        .onFocusChanged { state ->
                            val justGainedFocus = !railHadFocus && state.hasFocus
                            railHadFocus = state.hasFocus
                            if (justGainedFocus) {
                                if (railFallbackAttempt == railFocusAttempt) {
                                    railFallbackAttempt = NO_RAIL_FALLBACK
                                } else {
                                    focusRailCategory(railFocusCategory)
                                }
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            // The user moving is the one signal that is unambiguously theirs,
                            // unlike a focus change, which the rail also produces on its own.
                            // Stop a restoration still retrying.
                            if (event.type == KeyEventType.KeyDown && event.key.isDirection()) {
                                // Retired by number as well as cancelled. The loops check the
                                // attempt number rather than isActive, so bumping it is what
                                // actually stops them; the cancel is just the quicker of the
                                // two.
                                railFocusAttempt += 1L
                                railFocusJob?.cancel()
                                railFocusJob = null
                                railRestoringCategory = null
                            }
                            val toDetailKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                            if (event.type == KeyEventType.KeyDown && event.key == toDetailKey) {
                                val pendingSection = focusedTabCategory
                                    ?.takeIf { it != selectedCategory || !allowDetailAutofocus }
                                    ?.let { category -> visibleSections.firstOrNull { it.category == category } }
                                if (pendingSection != null) {
                                    onSectionClick(pendingSection)
                                    true
                                } else {
                                    allowDetailAutofocus = true
                                    false
                                }
                            } else {
                                false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                ) {
                    itemsIndexed(
                        items = visibleSections,
                        key = { _, section -> section.category }
                    ) { index, section ->
                        Column {
                            if (visibleCategories.startsNewGroup(index)) {
                                section.category.group?.let { group ->
                                    SettingsRailHeading(
                                        text = settingsGroupTitle(group),
                                        first = index == visibleCategories.indexOfFirst { it.group != null }
                                    )
                                }
                            }
                            SettingsRailButton(
                                onFocused = {
                                    val restoringTo = railRestoringCategory
                                    if (section.category == restoringTo) {
                                        // Cleared here rather than waiting for the attempt to
                                        // reach its next suspension point. The attempt is left
                                        // running: it ends on its own once the request lands, and
                                        // a directional press stops it early.
                                        railRestoringCategory = null
                                        railFocusCategoryName = section.category.name
                                    } else if (restoringTo == null) {
                                        railFocusCategoryName = section.category.name
                                    }
                                    if (section.destination == SettingsSectionDestination.Inline) {
                                        focusedTabCategory = section.category
                                    }
                                },
                                title = section.title,
                                icon = section.icon,
                                rawIconRes = section.rawIconRes,
                                isSelected = selectedCategory == section.category,
                                focusRequester = railFocusRequesters[section.category],
                                onClick = { onSectionClick(section) },
                                suppressSelectedVisual = railHadFocus
                                    && focusedTabCategory != null
                                    && focusedTabCategory != section.category
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = railListState)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onKeyEvent { event ->
                        val toRailKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                        if (event.type == KeyEventType.KeyDown && event.key == toRailKey) {
                            val movedLeft = focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)
                            if (!movedLeft) {
                                allowDetailAutofocus = false
                                focusRailCategory(railFocusCategory)
                            }
                            true
                        } else {
                            false
                        }
                    }
                    .onFocusChanged { state ->
                        detailHasFocus = state.hasFocus
                        if (state.hasFocus && !allowDetailAutofocus) {
                            focusRailCategory(railFocusCategory)
                        }
                    }
            ) {
                SettingsDetailPane(
                    selectedCategory = selectedCategory,
                    isEssentialMode = isEssentialMode,
                    allowDetailAutofocus = allowDetailAutofocus,
                    contentFocusRequesters = contentFocusRequesters,
                    experienceModeViewModel = experienceModeViewModel,
                    visibleCategories = visibleCategories,
                    onOpenSearchResult = { category ->
                        visibleSections.firstOrNull { it.category == category }?.let(onSectionClick)
                    },
                    onNavigateToManageProfiles = onNavigateToManageProfiles,
                    onNavigateToAddons = onNavigateToAddons,
                    onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
                    onNavigateToLicensesAttributions = onNavigateToLicensesAttributions,
                    onNavigateToWatchParty = onNavigateToWatchParty,
                    onNavigateToPhoneHub = onNavigateToPhoneHub
                )
            }
            }
        }
    }
}

@Composable
private fun SettingsDetailPane(
    selectedCategory: SettingsCategory,
    isEssentialMode: Boolean,
    allowDetailAutofocus: Boolean,
    contentFocusRequesters: Map<SettingsCategory, FocusRequester>,
    experienceModeViewModel: ExperienceModeSettingsViewModel,
    visibleCategories: List<SettingsCategory>,
    onOpenSearchResult: (SettingsCategory) -> Unit,
    onNavigateToManageProfiles: () -> Unit,
    onNavigateToAddons: () -> Unit,
    onNavigateToAuthQrSignIn: () -> Unit,
    onNavigateToLicensesAttributions: () -> Unit,
    onNavigateToWatchParty: () -> Unit,
    onNavigateToPhoneHub: () -> Unit
) {
    // Only when the pane was entered on purpose; otherwise moving along the rail would pull focus in.
    val focusFor: (SettingsCategory) -> FocusRequester? = { category ->
        if (allowDetailAutofocus) contentFocusRequesters[category] else null
    }
    when (selectedCategory) {
        SettingsCategory.EXPERIENCE -> EssentialAdvancedSettingsContent(
            experienceModeViewModel = experienceModeViewModel,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.EXPERIENCE]
            } else {
                null
            }
        )
        SettingsCategory.PROFILES -> ProfileSettingsContent(
            onManageProfiles = onNavigateToManageProfiles,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.PROFILES]
            } else {
                null
            }
        )
        SettingsCategory.WATCH_PARTY -> PartySettingsContent(
            onOpen = onNavigateToWatchParty,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.WATCH_PARTY]
            } else {
                null
            }
        )
        SettingsCategory.PHONE -> com.fetch.tv.ui.screens.phone.PhoneHubSettingsContent(
            onOpen = onNavigateToPhoneHub,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.PHONE]
            } else {
                null
            }
        )
        SettingsCategory.APPEARANCE -> ThemeSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.APPEARANCE]
            } else {
                null
            }
        )
        SettingsCategory.LAYOUT -> LayoutSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.LAYOUT]
            } else {
                null
            },
            essentialMode = isEssentialMode
        )
        SettingsCategory.PLAYBACK -> if (isEssentialMode) {
            EssentialPlaybackSettingsContent(
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.PLAYBACK]
                } else {
                    null
                }
            )
        } else {
            PlaybackSettingsContent(
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.PLAYBACK]
                } else {
                    null
                }
            )
        }
        SettingsCategory.ADVANCED -> if (isEssentialMode) {
            EssentialAdvancedSettingsContent(
                experienceModeViewModel = experienceModeViewModel,
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.ADVANCED]
                } else {
                    null
                }
            )
        } else {
            AdvancedSettingsContent(
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.ADVANCED]
                } else {
                    null
                },
                experienceModeViewModel = experienceModeViewModel
            )
        }
        SettingsCategory.SEARCH -> SettingsSearchContent(
            visibleCategories = visibleCategories,
            pageTitle = { settingsSectionSpec(it).title },
            groupTitle = { settingsGroupTitle(it) },
            onOpen = onOpenSearchResult,
            initialFocusRequester = focusFor(SettingsCategory.SEARCH)
        )
        SettingsCategory.TRACKING -> TrackingSettingsContent(initialFocusRequester = focusFor(SettingsCategory.TRACKING))
        SettingsCategory.YOUTUBE -> YouTubeSettingsContent(initialFocusRequester = focusFor(SettingsCategory.YOUTUBE))
        SettingsCategory.TMDB -> TmdbSettingsContent(initialFocusRequester = focusFor(SettingsCategory.TMDB))
        SettingsCategory.MDBLIST -> MDBListSettingsContent(initialFocusRequester = focusFor(SettingsCategory.MDBLIST))
        SettingsCategory.ANIME_SKIP -> AnimeSkipSettingsContent(initialFocusRequester = focusFor(SettingsCategory.ANIME_SKIP))
        SettingsCategory.ABOUT -> AboutSettingsContent(
            onNavigateToLicensesAttributions = onNavigateToLicensesAttributions,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.ABOUT]
            } else {
                null
            }
        )
        SettingsCategory.CONTENT_DISCOVERY -> ContentDiscoverySettingsContent(
            onNavigateToAddons = onNavigateToAddons,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.CONTENT_DISCOVERY]
            } else {
                null
            }
        )
        SettingsCategory.ACCOUNT -> AccountSettingsInline(
            onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.ACCOUNT]
            } else {
                null
            }
        )
        SettingsCategory.DEBUG -> DebugSettingsContent()
    }
}

@Composable
private fun EssentialAdvancedSettingsContent(
    experienceModeViewModel: ExperienceModeSettingsViewModel,
    initialFocusRequester: FocusRequester?
) {
    var showConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_advanced),
            subtitle = stringResource(R.string.experience_mode_switch_to_advanced_header_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.experience_mode_switch_to_advanced),
                subtitle = stringResource(R.string.experience_mode_switch_to_advanced_subtitle),
                value = stringResource(R.string.experience_mode_essential),
                onClick = { showConfirmation = true },
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
        }
    }

    if (showConfirmation) {
        ExperienceModeConfirmationDialog(
            targetMode = ExperienceMode.ADVANCED,
            onConfirm = { experienceModeViewModel.setMode(ExperienceMode.ADVANCED) },
            onDismiss = { showConfirmation = false }
        )
    }
}

@Composable
private fun AccountSettingsInline(
    onNavigateToAuthQrSignIn: () -> Unit,
    initialFocusRequester: FocusRequester?
) {
    val accountViewModel: com.fetch.tv.ui.screens.account.AccountViewModel = hiltViewModel()
    val accountUiState by accountViewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_account),
            subtitle = stringResource(R.string.settings_account_section_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            com.fetch.tv.ui.screens.account.AccountSettingsContent(
                uiState = accountUiState,
                viewModel = accountViewModel,
                onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
                initialFocusRequester = initialFocusRequester
            )
        }
    }
}

/**
 * Whether the item at [index] is on screen at all. Used to decide whether the rail has to be
 * scrolled before an item can be reached, not whether the item can take focus: being listed here
 * does not promise an attached requester, which is what the retry after the scroll is for.
 */
private fun LazyListState.isItemVisible(index: Int): Boolean =
    layoutInfo.visibleItemsInfo.any { it.index == index }

/** Whether [this] is one of the directional keys, so a press of it means the user is moving. */
private fun Key.isDirection(): Boolean =
    this == Key.DirectionUp ||
        this == Key.DirectionDown ||
        this == Key.DirectionLeft ||
        this == Key.DirectionRight
