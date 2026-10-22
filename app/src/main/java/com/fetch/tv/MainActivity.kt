package com.fetch.tv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.tv.material3.MaterialTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.fetch.tv.ui.screens.ambient.AmbientIdleController
import com.fetch.tv.ui.screens.ambient.AmbientModeHost
import com.fetch.tv.ui.screens.ambient.LocalAmbientIdleController
import com.fetch.tv.ui.components.LocalStartupLoadingState
import com.fetch.tv.ui.components.LocalStartupSplashEnabled
import com.fetch.tv.ui.components.StartupLoadingState
import com.fetch.tv.ui.components.FloatingSidebarButton
import com.fetch.tv.ui.components.StartupDestination
import com.fetch.tv.ui.components.shouldShowStartupSplash
import com.fetch.tv.ui.components.startupDestinationForRoute
import com.fetch.tv.core.runtime.PluginRuntimeHooks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.contentColorFor
import androidx.tv.material3.rememberDrawerState
import com.fetch.tv.R
import com.fetch.tv.core.auth.AuthManager
import com.fetch.tv.core.auth.DeviceSessionRegistration
import com.fetch.tv.core.deeplink.DeepLinkHandler
import com.fetch.tv.core.deeplink.DeepLinkParser
import com.fetch.tv.core.player.PlayerWindowBackdrop
import com.fetch.tv.core.party.PartyRepository
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.remote.RemoteCommand
import com.fetch.tv.core.remote.RemoteCommandBus
import com.fetch.tv.core.sync.ProfileSyncService
import com.fetch.tv.core.sync.StartupSyncService
import com.fetch.tv.core.tracking.TrackingProgressRefreshCoordinator
import com.fetch.tv.core.tracking.TrackingRefreshIntent
import com.fetch.tv.data.local.AppOnboardingDataStore
import com.fetch.tv.data.local.AuthSessionNoticeDataStore
import com.fetch.tv.data.local.ExperienceModeDataStore
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.StartupAuthNotice
import com.fetch.tv.data.local.ThemeDataStore
import com.fetch.tv.data.repository.MemberAccessRepository
import com.fetch.tv.data.remote.supabase.AvatarRepository
import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.CustomThemeColors
import com.fetch.tv.domain.model.AuthState
import com.fetch.tv.domain.model.CardDepthStyle
import com.fetch.tv.domain.model.CosmeticEntitlement
import com.fetch.tv.domain.model.DiscoverLocation
import com.fetch.tv.domain.model.ExperienceMode
import com.fetch.tv.domain.model.MemberAccess
import com.fetch.tv.domain.model.ProfileBackgroundSelection
import com.fetch.tv.domain.model.resolveProfileBackgroundSelection
import com.fetch.tv.domain.model.SettingsUiStyle
import com.fetch.tv.domain.model.resolveAppTheme
import com.fetch.tv.domain.model.resolveCustomThemeColors
import com.fetch.tv.domain.deeplink.AppDeepLink
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.ui.components.FetchScrollDefaults
import com.fetch.tv.ui.components.LocalCardDepthStyle
import com.fetch.tv.ui.components.LocalLandscapePosterMode
import com.fetch.tv.ui.components.LocalAlwaysBackdropWithLogo
import com.fetch.tv.ui.components.ProfileAvatarCircle
import com.fetch.tv.ui.navigation.FetchNavHost
import com.fetch.tv.ui.navigation.Screen
import com.fetch.tv.ui.screens.party.PartyEffects
import com.fetch.tv.ui.membership.LocalMemberAccess
import com.fetch.tv.ui.screens.account.AuthQrSignInScreen
import com.fetch.tv.ui.screens.addon.EssentialAddonSetupScreen
import com.fetch.tv.ui.screens.profile.ProfileSelectionScreen
import com.fetch.tv.ui.theme.FetchComponents
import com.fetch.tv.ui.theme.FetchLayout
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.brandWordmarkResource
import com.fetch.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.fetch.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.fetch.tv.ui.util.rememberDrawerItemFocusRequesters
import com.fetch.tv.ui.util.rememberRawSvgPainter
import com.fetch.tv.updater.UpdateViewModel
import com.fetch.tv.updater.ui.UpdateBannerHost
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val LocalSidebarExpanded = compositionLocalOf { false }
val LocalContentFocusRequester = compositionLocalOf { FocusRequester.Default }

data class SplashBackground(
    val profileColorHex: String? = null,
    val backgroundUrl: String? = null,
    val backgroundCacheKey: String? = null,
    val skipGradient: Boolean = false,
    val brandWordmarkRes: Int? = null
)
val LocalSplashBackground = compositionLocalOf { SplashBackground() }

private const val SIDEBAR_AUTO_COLLAPSE_DELAY_MS = 3_000L

private const val MAX_SUPPORTED_FONT_SCALE = 1.15f

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null
)

private data class MainUiPrefs(
    val theme: AppTheme = com.fetch.tv.domain.model.DEFAULT_APP_THEME,
    val customThemeColors: CustomThemeColors = CustomThemeColors.Default,
    val memberAccess: MemberAccess = MemberAccess.None,
    val font: AppFont = AppFont.SCHIBSTED_GROTESK,
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false,
    val hasChosenLayout: Boolean? = null,
    val experienceMode: ExperienceMode? = null,
    val experienceModeLoaded: Boolean = false,
    val addonSetupSkipped: Boolean = false,
    val sidebarCollapsed: Boolean = false,
    val discoverLocation: DiscoverLocation? = null,
    val smoothBringIntoViewEnabled: Boolean = true,
    val fastHorizontalNavigationEnabled: Boolean = false,
    val composeHighlighterEnabled: Boolean = false,
    val settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    val cardDepthStyle: CardDepthStyle = CardDepthStyle(),
    val landscapePosterMode: Boolean = false,
    val alwaysBackdropWithLogo: Boolean = false,
    val trailerCropToFill: Boolean = true
)

@AndroidEntryPoint
open class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var experienceModeDataStore: ExperienceModeDataStore

    @Inject
    lateinit var memberAccessRepository: MemberAccessRepository

    @Inject
    lateinit var addonRepository: AddonRepository

    @Inject
    lateinit var trackingProgressRefreshCoordinator: TrackingProgressRefreshCoordinator

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var androidTvChannelSyncService: com.fetch.tv.core.sync.androidtv.AndroidTvChannelSyncService

    @Inject
    lateinit var profileSyncService: ProfileSyncService

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    lateinit var profilePinManager: com.fetch.tv.core.parental.ProfilePinManager

    @Inject
    lateinit var parentalControls: com.fetch.tv.core.parental.ParentalControls

    @Inject
    lateinit var remoteCommandBus: RemoteCommandBus

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var deviceSessionRegistration: DeviceSessionRegistration

    @Inject
    lateinit var authSessionNoticeDataStore: AuthSessionNoticeDataStore

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @Inject
    lateinit var avatarRepository: AvatarRepository

    @Inject
    lateinit var profileBackgroundRepository: com.fetch.tv.data.remote.supabase.ProfileBackgroundRepository

    @Inject
    lateinit var trailerPlayerPool: com.fetch.tv.core.player.TrailerPlayerPool

    @Inject
    lateinit var externalPlaybackTracker: com.fetch.tv.core.player.ExternalPlaybackTracker

    @Inject
    lateinit var deepLinkHandler: DeepLinkHandler

    @Inject
    lateinit var partyRepository: PartyRepository

    private val pendingDeepLinkUrl = MutableStateFlow<String?>(null)
    private val pendingLaunchIntent = MutableStateFlow<Intent?>(null)

    private lateinit var jankStats: JankStats

    /** Activity-level launcher for external video players. Survives all navigation changes. */
    private val externalPlayerLauncher = registerForActivityResult(
        com.fetch.tv.core.player.ExternalPlayerResultContract()
    ) { result ->
        Log.d("MainActivity", "External player ActivityResult: $result")
        externalPlaybackTracker.onActivityResult(result)
    }

    /** True until the first onResume after onCreate completes. */
    private var isFirstResumeAfterCreate = false

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET }

        if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            // Cache not ready yet (very early cold start) — use system locale
            // The IO coroutine in Application.onCreate will finish before any activity
            // is usually created, but if not, we just use system locale until next launch
            super.attachBaseContext(newBase)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        isFirstResumeAfterCreate = true
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        // Wire the Activity-level launcher to the tracker
        externalPlaybackTracker.activityLauncher = externalPlayerLauncher

        PluginRuntimeHooks.onActivityCreate(this)

        window?.decorView?.post {
            val snapshot = com.fetch.tv.core.player.DisplayCapabilities.detect(this)
            com.fetch.tv.core.player.DisplayCapabilities.logSummary(snapshot)
        }

        // Extract extras set by the Continue Watching launcher channel preview programs.
        // A recreated activity (savedInstanceState != null, e.g. after the system killed the
        // app behind a screensaver) still carries the original launch intent. Replaying it would
        // navigate back to the episode the session started on instead of the restored one
        // (NuvioTV#3502), so only a fresh launch acts on it.
        val launchIntent = intent?.takeIf { savedInstanceState == null }
        val launchContentId = launchIntent?.getStringExtra("contentId")
        val launchContentType = launchIntent?.getStringExtra("contentType")
        val launchMode = launchIntent?.getStringExtra("launchMode")
        val launchVideoId = launchIntent?.getStringExtra("videoId")
        val launchName = launchIntent?.getStringExtra("name")
        val launchPoster = launchIntent?.getStringExtra("poster")
        val launchBackdrop = launchIntent?.getStringExtra("backdrop")
        val launchLogo = launchIntent?.getStringExtra("logo")
        val launchSeason = launchIntent?.getIntExtra("season", -1)?.takeIf { it >= 0 }
        val launchEpisode = launchIntent?.getIntExtra("episode", -1)?.takeIf { it >= 0 }
        val launchEpisodeTitle = launchIntent?.getStringExtra("episodeTitle")
        captureDeepLinkIntent(launchIntent)

        setContent {
            var hasSelectedProfileThisSession by rememberSaveable { mutableStateOf(false) }
            var startupSession by remember { mutableIntStateOf(0) }
            // Triggered immediately on profile click (before system confirms selection).
            var splashTriggered by remember { mutableStateOf(false) }
            // Overrides splash background with the currently focused profile
            // so the splash matches the profile selection background.
            var focusedSplashColor by remember { mutableStateOf<String?>(null) }
            var focusedSplashBgUrl by remember { mutableStateOf<String?>(null) }
            var focusedSplashCacheKey by remember { mutableStateOf<String?>(null) }
            var focusedSplashTheme by remember { mutableStateOf<AppTheme?>(null) }
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            var onboardingProfileSyncInProgress by remember { mutableStateOf(false) }
            val hasSeenAuthQrFlow = remember(appOnboardingDataStore) {
                appOnboardingDataStore.hasSeenAuthQrOnFirstLaunch.map<Boolean, Boolean?> { it }
            }
            val hasSeenAuthQrOnFirstLaunch by hasSeenAuthQrFlow.collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()
            val context = LocalContext.current

            LaunchedEffect(authSessionNoticeDataStore, context) {
                authSessionNoticeDataStore.pendingNotice.collect { notice ->
                    if (notice == StartupAuthNotice.FETCH) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.auth_notice_fetch_logged_out),
                            Toast.LENGTH_LONG
                        ).show()
                        authSessionNoticeDataStore.consumeNotice(notice)
                    }
                }
            }

            PartyEffects(partyRepository)

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                }
            }

            val activeProfileId by profileManager.activeProfileId.collectAsState()
            val startupSplashEnabled by profileManager.startupSplashEnabled.collectAsState()
            val startupLoadingState = remember(activeProfileId, startupSession) { StartupLoadingState() }
            val profiles by profileManager.profiles.collectAsState()
            val hasEverSelectedProfile by profileManager.hasEverSelectedProfile.collectAsState()
            val rememberLastProfileEnabled by profileManager.rememberLastProfileEnabled.collectAsState()
            val activeProfile = remember(activeProfileId, profiles) {
                profiles.firstOrNull { it.id == activeProfileId }
            }
            // Local PINs work without sync; the remote lock states are refreshed when signed in.
            // Null until read from disk, so a PIN-locked profile is never auto-entered early.
            val profilePinStates by profilePinManager.pinEnabled.collectAsState(initial = null)

            LaunchedEffect(authState, profiles) {
                if (authState is AuthState.FullAccount) {
                    profilePinManager.refreshRemoteStates()
                }
            }

            val activeProfileHasPin = remember(activeProfileId, profilePinStates) {
                profilePinStates?.get(activeProfileId) == true
            }

            var profileSwitchedManually by remember { mutableStateOf(false) }
            val shouldAutoSelectProfile = rememberLastProfileEnabled &&
                hasEverSelectedProfile && profilePinStates != null && !activeProfileHasPin &&
                !hasSelectedProfileThisSession && !profileSwitchedManually
            if (shouldAutoSelectProfile && !splashTriggered) {
                splashTriggered = true
                hasSelectedProfileThisSession = true
            }
            LaunchedEffect(shouldAutoSelectProfile) {
                if (shouldAutoSelectProfile) {
                    if (authManager.authState.value is AuthState.FullAccount) {
                        startupSyncService.requestSyncNow()
                    }
                    // Preload profile background catalog so the splash can show it
                    activeProfile?.profileBackgroundId?.let {
                        profileBackgroundRepository.loadSelectedAndPreload(it)
                    }
                }
            }

            var avatarCatalog by remember { mutableStateOf(emptyList<com.fetch.tv.data.remote.supabase.AvatarCatalogItem>()) }
            val avatarMemberAccess by memberAccessRepository.access.collectAsState()
            val hasProfileAvatarAccess = avatarMemberAccess.entitlements
                .includes(CosmeticEntitlement.PROFILE_AVATARS)

            LaunchedEffect(hasProfileAvatarAccess) {
                avatarCatalog = runCatching {
                    avatarRepository.getAvatarCatalog(hasProfileAvatarAccess)
                }
                    .getOrDefault(emptyList())
            }

            val activeProfileAvatarImageUrl = remember(activeProfile, avatarCatalog) {
                activeProfile?.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: activeProfile?.avatarId?.let { avatarRepository.getAvatarImageUrl(it, avatarCatalog) }
            }

            val mainUiPrefsFlow = remember(
                activeProfileId,
                startupSession,
                themeDataStore,
                layoutPreferenceDataStore,
                experienceModeDataStore,
                memberAccessRepository
            ) {
                val activeThemeFlow = combine(
                    themeDataStore.themeSelection,
                    memberAccessRepository.access
                ) { selection, memberAccess ->
                    selection.copy(
                        theme = resolveAppTheme(selection.theme, memberAccess.entitlements),
                        customColors = resolveCustomThemeColors(selection.customColors, memberAccess.tier)
                    ) to memberAccess
                }
                // Group flows into two batches to reduce intermediate flow allocations.
                // Each batch uses a single combine() instead of chaining .combine() calls,
                // which avoids N intermediate flow objects and redundant emissions on startup.
                val themeAndExperienceFlow = combine(
                    activeThemeFlow,
                    themeDataStore.selectedFont,
                    themeDataStore.amoledMode,
                    themeDataStore.amoledSurfacesMode,
                    experienceModeDataStore.mode,
                ) { themeAndAccess, font, amoledMode, amoledSurfacesMode, experienceMode ->
                    MainUiPrefs(
                        theme = themeAndAccess.first.theme ?: com.fetch.tv.domain.model.DEFAULT_APP_THEME,
                        customThemeColors = themeAndAccess.first.customColors,
                        memberAccess = themeAndAccess.second,
                        font = font,
                        amoledMode = amoledMode,
                        amoledSurfacesMode = amoledSurfacesMode,
                        experienceMode = experienceMode,
                        experienceModeLoaded = true,
                    )
                }
                val layoutAndFeaturesFlow = combine(
                    layoutPreferenceDataStore.hasChosenLayout,
                    layoutPreferenceDataStore.sidebarCollapsedByDefault,
                    layoutPreferenceDataStore.discoverLocation,
                ) { hasChosenLayout, sidebarCollapsed, discoverLocation ->
                    MainUiPrefs(
                        hasChosenLayout = hasChosenLayout,
                        sidebarCollapsed = sidebarCollapsed,
                        discoverLocation = discoverLocation,
                    )
                }
                val extraFeaturesFlow = combine(
                    experienceModeDataStore.addonSetupSkipped,
                    layoutPreferenceDataStore.smoothBringIntoViewEnabled,
                    layoutPreferenceDataStore.fastHorizontalNavigationEnabled,
                    layoutPreferenceDataStore.composeHighlighterEnabled,
                    themeDataStore.settingsUiStyle,
                ) { addonSetupSkipped, smoothBringIntoView, fastHorizontalNav, composeHighlighter, settingsUiStyle ->
                    MainUiPrefs(
                        addonSetupSkipped = addonSetupSkipped,
                        smoothBringIntoViewEnabled = smoothBringIntoView,
                        fastHorizontalNavigationEnabled = fastHorizontalNav,
                        composeHighlighterEnabled = composeHighlighter,
                        settingsUiStyle = settingsUiStyle,
                    )
                }
                val landscapePrefsFlow = combine(
                    layoutPreferenceDataStore.modernLandscapePostersEnabled,
                    layoutPreferenceDataStore.alwaysShowLandscapeClearlogo,
                    layoutPreferenceDataStore.trailerCropToFill
                ) { landscape, backdropWithLogo, trailerCrop -> Triple(landscape, backdropWithLogo, trailerCrop) }

                combine(
                    themeAndExperienceFlow,
                    layoutAndFeaturesFlow,
                    extraFeaturesFlow,
                    layoutPreferenceDataStore.cardDepthStyle,
                    landscapePrefsFlow
                ) { themePrefs, layoutPrefs, extraPrefs, cardDepthStyle, (landscapePosterMode, alwaysBackdropWithLogo, trailerCropToFill) ->
                    themePrefs.copy(
                        hasChosenLayout = layoutPrefs.hasChosenLayout,
                        sidebarCollapsed = layoutPrefs.sidebarCollapsed,
                        discoverLocation = layoutPrefs.discoverLocation,
                        addonSetupSkipped = extraPrefs.addonSetupSkipped,
                        smoothBringIntoViewEnabled = extraPrefs.smoothBringIntoViewEnabled,
                        fastHorizontalNavigationEnabled = extraPrefs.fastHorizontalNavigationEnabled,
                        composeHighlighterEnabled = extraPrefs.composeHighlighterEnabled,
                        settingsUiStyle = extraPrefs.settingsUiStyle,
                        cardDepthStyle = cardDepthStyle,
                        landscapePosterMode = landscapePosterMode,
                        alwaysBackdropWithLogo = alwaysBackdropWithLogo,
                        trailerCropToFill = trailerCropToFill
                    )
                }
            }
            val mainUiPrefs by key(activeProfileId, startupSession) {
                mainUiPrefsFlow.collectAsState(initial = MainUiPrefs(hasChosenLayout = null))
            }
            val installedAddons by key(activeProfileId, startupSession) {
                remember(addonRepository) {
                    addonRepository.getInstalledAddons()
                }.collectAsState(initial = null)
            }
            val discoverLocation = mainUiPrefs.discoverLocation

            FetchTheme(
                appTheme = mainUiPrefs.theme,
                customThemeColors = mainUiPrefs.customThemeColors,
                appFont = mainUiPrefs.font,
                amoledMode = mainUiPrefs.amoledMode,
                amoledSurfacesMode = mainUiPrefs.amoledSurfacesMode,
                settingsUiStyle = mainUiPrefs.settingsUiStyle
            ) {
                val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
                val bringIntoViewSpec = if (mainUiPrefs.smoothBringIntoViewEnabled) {
                    FetchScrollDefaults.smoothScrollSpec
                } else {
                    defaultBringIntoViewSpec
                }
                val systemDensity = LocalDensity.current
                val clampedFontScaleDensity = remember(systemDensity) {
                    Density(
                        density = systemDensity.density,
                        fontScale = systemDensity.fontScale.coerceAtMost(MAX_SUPPORTED_FONT_SCALE)
                    )
                }
                val highlighterEnabled = BuildConfig.IS_DEBUG_BUILD && mainUiPrefs.composeHighlighterEnabled
                com.fetch.tv.ui.util.RecompositionHighlighterFlag.enabled = highlighterEnabled
                val profileBgSelection = resolveProfileBackgroundSelection(
                    profile = activeProfile,
                    entitlements = mainUiPrefs.memberAccess.entitlements
                )
                val profileBgCatalog by profileBackgroundRepository.catalog.collectAsState()
                // Cache splash background in SharedPreferences so the splash
                // can show the correct background on cold start when
                // "remember last profile" skips the profile selection screen.
                val splashPrefs = remember {
                    context.getSharedPreferences("startup_splash", android.content.Context.MODE_PRIVATE)
                }
                val splashPreferencesReady = mainUiPrefs.hasChosenLayout != null && mainUiPrefs.experienceModeLoaded
                val splashBackground = remember(
                    profileBgSelection, profileBgCatalog, activeProfile, activeProfileId,
                    focusedSplashColor, focusedSplashBgUrl, focusedSplashCacheKey,
                    focusedSplashTheme, splashPreferencesReady, mainUiPrefs.theme
                ) {
                    val bgUrl = when (profileBgSelection) {
                        is ProfileBackgroundSelection.Custom -> profileBgSelection.url
                        is ProfileBackgroundSelection.Catalog -> {
                            profileBgCatalog.firstOrNull { it.id == profileBgSelection.id }
                                ?.imageFile?.toURI()?.toString()
                        }
                        else -> null
                    }
                    val colorHex = activeProfile?.avatarColorHex
                    // Only overwrite bg_url when we have a real value or when
                    // we know the profile has no background at all.
                    if (splashPreferencesReady && colorHex != null) {
                        splashPrefs.edit().putString("color_$activeProfileId", colorHex).apply()
                    }
                    if (splashPreferencesReady && bgUrl != null) {
                        splashPrefs.edit()
                            .putString("bg_url_$activeProfileId", bgUrl)
                            .apply()
                    } else if (splashPreferencesReady && profileBgSelection == null && activeProfile != null) {
                        splashPrefs.edit()
                            .remove("bg_url_$activeProfileId")
                            .apply()
                    }
                    val activeTheme = mainUiPrefs.theme
                    if (splashPreferencesReady && activeTheme != com.fetch.tv.domain.model.DEFAULT_APP_THEME) {
                        splashPrefs.edit().putString("theme_$activeProfileId", activeTheme.name).apply()
                    } else if (splashPreferencesReady) {
                        splashPrefs.edit().remove("theme_$activeProfileId").apply()
                    }
                    // Fall back to cached values only on cold start (no focused override)
                    val hasFocusedOverride = focusedSplashColor != null
                    val fallbackColor = if (colorHex == null && !hasFocusedOverride) splashPrefs.getString("color_$activeProfileId", null) else null
                    val fallbackBg = if (
                        bgUrl == null && !hasFocusedOverride && (!splashPreferencesReady || profileBgSelection != null)
                    ) splashPrefs.getString("bg_url_$activeProfileId", null) else null
                    val resolvedBgUrl = if (hasFocusedOverride) focusedSplashBgUrl else (bgUrl ?: fallbackBg)
                    val skipGradient = !hasFocusedOverride && resolvedBgUrl == null &&
                        profileBgSelection is ProfileBackgroundSelection.Catalog
                    // Resolve branded logo: focused theme (profile screen) > active theme > cached theme > default
                    val resolvedTheme = focusedSplashTheme
                        ?: activeTheme.takeIf { splashPreferencesReady }
                        ?: splashPrefs.getString("theme_$activeProfileId", null)
                            ?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
                        ?: com.fetch.tv.domain.model.DEFAULT_APP_THEME
                    SplashBackground(
                        profileColorHex = if (hasFocusedOverride) focusedSplashColor else (colorHex ?: fallbackColor),
                        backgroundUrl = resolvedBgUrl,
                        backgroundCacheKey = if (hasFocusedOverride) focusedSplashCacheKey else when (profileBgSelection) {
                            is ProfileBackgroundSelection.Catalog -> profileBgCatalog
                                .firstOrNull { it.id == profileBgSelection.id }
                                ?.let { "profile-background-${it.id}-v${it.assetVersion}" }
                            is ProfileBackgroundSelection.Custom -> "custom-profile-background-${profileBgSelection.url}"
                            null -> null
                        },
                        skipGradient = skipGradient,
                        brandWordmarkRes = resolvedTheme.brandWordmarkResource
                    )
                }
                CompositionLocalProvider(
                    LocalDensity provides clampedFontScaleDensity,
                    LocalBringIntoViewSpec provides bringIntoViewSpec,
                    LocalFastHorizontalNavigationEnabled provides mainUiPrefs.fastHorizontalNavigationEnabled,
                    LocalRecompositionHighlighterEnabled provides highlighterEnabled,
                    LocalCardDepthStyle provides mainUiPrefs.cardDepthStyle,
                    LocalLandscapePosterMode provides mainUiPrefs.landscapePosterMode,
                    LocalAlwaysBackdropWithLogo provides mainUiPrefs.alwaysBackdropWithLogo,
                    com.fetch.tv.ui.components.LocalTrailerCropToFill provides mainUiPrefs.trailerCropToFill,
                    LocalMemberAccess provides mainUiPrefs.memberAccess,
                    LocalAmbientIdleController provides ambientIdleController,
                    com.fetch.tv.core.player.LocalTrailerPlayerPool provides trailerPlayerPool,
                    LocalSplashBackground provides splashBackground,
                    LocalStartupLoadingState provides startupLoadingState,
                    LocalStartupSplashEnabled provides startupSplashEnabled
                ) {
                val transparentPlayerBackdrop = PlayerWindowBackdrop.isTransparentRequested
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = if (transparentPlayerBackdrop) {
                            Color.Transparent
                        } else {
                            FetchTheme.colors.Background
                        },
                        contentColor = contentColorFor(FetchTheme.colors.Background)
                    )
                ) {
                    // Wrap everything in a Box. This prevents any black flash between
                    // profile selection and the home content
                    Box(modifier = Modifier.fillMaxSize()) {

                    var startupDestination = StartupDestination.Loading
                    val surfaceContentReady = hasSeenAuthQrOnFirstLaunch != null &&
                        authState !is AuthState.Loading &&
                        profilePinStates != null

                    if (!surfaceContentReady) {
                        // Still loading auth state; nothing to show yet.
                    } else if (
                        hasSeenAuthQrOnFirstLaunch == false &&
                        authState !is AuthState.FullAccount &&
                        !onboardingCompletedThisSession
                    ) {
                        startupDestination = StartupDestination.Setup
                        AuthQrSignInScreen(
                            onBackPress = { finish() },
                            onContinue = {
                                lifecycleScope.launch {
                                    val shouldRunRemoteOnboardingSync =
                                        authManager.authState.value is AuthState.FullAccount

                                    if (shouldRunRemoteOnboardingSync) {
                                        if (onboardingProfileSyncInProgress) return@launch
                                        onboardingProfileSyncInProgress = true
                                        val maxAttempts = 3
                                        var synced = false
                                        for (attempt in 0 until maxAttempts) {
                                            val result = profileSyncService.pullFromRemote()
                                            if (result.isSuccess) {
                                                synced = true
                                                break
                                            }
                                            if (attempt < maxAttempts - 1) {
                                                delay(1_000)
                                            }
                                        }
                                        if (!synced) {
                                            android.util.Log.w(
                                                "MainActivity",
                                                "Onboarding profile sync failed after retries; continuing"
                                            )
                                        }
                                    }
                                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                                    onboardingCompletedThisSession = true
                                    onboardingProfileSyncInProgress = false
                                }
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                    } else {

                    val shouldShowProfileSelection =
                        !hasSelectedProfileThisSession && (profiles.size > 1 || activeProfileHasPin)

                    if (shouldShowProfileSelection) {
                        startupDestination = if (splashTriggered) StartupDestination.Loading else StartupDestination.ProfileSelection
                        ProfileSelectionScreen(
                            onProfileClicked = {
                                splashTriggered = true
                            },
                            onProfileSelectionFailed = {
                                splashTriggered = false
                            },
                            onProfileFocusChanged = { colorHex, bgUrl, cacheKey ->
                                if (!splashTriggered) {
                                    focusedSplashColor = colorHex
                                    focusedSplashBgUrl = bgUrl
                                    focusedSplashCacheKey = cacheKey
                                }
                            },
                            onProfileThemeFocused = { theme ->
                                if (!splashTriggered) {
                                    focusedSplashTheme = theme
                                }
                            },
                            onProfileSelected = {
                                hasSelectedProfileThisSession = true
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                    } else {

                    val layoutChosen = mainUiPrefs.hasChosenLayout
                    val prefsLoading = layoutChosen == null || !mainUiPrefs.experienceModeLoaded || installedAddons == null
                    if (!prefsLoading) {
                    val effectiveExperienceMode = mainUiPrefs.experienceMode
                        ?: if (layoutChosen) ExperienceMode.ADVANCED else null
                    val needsExperienceSelection = effectiveExperienceMode == null
                    val needsEssentialAddonSetup =
                        effectiveExperienceMode == ExperienceMode.ESSENTIAL &&
                            installedAddons.orEmpty().isEmpty() &&
                            !mainUiPrefs.addonSetupSkipped
                    val pendingDeepLink by pendingDeepLinkUrl.collectAsState()

                    LaunchedEffect(pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        val deepLink = DeepLinkParser.parse(url)
                        if (deepLink is AppDeepLink.AddonInstall && (needsEssentialAddonSetup || !layoutChosen) &&
                            !parentalControls.activeRestrictions.value.isRestricted
                        ) {
                            Toast.makeText(context, context.getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
                            val installResult = deepLinkHandler.installAddon(deepLink.manifestUrl)
                            if (pendingDeepLinkUrl.value == url) {
                                pendingDeepLinkUrl.value = null
                            }
                            Toast.makeText(context, installResult.message, Toast.LENGTH_LONG).show()
                        }
                    }

                    if (needsEssentialAddonSetup) {
                        startupDestination = StartupDestination.Setup
                        EssentialAddonSetupScreen(
                            onSkip = {
                                lifecycleScope.launch {
                                    experienceModeDataStore.setAddonSetupSkipped(true)
                                }
                            }
                        )
                    } else {
                    val sidebarCollapsed = mainUiPrefs.sidebarCollapsed

                    val startDestination = if (needsExperienceSelection) {
                        Screen.ExperienceModeSelection.route
                    } else {
                        Screen.Home.route
                    }
                    val navController = rememberNavController()
                    var optimisticRoute by remember { mutableStateOf<String?>(null) }
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val actualRoute = navBackStackEntry?.destination?.route
                    val currentRoute = optimisticRoute ?: actualRoute
                    startupDestination = startupDestinationForRoute(actualRoute ?: startDestination)

                    LaunchedEffect(actualRoute) {
                        optimisticRoute = null
                        ambientIdleController.onInteraction()
                    }

                    // Auto-play next episode for EXTERNAL players: the tracker resolves the
                    // next episode and we navigate into the same Screen.Stream auto-play route
                    // the internal onPlaybackEnded path uses. Collected from the root composable
                    // so it survives StreamScreen's self-pop and a process kill (metadata is
                    // recovered from disk and the event replayed).
                    LaunchedEffect(navController) {
                        externalPlaybackTracker.autoPlayNext.collect { next ->
                            if (!externalPlaybackTracker.claimAutoPlayNextNavigation(next)) {
                                return@collect
                            }
                            Log.d(
                                "MainActivity",
                                "autoPlayNext received: S${next.nextSeason}E${next.nextEpisode} " +
                                    "videoId=${next.nextVideoId}; navigating to Stream"
                            )
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = next.nextVideoId,
                                    contentType = next.contentType,
                                    title = next.contentName,
                                    poster = next.poster,
                                    backdrop = next.backdrop,
                                    logo = next.logo,
                                    season = next.nextSeason,
                                    episode = next.nextEpisode,
                                    year = next.year,
                                    contentId = next.contentId,
                                    contentName = next.contentName,
                                    returnToDetailOnBack = next.contentType.equals("series", ignoreCase = true),
                                    profileId = next.profileId
                                )
                            ) {
                                // Replace any lingering Stream screen (e.g. the previous
                                // episode's, restored from the backstack after a process restart).
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    // Navigate to content when launched from the Continue Watching channel row.
                    LaunchedEffect(navController) {
                        if (launchContentId != null && launchContentType != null && layoutChosen) {
                            if (launchMode == "stream" && launchVideoId != null && launchName != null) {
                                navController.navigate(
                                    Screen.Stream.createRoute(
                                        videoId = launchVideoId,
                                        contentType = launchContentType,
                                        title = launchName,
                                        poster = launchPoster,
                                        backdrop = launchBackdrop,
                                        logo = launchLogo,
                                        season = launchSeason,
                                        episode = launchEpisode,
                                        episodeName = launchEpisodeTitle,
                                        contentId = launchContentId,
                                        contentName = launchName,
                                        returnToDetailOnBack = launchContentType.equals("series", ignoreCase = true),
                                        returnToHomeOnBack = true
                                    )
                                )
                            } else {
                                navController.navigate(
                                    Screen.Detail.createRoute(
                                        itemId = launchContentId,
                                        itemType = launchContentType
                                    )
                                )
                            }
                        }
                    }

                    val pendingLaunch by pendingLaunchIntent.collectAsState()
                    LaunchedEffect(navController, layoutChosen, pendingLaunch) {
                        val intent = pendingLaunch ?: return@LaunchedEffect
                        if (!layoutChosen) return@LaunchedEffect
                        pendingLaunchIntent.value = null
                        val contentId = intent.getStringExtra("contentId") ?: return@LaunchedEffect
                        val contentType = intent.getStringExtra("contentType") ?: return@LaunchedEffect
                        val videoId = intent.getStringExtra("videoId")
                        val name = intent.getStringExtra("name")
                        if (videoId != null && name != null) {
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = videoId,
                                    contentType = contentType,
                                    title = name,
                                    poster = intent.getStringExtra("poster"),
                                    backdrop = intent.getStringExtra("backdrop"),
                                    logo = intent.getStringExtra("logo"),
                                    season = intent.getIntExtra("season", -1).takeIf { it >= 0 },
                                    episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 },
                                    episodeName = intent.getStringExtra("episodeTitle"),
                                    contentId = contentId,
                                    contentName = name,
                                    returnToDetailOnBack = contentType.equals("series", ignoreCase = true),
                                    returnToHomeOnBack = true
                                )
                            )
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = contentId,
                                    itemType = contentType
                                )
                            )
                        }
                    }

                    LaunchedEffect(navController) {
                        // Phone remote: keys and text become key events in this window only.
                        remoteCommandBus.commands.collect { command ->
                            when (command) {
                                is RemoteCommand.Key -> injectRemoteKey(command.key.keyCode)
                                is RemoteCommand.Text -> injectRemoteText(command.text)
                                is RemoteCommand.Search -> navController.navigate(Screen.Search.route) {
                                    launchSingleTop = true
                                }
                            }
                        }
                    }

                    LaunchedEffect(navController, layoutChosen, pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        if (!layoutChosen) return@LaunchedEffect
                        when (val deepLink = DeepLinkParser.parse(url)) {
                            is AppDeepLink.Meta -> {
                                pendingDeepLinkUrl.value = null
                                navController.navigate(
                                    Screen.Detail.createRoute(
                                        itemId = deepLink.id,
                                        itemType = deepLink.type
                                    )
                                ) {
                                    launchSingleTop = true
                                }
                            }
                            is AppDeepLink.Party -> {
                                pendingDeepLinkUrl.value = null
                                navController.navigate(Screen.WatchParty.createRoute(deepLink.code)) {
                                    launchSingleTop = true
                                }
                            }
                            is AppDeepLink.AddonInstall -> if (parentalControls.activeRestrictions.value.isRestricted) {
                                // Restricted profiles can't install addons, even from a link.
                                pendingDeepLinkUrl.value = null
                                Toast.makeText(context, context.getString(R.string.parental_addons_blocked), Toast.LENGTH_LONG).show()
                            } else {
                                navController.navigate(Screen.AddonManager.route) {
                                    launchSingleTop = true
                                }
                                Toast.makeText(context, context.getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
                                val installResult = deepLinkHandler.installAddon(deepLink.manifestUrl)
                                if (pendingDeepLinkUrl.value == url) {
                                    pendingDeepLinkUrl.value = null
                                }
                                Toast.makeText(context, installResult.message, Toast.LENGTH_LONG).show()
                            }
                            null -> {
                                pendingDeepLinkUrl.value = null
                            }
                        }
                    }

                    val view = LocalView.current
                    LaunchedEffect(currentRoute) {
                        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                        if (currentRoute != null) {
                            holder.state?.putState("Screen", currentRoute)
                        }
                    }

                    LaunchedEffect(discoverLocation, currentRoute) {
                        if (discoverLocation == null) return@LaunchedEffect
                        val onDiscoverRoute = currentRoute == Screen.Discover.route ||
                            currentRoute?.startsWith("${Screen.Discover.route}/") == true
                        if (discoverLocation == DiscoverLocation.OFF && onDiscoverRoute) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = false }
                                launchSingleTop = true
                            }
                        }
                    }

                    val rootRoutes = remember(discoverLocation) {
                        buildSet {
                            add(Screen.Home.route)
                            add(Screen.Search.route)
                            add(Screen.Library.route)
                            add(Screen.Settings.route)
                            if (discoverLocation == DiscoverLocation.IN_SIDEBAR) {
                                add(Screen.Discover.route)
                            }
                        }
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavDiscover = stringResource(R.string.nav_discover)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val drawerItems = remember(
                        strNavHome,
                        strNavDiscover,
                        strNavSearch,
                        strNavLibrary,
                        strNavSettings,
                        discoverLocation
                    ) {
                        buildList {
                            add(
                                DrawerItem(
                                    route = Screen.Home.route,
                                    label = strNavHome,
                                    icon = Icons.Default.Home
                                )
                            )
                            if (discoverLocation == DiscoverLocation.IN_SIDEBAR) {
                                add(
                                    DrawerItem(
                                        route = Screen.Discover.route,
                                        label = strNavDiscover,
                                        icon = Icons.Default.Explore
                                    )
                                )
                            }
                            add(
                                DrawerItem(
                                    route = Screen.Search.route,
                                    label = strNavSearch,
                                    iconRes = R.raw.sidebar_search
                                )
                            )
                            add(
                                DrawerItem(
                                    route = Screen.Library.route,
                                    label = strNavLibrary,
                                    iconRes = R.raw.sidebar_library
                                )
                            )
                            add(
                                DrawerItem(
                                    route = Screen.Settings.route,
                                    label = strNavSettings,
                                    iconRes = R.raw.sidebar_settings
                                )
                            )
                        }
                    }
                    val selectedDrawerRoute = drawerItems.firstOrNull { item ->
                        currentRoute == item.route || currentRoute?.startsWith("${item.route}/") == true
                    }?.route
                    val selectedDrawerItem = drawerItems.firstOrNull { it.route == selectedDrawerRoute } ?: drawerItems.first()

                    val confirmExitEnabled by profileManager.confirmExitEnabled.collectAsState()
                    var backPressedOnce by remember { mutableStateOf(false) }
                    LaunchedEffect(backPressedOnce) {
                        if (backPressedOnce) {
                            delay(2000L)
                            backPressedOnce = false
                        }
                    }
                    val handleExitApp: () -> Unit = {
                        if (!confirmExitEnabled || backPressedOnce) {
                            finishAffinity()
                            finishAndRemoveTask()
                            if (confirmExitEnabled) {
                                // Kill the process to free RAM on low-memory devices.
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        } else {
                            backPressedOnce = true
                            Toast.makeText(this@MainActivity, getString(R.string.confirm_exit_toast), Toast.LENGTH_SHORT).show()
                        }
                    }

                    val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
                    val updateState by updateViewModel.uiState.collectAsState()
                    val updateBannerState = updateState.copy(
                        showBanner = updateState.showBanner && currentRoute?.startsWith("player/") != true
                    )

                    UpdateBannerHost(
                        state = updateBannerState,
                        onDismissBanner = updateViewModel::dismissBanner,
                        onDownload = updateViewModel::downloadUpdate,
                        onInstall = updateViewModel::installUpdateOrRequestPermission,
                        onDismissUnknownSources = updateViewModel::dismissUnknownSourcesDialog,
                        onOpenUnknownSources = updateViewModel::openUnknownSourcesSettings,
                        onFeedbackShown = updateViewModel::consumeFeedbackMessage
                    ) {
                        val switchProfileNow = {
                            startupSession++
                            splashTriggered = false
                            profileSwitchedManually = true
                            focusedSplashColor = null
                            focusedSplashBgUrl = null
                            focusedSplashCacheKey = null
                            focusedSplashTheme = null
                            hasSelectedProfileThisSession = false
                        }
                        com.fetch.tv.ui.screens.profile.ParentalGateHost {
                        // Leaving a restricted profile needs the parent PIN.
                        val parentalGate = com.fetch.tv.ui.screens.profile.LocalParentalGate.current
                        val handleSwitchProfile: () -> Unit = { parentalGate.runGuarded(switchProfileNow) }
                        Box(modifier = Modifier.fillMaxSize()) {
                            FetchSidebarScaffold(
                                longPressBackHeld = longPressBackHeld,
                                navController = navController,
                                startDestination = startDestination,
                                currentRoute = currentRoute,
                                rootRoutes = rootRoutes,
                                drawerItems = drawerItems,
                                selectedDrawerRoute = selectedDrawerRoute,
                                sidebarCollapsed = sidebarCollapsed,
                                hideBuiltInHeaders = false,
                                activeProfileName = activeProfile?.name ?: "",
                                activeProfileColorHex = activeProfile?.avatarColorHex ?: "#1E88E5",
                                activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                                showProfileSelector = profiles.size > 1,
                                onSwitchProfile = handleSwitchProfile,
                                onNavigate = { optimisticRoute = it },
                                onExitApp = handleExitApp
                            )

                            val autoNextOverlay by externalPlaybackTracker.autoNextOverlay.collectAsState()
                            autoNextOverlay?.let { ov ->
                                com.fetch.tv.ui.screens.player.LoadingOverlay(
                                    visible = true,
                                    backdropUrl = ov.backdrop,
                                    logoUrl = ov.logo,
                                    title = ov.title,
                                    message = ov.message ?: stringResource(R.string.external_auto_next_loading),
                                    progress = ov.progress,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            val isTrailerPlaying by trailerPlayerPool.isTrailerPlaying.collectAsState()
                            LaunchedEffect(isTrailerPlaying) {
                                if (!isTrailerPlaying) ambientIdleController.onInteraction()
                            }
                            AmbientModeHost(
                                controller = ambientIdleController,
                                allowed = currentRoute?.startsWith("player/") != true &&
                                    autoNextOverlay == null &&
                                    !isTrailerPlaying,
                                onShown = { trailerPlayerPool.stop() }
                            )
                        }
                        }
                    }
                } 
            } 
        } 
    }

                    LaunchedEffect(startupDestination, startupLoadingState) {
                        if (startupDestination == StartupDestination.Content) {
                            startupLoadingState.complete = true
                        }
                    }
                    val showSplash = shouldShowStartupSplash(
                        enabled = startupSplashEnabled,
                        complete = startupLoadingState.complete,
                        destination = startupDestination
                    )
                    val animatedSplashAlpha by animateFloatAsState(
                        targetValue = if (showSplash) 1f else 0f,
                        animationSpec = tween(if (showSplash) 0 else 400),
                        label = "startupSplashAlpha"
                    )
                    val splashAlpha = if (showSplash) 1f else animatedSplashAlpha
                    LaunchedEffect(splashAlpha, startupDestination) {
                        if (splashAlpha == 0f && startupDestination != StartupDestination.ProfileSelection) {
                            focusedSplashColor = null
                            focusedSplashBgUrl = null
                            focusedSplashCacheKey = null
                            focusedSplashTheme = null
                        }
                    }
                    if (splashAlpha > 0f) {
                        com.fetch.tv.ui.components.StartupSplashScreen(
                            profileColorHex = splashBackground.profileColorHex,
                            profileBackgroundUrl = splashBackground.backgroundUrl,
                            backgroundCacheKey = splashBackground.backgroundCacheKey,
                            skipGradient = splashBackground.skipGradient,
                            brandWordmarkRes = splashBackground.brandWordmarkRes,
                            modifier = Modifier.graphicsLayer { alpha = splashAlpha }
                        )
                    }
                    }
                }
                }
            }
        }

        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (frameData.isJank) {
                Log.w(
                    "JankStats",
                    "JANK: ${frameData.frameDurationUiNanos / 1_000_000}ms | states: ${frameData.states}"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ambientIdleController.dismiss()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = true
        memberAccessRepository.refreshIfStale()
        lifecycleScope.launch {
            deviceSessionRegistration.requestForegroundRegistration()
            startupSyncService.requestForegroundSync()
        }
        lifecycleScope.launch {
            val refreshIntent = if (isFirstResumeAfterCreate) {
                isFirstResumeAfterCreate = false
                TrackingRefreshIntent.INVALIDATED
            } else {
                TrackingRefreshIntent.AUTOMATIC
            }
            trackingProgressRefreshCoordinator.refreshConnected(refreshIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLinkIntent(intent)
        captureLaunchIntent(intent)
    }

    private fun captureDeepLinkIntent(intent: Intent?) {
        val url = intent?.dataString?.trim()?.takeIf(String::isNotBlank) ?: return
        pendingDeepLinkUrl.value = url
    }

    private fun captureLaunchIntent(intent: Intent?) {
        val contentId = intent?.getStringExtra("contentId") ?: return
        val launchMode = intent.getStringExtra("launchMode") ?: return
        if (launchMode != "stream") return
        pendingLaunchIntent.value = intent
    }

    override fun onPause() {
        super.onPause()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    // Intercept Back at the Activity level, before any Compose BackHandler, so the auto-next loader
    // can always be dismissed. Compose back-dispatch ordering kept putting the destination screen's
    // handler above the loader's, so Back never reached it.
    // Tracks whether a long-press Back sequence is in progress. When true, all Back
    // key events are consumed at the Activity level so that repeated DOWN events from a
    // held Back key don't cascade through Compose BackHandlers (e.g. opening the sidebar
    // and then immediately exiting the app).
    val longPressBackHeld = mutableStateOf(false)

    private val ambientIdleController = AmbientIdleController()

    override fun onUserInteraction() {
        super.onUserInteraction()
        ambientIdleController.onInteraction()
    }

    private fun injectRemoteKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** Types [text] into the focused field. Characters the virtual keymap can't produce are skipped. */
    private fun injectRemoteText(text: String) {
        val keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        text.forEach { character ->
            keyMap.getEvents(charArrayOf(character))?.forEach { dispatchKeyEvent(it) }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ambientIdleController.consumeKeyEvent(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (longPressBackHeld.value) {
                if (event.action == KeyEvent.ACTION_UP) longPressBackHeld.value = false
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            externalPlaybackTracker.autoNextOverlay.value != null
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                Log.d("ExtAutoNext", "dispatchKeyEvent BACK -> dismissAutoNextOverlay (loader showing)")
                externalPlaybackTracker.dismissAutoNextOverlay()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        // Returning from an external player: raise the auto-next loader before the player's
        // result is dispatched and before the window repaints, so the transition shows the
        // loader instantly with no episode-list flash. No-op unless a series episode is being
        // tracked; onActivityResult keeps it for a completion or dismisses it otherwise.
        externalPlaybackTracker.raiseAutoNextOverlayOnReturn()
        super.onStart()
        startupSyncService.startPeriodicSurfacePulls()
        androidTvChannelSyncService.onForegroundChanged(true)
    }

    override fun onStop() {
        externalPlaybackTracker.onExternalPlayerCoveredApp()
        super.onStop()
        startupSyncService.stopPeriodicSurfacePulls()
        // App going to background (e.g. user returning to the launcher): reconcile the
        // Continue Watching channel once so Projectivy repaints it with fresh progress.
        androidTvChannelSyncService.onForegroundChanged(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        PluginRuntimeHooks.onActivityDestroy()
    }
}

@Composable
private fun SidebarFocusRecoveryEffect(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    drawerItemFocusRequesters: Map<String, FocusRequester>,
    sidebarOwnsFocus: Boolean
) {
    LaunchedEffect(drawerItems, sidebarOwnsFocus, selectedDrawerRoute) {
        if (!sidebarOwnsFocus) {
            return@LaunchedEffect
        }
        if (selectedDrawerRoute != null && drawerItems.any { it.route == selectedDrawerRoute }) {
            return@LaunchedEffect
        }
        val fallbackRoute = drawerItems.firstOrNull()?.route ?: return@LaunchedEffect
        val requester = drawerItemFocusRequesters[fallbackRoute] ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
/**
 * Fetch's sidebar: a column of round icons that floats over the left edge of the root screens.
 * The current page keeps an accent fill; when focus moves in, the focused icon shows its label
 * beside it. Back or Left opens it, Right or picking a page closes it.
 */
@Composable
private fun FetchSidebarScaffold(
    longPressBackHeld: MutableState<Boolean>,
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    sidebarCollapsed: Boolean,
    hideBuiltInHeaders: Boolean,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerItemFocusRequesters = rememberDrawerItemFocusRequesters(drawerItems)
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val showSidebar = currentRoute in rootRoutes

    LaunchedEffect(currentRoute) {
        longPressBackHeld.value = false
        drawerState.setValue(DrawerValue.Closed)
    }

    val sidebarTokens = FetchComponents.tokens.sidebar
    val closedDrawerWidth = if (sidebarCollapsed) FetchTheme.spacing.none else sidebarTokens.collapsedWidth
    val openDrawerWidth = sidebarTokens.expandedWidth

    val focusManager = LocalFocusManager.current
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val contentFocusRequester = remember { FocusRequester() }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    // Bumped on every key event the drawer sees so the auto-collapse timer
    // resets while the user navigates between drawer items.
    var legacyDrawerInteractionVersion by remember { mutableStateOf(0) }


    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Closed) {
        pendingSidebarFocusRequest = true
        drawerState.setValue(DrawerValue.Open)
    }

    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Open) {
        if (longPressBackHeld.value) return@BackHandler
        onExitApp()
    }

    LaunchedEffect(drawerState.currentValue, pendingContentFocusTransfer) {
        if (!pendingContentFocusTransfer || drawerState.currentValue != DrawerValue.Closed) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequester.requestFocus() }
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(drawerState.currentValue, selectedDrawerRoute, showSidebar, pendingSidebarFocusRequest) {
        if (!showSidebar || !pendingSidebarFocusRequest || drawerState.currentValue != DrawerValue.Open) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: drawerItems.firstOrNull()?.route ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    SidebarFocusRecoveryEffect(
        drawerItems = drawerItems,
        selectedDrawerRoute = selectedDrawerRoute,
        drawerItemFocusRequesters = drawerItemFocusRequesters,
        sidebarOwnsFocus = showSidebar && drawerState.currentValue == DrawerValue.Open
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        // A soft fade from the left edge instead of a panel, so the icons and labels stay readable
        // over artwork while the page itself runs edge to edge.
        scrimBrush = Brush.horizontalGradient(
            0f to FetchTheme.colors.Background.copy(alpha = 0.92f),
            0.35f to FetchTheme.colors.Background.copy(alpha = 0.55f),
            1f to Color.Transparent
        ),
        drawerContent = { drawerValue ->
            if (showSidebar) {
                val isExpanded = drawerValue == DrawerValue.Open
                val drawerWidth = if (isExpanded) openDrawerWidth else closedDrawerWidth
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .background(
                            Brush.horizontalGradient(
                                0f to FetchTheme.colors.Background.copy(alpha = if (isExpanded) 0f else 0.85f),
                                1f to Color.Transparent
                            )
                        )
                        .selectableGroup()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                legacyDrawerInteractionVersion++
                            }
                            val closeKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                            if (keyEvent.key == closeKey && keyEvent.type == KeyEventType.KeyDown) {
                                drawerState.setValue(DrawerValue.Closed)
                                pendingContentFocusTransfer = false
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = sidebarTokens.floatingInset),
                        verticalArrangement = Arrangement.spacedBy(sidebarTokens.floatingGap),
                        horizontalAlignment = Alignment.Start
                    ) {
                        drawerItems.forEach { item ->
                            key(item.route) {
                                FloatingSidebarButton(
                                    label = item.label,
                                    selected = selectedDrawerRoute == item.route,
                                    expanded = isExpanded,
                                    onClick = {
                                        keyboardController?.hide()
                                        onNavigate(item.route)
                                        navigateToDrawerRoute(
                                            navController = navController,
                                            currentRoute = currentRoute,
                                            targetRoute = item.route
                                        )
                                        drawerState.setValue(DrawerValue.Closed)
                                        pendingContentFocusTransfer = currentRoute == item.route
                                    },
                                    modifier = Modifier.focusRequester(
                                        drawerItemFocusRequesters.getValue(item.route)
                                    )
                                ) { tint ->
                                    DrawerItemIcon(
                                        iconRes = item.iconRes,
                                        icon = item.icon,
                                        tint = tint,
                                        brush = null,
                                        modifier = Modifier.size(sidebarTokens.iconSize)
                                    )
                                }
                            }
                        }
                        if (showProfileSelector && activeProfileName.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(sidebarTokens.floatingGap))
                            FloatingSidebarButton(
                                label = activeProfileName,
                                selected = false,
                                expanded = isExpanded,
                                onClick = {
                                    onSwitchProfile()
                                    drawerState.setValue(DrawerValue.Closed)
                                }
                            ) { _ ->
                                ProfileAvatarCircle(
                                    name = activeProfileName,
                                    colorHex = activeProfileColorHex,
                                    size = sidebarTokens.floatingButtonSize - FetchTheme.spacing.xs,
                                    avatarImageUrl = activeProfileAvatarImageUrl,
                                    imageCrossfade = false
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        val contentStartPadding by animateDpAsState(
            targetValue = if (showSidebar && !sidebarCollapsed) {
                FetchLayout.tokens.sidebarContentOffset
            } else {
                FetchTheme.spacing.none
            },
            animationSpec = tween(FetchMotion.tokens.durations.medium),
            label = "contentStartPadding"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = contentStartPadding)
                .onPreviewKeyEvent { keyEvent ->
                    // Long-press Back on a root route directly opens the sidebar,
                    // bypassing the "scroll row to start" BackHandler in home content.
                    if (keyEvent.key == Key.Back) {
                        if (
                            keyEvent.type == KeyEventType.KeyDown &&
                            showSidebar &&
                            drawerState.currentValue == DrawerValue.Closed &&
                            currentRoute in rootRoutes &&
                            keyEvent.nativeKeyEvent.isLongPress
                        ) {
                            if (!longPressBackHeld.value) {
                                longPressBackHeld.value = true
                                pendingSidebarFocusRequest = true
                                drawerState.setValue(DrawerValue.Open)
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (longPressBackHeld.value) {
                            if (keyEvent.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
                .onKeyEvent { keyEvent ->
                    val openKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                    if (
                        showSidebar &&
                        drawerState.currentValue == DrawerValue.Closed &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        keyEvent.key == openKey
                    ) {
                        if (focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)) {
                            true
                        } else {
                            pendingSidebarFocusRequest = true
                            drawerState.setValue(DrawerValue.Open)
                            true
                        }
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides (drawerState.currentValue == DrawerValue.Open),
                LocalContentFocusRequester provides contentFocusRequester
            ) {
                FetchNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders
                )
            }
        }
    }
}

private fun navigateToDrawerRoute(
    navController: NavHostController,
    currentRoute: String?,
    targetRoute: String
) {
    if (currentRoute == targetRoute) {
        if (targetRoute == Screen.Home.route) {
            // Scroll Home to top by clearing saved focus/scroll state on the ViewModel.
            val homeEntry = try {
                navController.getBackStackEntry(Screen.Home.route)
            } catch (_: IllegalArgumentException) {
                // "home" not yet on the back stack (e.g. nav graph not fully initialized).
                return
            }
            val homeViewModel = androidx.lifecycle.ViewModelProvider(homeEntry)[com.fetch.tv.ui.screens.home.HomeViewModel::class.java]
            homeViewModel.requestScrollToTop()
        }
        return
    }
    try {
        navController.navigate(targetRoute) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    } catch (e: IllegalArgumentException) {
        Log.w("FetchNavigation", "Route not found in nav graph: $targetRoute", e)
    }
}

private fun isBlockedContentKey(key: Key): Boolean {
    return key == Key.DirectionUp ||
        key == Key.DirectionDown ||
        key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.DirectionCenter ||
        key == Key.Enter
}

@Composable
private fun DrawerItemIcon(
    iconRes: Int?,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.tv.material3.LocalContentColor.current,
    brush: Brush? = null
) {
    val iconModifier = if (brush == null) {
        modifier
    } else {
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                }
            }
    }
    val iconTint = if (brush == null) tint else Color.White
    when {
        icon != null -> Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier
        )

        iconRes != null -> Icon(
            painter = rememberRawSvgPainter(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier
        )
    }
}

object LocaleCache {
    const val UNSET = "__UNSET__"

    @Volatile
    var localeTag: String = UNSET
}
