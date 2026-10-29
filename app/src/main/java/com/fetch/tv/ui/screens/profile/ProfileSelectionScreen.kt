package com.fetch.tv.ui.screens.profile

import com.fetch.tv.ui.theme.FetchRadii
import androidx.tv.material3.MaterialTheme
import com.fetch.tv.ui.theme.FetchPrimitives
import com.fetch.tv.ui.util.contentTextDirection
import com.fetch.tv.ui.theme.FetchMotion

import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.brandWordmarkResource

import android.graphics.Rect
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import com.fetch.tv.core.sync.SetProfilePinResult
import com.fetch.tv.core.parental.PinVerifyResult
import com.fetch.tv.core.parental.ResetPinResult
import com.fetch.tv.data.remote.supabase.AvatarCatalogItem
import com.fetch.tv.data.remote.supabase.ProfileBackgroundCatalogItem
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.domain.model.PROFILE_AVATAR_COLORS
import com.fetch.tv.domain.model.UserProfile
import com.fetch.tv.ui.components.AvatarPickerGrid
import com.fetch.tv.ui.components.CustomProfileBackgroundImage
import com.fetch.tv.ui.components.MemberBrandWordmark
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.components.ProfileBackgroundImage
import com.fetch.tv.ui.components.ProfileBackgroundPicker
import com.fetch.tv.ui.components.ProfileAvatarCircle
import com.fetch.tv.ui.components.ProfileEditorTab
import com.fetch.tv.ui.components.ProfileEditorTabs
import com.fetch.tv.ui.membership.Membership
import com.fetch.tv.ui.util.rememberLongPressKeyTracker
import com.fetch.tv.domain.model.ProfileBackgroundSelection
import com.fetch.tv.domain.model.resolveProfileBackgroundSelection
import kotlinx.coroutines.delay
import com.fetch.tv.ui.theme.FetchContentColors
import com.fetch.tv.ui.theme.FetchStatusTones

private object ProfileSelectionSpacing {
    val ScreenPaddingHorizontal = FetchTheme.spacing.huge
    val ScreenPaddingVertical = FetchTheme.spacing.xxxl
    val LogoHeight = 44.dp
    val LogoToHeading = 28.dp
    val HeadingToSubheading = FetchTheme.spacing.md
    val GridItemGap = 28.dp
    val CompactGridItemGap = FetchTheme.spacing.md
    val CardWidth = 152.dp
    val CompactCardWidth = 128.dp
    val CardPaddingHorizontal = 10.dp
    val CardPaddingVertical = FetchTheme.spacing.sm
    val AvatarContainer = 126.dp
    val CompactAvatarContainer = 104.dp
    val CompactAvatarSize = 82.dp
    val CompactFocusedAvatarSize = 88.dp
    val CompactOuterAvatarSize = 98.dp
    val CompactFocusedOuterAvatarSize = 104.dp
    val AvatarToName = FetchTheme.spacing.md
    val CompactAvatarToName = 10.dp
    val NameToMeta = FetchTheme.spacing.sm
    val MetaSlotHeight = FetchTheme.spacing.lg
    val EditorPanelMaxWidth = 980.dp
    val EditorPanelGap = 28.dp
    val EditorPreviewWidth = 280.dp
    val EditorPreviewAvatarSize = 112.dp
    val EditorFieldRadius = 14.dp
    val EditorPreviewTopOffset = 28.dp
    val EditorDividerHeight = 320.dp
    val EditorDividerSpacing = 18.dp
    val PinKickerToHeading = 14.dp
    val PinHeadingToBoxes = 42.dp
    val PinBoxesToSupport = 26.dp
    val PinBoxSize = 118.dp
    val PinBoxGap = 14.dp
    val PinSupportMaxWidth = 720.dp
}

private sealed interface ProfileBackgroundArtwork {
    data class Catalog(val background: ProfileBackgroundCatalogItem) : ProfileBackgroundArtwork
    data class Custom(val url: String) : ProfileBackgroundArtwork
}

private val ProfileCardFocusEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val ProfilePinLength = 4

enum class ProfileSelectionMode {
    Selection,
    Management
}

internal sealed interface ProfilePinOverlayState {
    val profile: UserProfile

    data class Unlock(override val profile: UserProfile) : ProfilePinOverlayState
    /** A new PIN for [profile]. [parentPin] is set when it replaces a forgotten PIN. */
    data class Set(
        override val profile: UserProfile,
        val currentPin: String? = null,
        val parentPin: String? = null
    ) : ProfilePinOverlayState
    data class VerifyCurrentForChange(override val profile: UserProfile) : ProfilePinOverlayState
    data class VerifyCurrentForRemove(override val profile: UserProfile) : ProfilePinOverlayState
    data class VerifyCurrentForDelete(override val profile: UserProfile) : ProfilePinOverlayState
    /** The profile's own PIN, asked for before it is edited or its settings are copied to or from it. */
    data class VerifyAccess(override val profile: UserProfile) : ProfilePinOverlayState
    /** The main profile's PIN ([profile]), asked for before [target]'s forgotten PIN is replaced. */
    data class ResetVerifyParent(
        override val profile: UserProfile,
        val target: UserProfile
    ) : ProfilePinOverlayState
    /**
     * Parent PIN, asked for before a restricted profile opens settings or leaves the profile,
     * or before [targetProfile]'s parental limits are edited.
     */
    data class ParentGate(
        override val profile: UserProfile,
        val targetProfile: UserProfile? = null,
        /** Set instead of [targetProfile] when the PIN opens this profile's viewing activity. */
        val activityProfile: UserProfile? = null,
        /** Set instead when the PIN opens this unlocked profile while another has limits. */
        val openProfile: UserProfile? = null
    ) : ProfilePinOverlayState
}

/** Everything the create panel collects for a new profile. */
private data class NewProfileSetup(
    val name: String,
    val colorHex: String,
    val avatarId: String?,
    val copyFromProfileId: Int?,
    val copyProviderCredentials: Boolean,
    val isKidsProfile: Boolean,
    val usesMainAddons: Boolean
)

/** An action on [profile] that runs once its PIN has been entered. */
private data class ProfilePinCheck(
    val profile: UserProfile,
    val onVerified: () -> Unit,
    val stage: ProfilePinOverlayState = ProfilePinOverlayState.VerifyAccess(profile)
)

/** What a PIN prompt offers for a forgotten PIN: a reset when [onReset] is set, otherwise a note. */
internal data class ForgotPinOption(
    val label: String,
    val onReset: (() -> Unit)? = null
)

private enum class ProfilePinEntryStage {
    Create,
    Confirm
}

private data class KeyboardVisibilityState(
    val isVisible: Boolean,
    val bottomPx: Int
)

@Composable
fun ProfileSelectionScreen(
    onProfileSelected: () -> Unit,
    onProfileClicked: () -> Unit = {},
    onProfileSelectionFailed: () -> Unit = {},
    onProfileFocusChanged: ((colorHex: String?, backgroundUrl: String?, memoryCacheKey: String?) -> Unit)? = null,
    onProfileThemeFocused: ((com.fetch.tv.domain.model.AppTheme?) -> Unit)? = null,
    screenMode: ProfileSelectionMode = ProfileSelectionMode.Selection,
    onBackPress: (() -> Unit)? = null,
    viewModel: ProfileSelectionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val avatarCatalog by viewModel.avatarCatalog.collectAsState()
    val profileBackgroundCatalog by viewModel.profileBackgroundCatalog.collectAsState()
    val hasProfileBackgroundAccess by viewModel.hasProfileBackgroundAccess.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isCopyingSettings by viewModel.isCopyingSettings.collectAsState()
    val profilePinEnabled by viewModel.profilePinEnabled.collectAsState()
    val profileRestrictions by viewModel.profileRestrictions.collectAsState()
    val profileTitleLists by viewModel.profileTitleLists.collectAsState()
    val profileKidsRules by viewModel.profileKidsRules.collectAsState()
    val isPinOperationInProgress by viewModel.isPinOperationInProgress.collectAsState()
    val avatarImageUrlsById = remember(avatarCatalog, profiles) {
        buildMap {
            avatarCatalog.forEach { avatar -> put(avatar.id, avatar.imageUrl) }
            profiles.mapNotNull(UserProfile::avatarId).forEach { avatarId ->
                viewModel.getAvatarImageUrl(avatarId)?.let { imageUrl -> putIfAbsent(avatarId, imageUrl) }
            }
        }
    }
    val profileBackgroundsById = remember(profileBackgroundCatalog) {
        profileBackgroundCatalog.associateBy { it.id }
    }
    val memberAccess = Membership.access
    var focusedAvatarColor by remember { mutableStateOf(FetchPrimitives.blue500) }
    var focusedProfileId by remember { mutableStateOf<Int?>(null) }
    var showCreateProfile by remember { mutableStateOf(false) }
    var longPressedProfile by remember { mutableStateOf<UserProfile?>(null) }
    var suppressOptionsDialogFirstKeyUp by remember { mutableStateOf(true) }
    var profileToDelete by remember { mutableStateOf<UserProfile?>(null) }
    var profileToEdit by remember { mutableStateOf<UserProfile?>(null) }
    var settingsCopyTarget by remember { mutableStateOf<UserProfile?>(null) }
    var limitsTarget by remember { mutableStateOf<UserProfile?>(null) }
    var activityTarget by remember { mutableStateOf<UserProfile?>(null) }
    var settingsCopyError by remember { mutableStateOf<String?>(null) }
    var pinOverlayState by remember { mutableStateOf<ProfilePinOverlayState?>(null) }
    var pinOverlayError by remember { mutableStateOf<String?>(null) }
    var profileActionMessage by remember { mutableStateOf<String?>(null) }
    var pinCheck by remember { mutableStateOf<ProfilePinCheck?>(null) }
    var parentPinNudgeFor by remember { mutableStateOf<UserProfile?>(null) }
    var lockOfferFor by remember { mutableStateOf<UserProfile?>(null) }
    val lockUnrestrictedProfiles by viewModel.lockUnrestrictedProfiles.collectAsState()
    var pinCheckError by remember { mutableStateOf<String?>(null) }
    // Editing a locked profile, or copying settings (and API keys) to or from it, needs its PIN.
    // The profile already open behind Manage Profiles was unlocked to get there; the startup
    // picker's active profile was not.
    val runWithProfilePin: (UserProfile, () -> Unit) -> Unit = { profile, action ->
        val alreadyUnlocked = screenMode == ProfileSelectionMode.Management && profile.id == activeProfileId
        if (profilePinEnabled[profile.id] == true && !alreadyUnlocked) {
            pinCheckError = null
            pinCheck = ProfilePinCheck(profile, action)
        } else {
            action()
        }
    }
    val parentProfile = profiles.firstOrNull { it.isPrimary }
    val parentHasPin = parentProfile != null && profilePinEnabled[parentProfile.id] == true
    fun pinRejectedMessage(verify: PinVerifyResult): String = if (verify.retryAfterSeconds > 0) {
        context.getString(R.string.profile_pin_locked, verify.retryAfterSeconds)
    } else {
        context.getString(R.string.profile_pin_incorrect)
    }
    // A forgotten PIN is replaced with the main profile's PIN; the main PIN itself can't be.
    fun forgotPinOption(
        state: ProfilePinOverlayState,
        moveTo: (ProfilePinOverlayState) -> Unit
    ): ForgotPinOption? {
        if (state is ProfilePinOverlayState.Set) return null
        val target = state.profile
        return when {
            target.isPrimary -> ForgotPinOption(context.getString(R.string.profile_pin_forgot_main))
            parentProfile == null || !parentHasPin -> ForgotPinOption(
                context.getString(R.string.profile_pin_forgot_needs_main, parentProfile?.name.orEmpty())
            )
            else -> ForgotPinOption(context.getString(R.string.profile_pin_forgot_reset, parentProfile.name)) {
                moveTo(ProfilePinOverlayState.ResetVerifyParent(parentProfile, target))
            }
        }
    }
    // Runs one step of a PIN reset: the main PIN, then the new PIN. [onReset] gets the profile.
    fun submitPinResetStep(
        state: ProfilePinOverlayState,
        pin: String,
        moveTo: (ProfilePinOverlayState) -> Unit,
        setError: (String?) -> Unit,
        onReset: (profile: UserProfile, message: String) -> Unit
    ) {
        when {
            state is ProfilePinOverlayState.ResetVerifyParent ->
                viewModel.verifyProfilePin(state.profile.id, pin) { result ->
                    result.onSuccess { verify ->
                        if (verify.unlocked) {
                            setError(null)
                            moveTo(ProfilePinOverlayState.Set(state.target, parentPin = pin))
                        } else {
                            setError(pinRejectedMessage(verify))
                        }
                    }.onFailure {
                        setError(context.getString(R.string.profile_pin_verify_error))
                    }
                }
            state is ProfilePinOverlayState.Set && state.parentPin != null ->
                viewModel.resetProfilePin(state.profile.id, pin, state.parentPin) { result ->
                    when (result) {
                        ResetPinResult.Success -> {
                            setError(null)
                            onReset(state.profile, context.getString(R.string.profile_pin_reset_for_profile, state.profile.name))
                        }
                        ResetPinResult.LocalOnly -> {
                            setError(null)
                            onReset(state.profile, context.getString(R.string.profile_pin_reset_local_only, state.profile.name))
                        }
                        else -> setError(context.getString(R.string.profile_pin_save_error))
                    }
                }
        }
    }
    val onProfileFocusedChange = remember(onProfileFocusChanged, onProfileThemeFocused) {
        { profile: UserProfile? ->
            focusedProfileId = profile?.id
            focusedAvatarColor = profile?.avatarColorHex?.let(::parseProfileColor) ?: FetchPrimitives.neutral700
            Unit
        }
    }
    val focusedProfile = profiles.firstOrNull { it.id == focusedProfileId }
    val profileThemes by viewModel.profileThemes.collectAsState()
    val focusedBrandWordmarkRes = remember(focusedProfileId, profileThemes) {
        val pid = focusedProfileId ?: return@remember null
        val theme = profileThemes[pid]
        theme?.brandWordmarkResource
    }
    val selectProfile: (Int) -> Unit = { profileId ->
        if (!viewModel.isSelectingProfile) {
            onProfileClicked()
            viewModel.selectProfile(
                id = profileId,
                onComplete = onProfileSelected,
                onFailure = {
                    onProfileSelectionFailed()
                    profileActionMessage = context.getString(R.string.account_error_generic_retry)
                }
            )
        }
    }
    // The startup picker can switch into managing in place; Manage Profiles opens already in it.
    val canToggleManagement = screenMode == ProfileSelectionMode.Selection
    var isManagingInPlace by rememberSaveable { mutableStateOf(false) }
    val isManagementMode = screenMode == ProfileSelectionMode.Management || isManagingInPlace
    val screenTitle = if (isManagementMode) {
        stringResource(R.string.profile_manage_title)
    } else {
        stringResource(R.string.profile_selection_title)
    }
    val screenSubtitle = if (isManagementMode) {
        stringResource(R.string.profile_manage_subtitle)
    } else {
        stringResource(R.string.profile_selection_subtitle)
    }
    val screenHint = if (isManagementMode) {
        stringResource(R.string.profile_manage_hint)
    } else {
        stringResource(R.string.profile_selection_hint)
    }

    if (onBackPress != null) {
        BackHandler(onBack = onBackPress)
    }

    LaunchedEffect(profiles, activeProfileId) {
        val targetProfile = profiles.firstOrNull { it.id == focusedProfileId }
            ?: profiles.firstOrNull { it.id == activeProfileId }
            ?: profiles.firstOrNull()
        targetProfile?.let { profile ->
            focusedProfileId = profile.id
            focusedAvatarColor = parseProfileColor(profile.avatarColorHex)
        }
    }

    LaunchedEffect(profileToEdit, hasProfileBackgroundAccess) {
        if (profileToEdit != null && hasProfileBackgroundAccess) {
            viewModel.preloadProfileBackgrounds()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val overlayProfileColor = pinOverlayState?.profile?.avatarColorHex?.let(::parseProfileColor)
        val backgroundProfile = pinOverlayState?.profile ?: focusedProfile
        val backgroundSelection = resolveProfileBackgroundSelection(
            profile = backgroundProfile,
            entitlements = memberAccess.entitlements
        )
        LaunchedEffect(backgroundSelection) {
            val selectedId = (backgroundSelection as? ProfileBackgroundSelection.Catalog)?.id
            if (selectedId != null) viewModel.loadProfileBackground(selectedId)
        }
        val profileBackground = when (backgroundSelection) {
            is ProfileBackgroundSelection.Catalog -> profileBackgroundsById[backgroundSelection.id]
                ?.takeIf { it.imageFile != null }
                ?.let {
                ProfileBackgroundArtwork.Catalog(it)
            }
            is ProfileBackgroundSelection.Custom -> ProfileBackgroundArtwork.Custom(backgroundSelection.url)
            null -> null
        }

        // Keep splash background in sync with whichever profile is focused
        // so the splash matches after the user clicks.
        LaunchedEffect(profileBackground, backgroundProfile) {
            val bgUrl = when (profileBackground) {
                is ProfileBackgroundArtwork.Custom -> profileBackground.url
                is ProfileBackgroundArtwork.Catalog -> profileBackground.background.imageFile?.toURI()?.toString()
                null -> null
            }
            val cacheKey = when (profileBackground) {
                is ProfileBackgroundArtwork.Catalog -> "profile-background-${profileBackground.background.id}-v${profileBackground.background.assetVersion}"
                is ProfileBackgroundArtwork.Custom -> "custom-profile-background-${profileBackground.url}"
                null -> null
            }
            onProfileFocusChanged?.invoke(
                backgroundProfile?.avatarColorHex,
                bgUrl,
                cacheKey
            )
        }

        LaunchedEffect(focusedProfileId, profileThemes) {
            val theme = focusedProfileId?.let { profileThemes[it] }
            onProfileThemeFocused?.invoke(theme)
        }

        ProfileSelectionBackground(
            focusedAvatarColor = overlayProfileColor ?: focusedAvatarColor,
            profileBackground = profileBackground
        )

        AnimatedContent(
            targetState = pinOverlayState,
            transitionSpec = {
                val entering = fadeIn(tween(FetchMotion.tokens.durations.fast)) + slideInHorizontally(
                    animationSpec = tween(FetchMotion.tokens.durations.medium, easing = ProfileCardFocusEasing),
                    initialOffsetX = { fullWidth ->
                        if (targetState == null) -fullWidth / 10 else fullWidth / 12
                    }
                )
                val exiting = fadeOut(tween(FetchMotion.tokens.durations.fast)) + slideOutHorizontally(
                    animationSpec = tween(FetchMotion.tokens.durations.fast),
                    targetOffsetX = { fullWidth ->
                        if (targetState == null) fullWidth / 12 else -fullWidth / 12
                    }
                )
                entering togetherWith exiting
            },
            modifier = Modifier.fillMaxSize(),
            label = "profilePinTransition"
        ) { activePinOverlay ->
            if (activePinOverlay == null) {
                ProfileSelectionMainContent(
                    screenTitle = screenTitle,
                    screenSubtitle = screenSubtitle,
                    screenHint = screenHint,
                    isManagementMode = isManagementMode,
                    onToggleManagement = if (canToggleManagement) {
                        { isManagingInPlace = !isManagingInPlace }
                    } else {
                        null
                    },
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    canAddProfile = viewModel.canAddProfile,
                    profilePinEnabled = profilePinEnabled,
                    profileRestrictions = profileRestrictions,
                    avatarImageUrlsById = avatarImageUrlsById,
                    brandWordmarkRes = focusedBrandWordmarkRes,
                    onProfileFocused = onProfileFocusedChange,
                    onProfileSelected = { profile ->
                        if (isManagementMode) {
                            suppressOptionsDialogFirstKeyUp = false
                            longPressedProfile = profile
                        } else {
                            val parent = profiles.firstOrNull { it.isPrimary }
                            if (profilePinEnabled[profile.id] == true) {
                                pinOverlayError = null
                                pinOverlayState = ProfilePinOverlayState.Unlock(profile)
                            } else if (parent != null && viewModel.needsParentPinToOpen(profile.id)) {
                                // Otherwise a kid could open a profile with no limits and no PIN.
                                pinOverlayError = null
                                pinOverlayState = ProfilePinOverlayState.ParentGate(parent, openProfile = profile)
                            } else {
                                selectProfile(profile.id)
                            }
                        }
                    },
                    onProfileLongPress = { profile ->
                        suppressOptionsDialogFirstKeyUp = true
                        longPressedProfile = profile
                    },
                    onAddProfileClick = { showCreateProfile = true }
                )
            } else {
                val pinResetFinished: (UserProfile, String) -> Unit = { _, message ->
                    pinOverlayState = null
                    profileActionMessage = message
                }
                ProfilePinOverlay(
                    state = activePinOverlay,
                    isWorking = isPinOperationInProgress,
                    errorMessage = pinOverlayError,
                    forgotPin = forgotPinOption(activePinOverlay) { next ->
                        pinOverlayError = null
                        pinOverlayState = next
                    },
                    onClearError = { pinOverlayError = null },
                    onDismiss = {
                        pinOverlayState = null
                        pinOverlayError = null
                    },
                    onSubmit = { pin ->
                        when (activePinOverlay) {
                            is ProfilePinOverlayState.ResetVerifyParent -> submitPinResetStep(
                                activePinOverlay,
                                pin,
                                moveTo = { pinOverlayState = it },
                                setError = { pinOverlayError = it },
                                onReset = pinResetFinished
                            )

                            is ProfilePinOverlayState.Set -> if (activePinOverlay.parentPin != null) {
                                submitPinResetStep(
                                    activePinOverlay,
                                    pin,
                                    moveTo = { pinOverlayState = it },
                                    setError = { pinOverlayError = it },
                                    onReset = pinResetFinished
                                )
                            } else {
                                viewModel.setProfilePin(
                                    activePinOverlay.profile.id,
                                    pin,
                                    activePinOverlay.currentPin
                                ) { result ->
                                    when (result) {
                                        is SetProfilePinResult.Success -> {
                                            pinOverlayState = null
                                            pinOverlayError = null
                                            profileActionMessage = context.getString(R.string.profile_pin_saved_for_profile, activePinOverlay.profile.name)
                                        }
                                        is SetProfilePinResult.CurrentPinRequired -> {
                                            pinOverlayState = ProfilePinOverlayState.VerifyCurrentForChange(
                                                activePinOverlay.profile
                                            )
                                            pinOverlayError = context.getString(R.string.profile_pin_current_required)
                                        }
                                        is SetProfilePinResult.Failure -> {
                                            pinOverlayError = context.getString(R.string.profile_pin_save_error)
                                        }
                                    }
                                }
                            }

                            is ProfilePinOverlayState.Unlock -> {
                                viewModel.verifyProfilePin(activePinOverlay.profile.id, pin) { result ->
                                    result.onSuccess { verify ->
                                        if (verify.unlocked) {
                                            pinOverlayError = null
                                            pinOverlayState = null
                                            selectProfile(activePinOverlay.profile.id)
                                        } else {
                                            pinOverlayError = if (verify.retryAfterSeconds > 0) {
                                                context.getString(R.string.profile_pin_locked, verify.retryAfterSeconds)
                                            } else {
                                                context.getString(R.string.profile_pin_invalid)
                                            }
                                        }
                                    }.onFailure {
                                        pinOverlayError = context.getString(R.string.profile_pin_verify_error)
                                    }
                                }
                            }

                            is ProfilePinOverlayState.VerifyCurrentForChange -> {
                                viewModel.verifyProfilePin(activePinOverlay.profile.id, pin) { result ->
                                    result.onSuccess { verify ->
                                        if (verify.unlocked) {
                                            pinOverlayError = null
                                            pinOverlayState = ProfilePinOverlayState.Set(
                                                profile = activePinOverlay.profile,
                                                currentPin = pin
                                            )
                                        } else {
                                            pinOverlayError = if (verify.retryAfterSeconds > 0) {
                                                context.getString(R.string.profile_pin_locked, verify.retryAfterSeconds)
                                            } else {
                                                context.getString(R.string.profile_pin_incorrect)
                                            }
                                        }
                                    }.onFailure {
                                        pinOverlayError = context.getString(R.string.profile_pin_verify_error)
                                    }
                                }
                            }

                            is ProfilePinOverlayState.VerifyCurrentForRemove -> {
                                viewModel.clearProfilePin(
                                    profileId = activePinOverlay.profile.id,
                                    currentPin = pin
                                ) { result ->
                                    result.onSuccess { verify ->
                                        if (verify.unlocked) {
                                            pinOverlayError = null
                                            pinOverlayState = null
                                            profileActionMessage = context.getString(R.string.profile_pin_lock_removed_for_profile, activePinOverlay.profile.name)
                                        } else {
                                            pinOverlayError = if (verify.retryAfterSeconds > 0) {
                                                context.getString(R.string.profile_pin_locked, verify.retryAfterSeconds)
                                            } else {
                                                context.getString(R.string.profile_pin_incorrect)
                                            }
                                        }
                                    }.onFailure {
                                        pinOverlayError = context.getString(R.string.profile_pin_verify_error)
                                    }
                                }
                            }

                            is ProfilePinOverlayState.ParentGate -> {
                                viewModel.verifyProfilePin(activePinOverlay.profile.id, pin) { result ->
                                    result.onSuccess { verify ->
                                        if (verify.unlocked) {
                                            pinOverlayError = null
                                            pinOverlayState = null
                                            limitsTarget = activePinOverlay.targetProfile
                                            activityTarget = activePinOverlay.activityProfile
                                            activePinOverlay.openProfile?.let { selectProfile(it.id) }
                                        } else {
                                            pinOverlayError = if (verify.retryAfterSeconds > 0) {
                                                context.getString(R.string.profile_pin_locked, verify.retryAfterSeconds)
                                            } else {
                                                context.getString(R.string.profile_pin_incorrect)
                                            }
                                        }
                                    }.onFailure {
                                        pinOverlayError = context.getString(R.string.profile_pin_verify_error)
                                    }
                                }
                            }

                            // Shown in its own dialog by the pin check below, never here.
                            is ProfilePinOverlayState.VerifyAccess -> Unit

                            is ProfilePinOverlayState.VerifyCurrentForDelete -> {
                                viewModel.verifyProfilePin(activePinOverlay.profile.id, pin) { result ->
                                    result.onSuccess { verify ->
                                        if (verify.unlocked) {
                                            pinOverlayError = null
                                            pinOverlayState = null
                                            profileToDelete = activePinOverlay.profile
                                        } else {
                                            pinOverlayError = if (verify.retryAfterSeconds > 0) {
                                                context.getString(R.string.profile_pin_locked, verify.retryAfterSeconds)
                                            } else {
                                                context.getString(R.string.profile_pin_incorrect)
                                            }
                                        }
                                    }.onFailure {
                                        pinOverlayError = context.getString(R.string.profile_pin_verify_error)
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }

        // Create Profile Overlay
        AnimatedVisibility(
            visible = showCreateProfile,
            enter = fadeIn(FetchMotion.enterTween()),
            exit = fadeOut(FetchMotion.exitTween())
        ) {
            CreateProfileOverlay(
                profiles = profiles,
                avatarCatalog = avatarCatalog,
                isCreating = isCreating,
                onRequestProfilePin = runWithProfilePin,
                onDismiss = { if (!isCreating) showCreateProfile = false },
                onCreateProfile = { setup ->
                    viewModel.createProfile(
                        name = setup.name,
                        avatarColorHex = setup.colorHex,
                        avatarId = setup.avatarId,
                        copyFromProfileId = setup.copyFromProfileId,
                        copyProviderCredentials = setup.copyProviderCredentials,
                        isKidsProfile = setup.isKidsProfile,
                        usesMainAddons = setup.usesMainAddons
                    ) { result ->
                        when (result) {
                            is CreateProfileResult.Created -> {
                                showCreateProfile = false
                                if (setup.isKidsProfile && !parentHasPin) parentPinNudgeFor = result.profile
                                if (setup.isKidsProfile && parentHasPin) lockOfferFor = result.profile
                                val sourceName = profiles.firstOrNull { it.id == setup.copyFromProfileId }?.name
                                if (result.settingsCopyResult?.isSuccess == true && sourceName != null) {
                                    profileActionMessage = context.getString(
                                        R.string.profile_copy_settings_created_success,
                                        sourceName
                                    )
                                } else if (result.settingsCopyResult?.isFailure == true) {
                                    profileActionMessage = context.getString(
                                        R.string.profile_copy_settings_created_error
                                    )
                                }
                            }
                            CreateProfileResult.Failed -> {
                                profileActionMessage = context.getString(R.string.profile_create_error)
                            }
                        }
                    }
                }
            )
        }

        // Edit Profile Overlay
        // Keeps the last profile on screen while the overlay fades out, so it doesn't go blank.
        val lastProfileToEdit = remember { mutableStateOf<UserProfile?>(null) }
        profileToEdit?.let { lastProfileToEdit.value = it }
        AnimatedVisibility(
            visible = profileToEdit != null,
            enter = fadeIn(FetchMotion.enterTween()),
            exit = fadeOut(FetchMotion.exitTween())
        ) {
            // Ignore keys while fading out, so a quick second OK can't save twice.
            // Exiting exactly when there is no profile to edit (reopening mid-fade clears it).
            val exiting = profileToEdit == null
            Box(modifier = Modifier.onPreviewKeyEvent { exiting }) {
                lastProfileToEdit.value?.let { profile ->
                    val editorPreferences by remember(profile.id) {
                        viewModel.editorPreferences(profile.id)
                    }.collectAsState(initial = null)
                    EditProfileOverlay(
                        profile = profile,
                        avatarCatalog = avatarCatalog,
                        profileBackgroundCatalog = profileBackgroundCatalog,
                        hasProfileBackgroundAccess = hasProfileBackgroundAccess,
                        preferences = editorPreferences,
                        isSaving = isSaving,
                        avatarUrlResolver = { avatarId -> viewModel.getAvatarImageUrl(avatarId) },
                        onDismiss = { profileToEdit = null },
                        onSaveProfile = onSave@{ updated, preferences ->
                            // Closing: a click or accessibility action during the fade-out must not save again.
                            if (profileToEdit == null) return@onSave
                            viewModel.updateProfile(updated, preferences, editorPreferences) { saved ->
                                if (saved) {
                                    profileToEdit = null
                                } else {
                                    profileActionMessage = context.getString(R.string.profile_save_error)
                                }
                            }
                        }
                    )
                }
            }
        }

        LaunchedEffect(profileActionMessage) {
            if (profileActionMessage != null) {
                delay(2600)
                profileActionMessage = null
            }
        }

        AnimatedVisibility(
            visible = profileActionMessage != null,
            enter = fadeIn(tween(FetchMotion.tokens.durations.fast)),
            exit = fadeOut(tween(FetchMotion.tokens.durations.fast)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            profileActionMessage?.let { message ->
                Box(
                    modifier = Modifier
                        .padding(bottom = 34.dp)
                        .clip(RoundedCornerShape(FetchRadii.tokens.full))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .border(
                            width = FetchTheme.spacing.hairline,
                            color = Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(FetchRadii.tokens.full)
                        )
                        .padding(horizontal = FetchTheme.spacing.xl, vertical = 14.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Text(
                        text = message,
                        color = FetchTheme.colors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Long-press options dialog (Edit / Delete)
        longPressedProfile?.let { profile ->
            val primaryDialogFocusRequester = remember(profile.id) { FocusRequester() }
            LaunchedEffect(profile.id) {
                repeat(2) { withFrameNanos { } }
                runCatching { primaryDialogFocusRequester.requestFocus() }
            }
            FetchDialog(
                onDismiss = { longPressedProfile = null },
                title = stringResource(R.string.profile_selection_options_title),
                width = 360.dp,
                suppressFirstKeyUp = suppressOptionsDialogFirstKeyUp,
                scrollable = true
            ) {
                Button(
                    onClick = {
                        longPressedProfile = null
                        runWithProfilePin(profile) { profileToEdit = profile }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(primaryDialogFocusRequester),
                    shape = ButtonDefaults.shape(FetchTheme.shapes.button),
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.profile_edit_label))
                }

                if (profiles.size > 1) {
                    Button(
                        onClick = {
                            longPressedProfile = null
                            runWithProfilePin(profile) {
                                settingsCopyError = null
                                settingsCopyTarget = profile
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ButtonDefaults.shape(FetchTheme.shapes.button),
                        colors = ButtonDefaults.colors(
                            containerColor = FetchTheme.colors.BackgroundCard,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.profile_copy_settings_label))
                    }
                }

                Button(
                    onClick = {
                        longPressedProfile = null
                        pinOverlayError = null
                        pinOverlayState = if (profilePinEnabled[profile.id] == true) {
                            ProfilePinOverlayState.VerifyCurrentForChange(profile)
                        } else {
                            ProfilePinOverlayState.Set(profile)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ButtonDefaults.shape(FetchTheme.shapes.button),
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        if (profilePinEnabled[profile.id] == true) {
                            stringResource(R.string.profile_pin_change)
                        } else {
                            stringResource(R.string.profile_pin_set)
                        }
                    )
                }

                if (profilePinEnabled[profile.id] == true) {
                    Button(
                        onClick = {
                            longPressedProfile = null
                            pinOverlayError = null
                            pinOverlayState = ProfilePinOverlayState.VerifyCurrentForRemove(profile)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ButtonDefaults.shape(FetchTheme.shapes.button),
                        colors = ButtonDefaults.colors(
                            containerColor = FetchTheme.colors.BackgroundCard,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.profile_pin_remove))
                    }
                }

                if (!profile.isPrimary && parentProfile != null) {
                    Button(
                        onClick = {
                            longPressedProfile = null
                            // A restricted profile's addons are the parent's call, like its limits.
                            val restricted = profileRestrictions[profile.id]?.isRestricted == true
                            val guard = if (restricted && parentHasPin) parentProfile else profile
                            runWithProfilePin(guard) {
                                val shared = !profile.usesPrimaryAddons
                                viewModel.updateProfile(
                                    profile.copy(usesPrimaryAddons = shared)
                                ) { saved ->
                                    profileActionMessage = when {
                                        !saved -> context.getString(R.string.profile_save_error)
                                        shared -> context.getString(
                                            R.string.profile_addons_shared_saved,
                                            profile.name,
                                            parentProfile.name
                                        )
                                        else -> context.getString(R.string.profile_addons_own_saved, profile.name)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ButtonDefaults.shape(FetchTheme.shapes.button),
                        colors = ButtonDefaults.colors(
                            containerColor = FetchTheme.colors.BackgroundCard,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    ) {
                        Text(profileAddonsLabel(profile.usesPrimaryAddons, parentProfile.name))
                    }
                }

                if (!profile.isPrimary) {
                    Button(
                        onClick = {
                            longPressedProfile = null
                            val parent = profiles.firstOrNull { it.isPrimary }
                            // Limits are the parent's to change, so they sit behind the parent PIN.
                            if (parent != null && profilePinEnabled[parent.id] == true) {
                                pinOverlayError = null
                                pinOverlayState = ProfilePinOverlayState.ParentGate(parent, targetProfile = profile)
                            } else {
                                limitsTarget = profile
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ButtonDefaults.shape(FetchTheme.shapes.button),
                        colors = ButtonDefaults.colors(
                            containerColor = FetchTheme.colors.BackgroundCard,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.parental_limits_label))
                    }
                }

                Button(
                    onClick = {
                        longPressedProfile = null
                        // Viewing activity is the main profile's to read, so another profile's
                        // sits behind the main PIN when there is one.
                        val ownActivity = screenMode == ProfileSelectionMode.Management && profile.id == activeProfileId
                        if (!ownActivity && parentProfile != null && parentHasPin) {
                            pinOverlayError = null
                            pinOverlayState = ProfilePinOverlayState.ParentGate(parentProfile, activityProfile = profile)
                        } else {
                            runWithProfilePin(profile) { activityTarget = profile }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ButtonDefaults.shape(FetchTheme.shapes.button),
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.profile_viewing_activity_label))
                }

                if (!profile.isPrimary) {
                    Button(
                        onClick = {
                            longPressedProfile = null
                            if (profilePinEnabled[profile.id] == true) {
                                pinOverlayError = null
                                pinOverlayState = ProfilePinOverlayState.VerifyCurrentForDelete(profile)
                            } else {
                                profileToDelete = profile
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ButtonDefaults.shape(FetchTheme.shapes.button),
                        colors = ButtonDefaults.colors(
                            containerColor = FetchStatusTones.errorContainer,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.profile_delete))
                    }
                }
            }
        }

        activityTarget?.let { targetProfile ->
            val entries by remember(targetProfile.id) {
                viewModel.observeViewingActivity(targetProfile.id)
            }.collectAsState(initial = null)
            ViewingActivityDialog(
                profile = targetProfile,
                entries = entries,
                onDismiss = { activityTarget = null }
            )
        }

        limitsTarget?.let { targetProfile ->
            val primaryHasPin = profiles.firstOrNull { it.isPrimary }?.let { profilePinEnabled[it.id] == true } == true
            ProfileLimitsDialog(
                profile = targetProfile,
                initial = profileRestrictions[targetProfile.id] ?: ProfileRestrictions.None,
                parentPinSet = primaryHasPin,
                onDismiss = { limitsTarget = null },
                initialKidsRules = profileKidsRules[targetProfile.id] ?: com.fetch.tv.data.local.KidsRules.Default,
                onSave = { restrictions, kidsRules ->
                    viewModel.setProfileRestrictions(targetProfile.id, restrictions)
                    viewModel.setKidsRules(targetProfile.id, kidsRules)
                    limitsTarget = null
                    profileActionMessage = context.getString(R.string.parental_limits_saved, targetProfile.name)
                    if (restrictions.isRestricted && !primaryHasPin) parentPinNudgeFor = targetProfile
                    if (restrictions.isRestricted && primaryHasPin) lockOfferFor = targetProfile
                },
                titleLists = profileTitleLists[targetProfile.id] ?: com.fetch.tv.data.local.ProfileTitleLists.Empty,
                onClearTitle = { title -> viewModel.clearParentalTitle(targetProfile.id, title) }
            )
        }

        settingsCopyTarget?.let { targetProfile ->
            CopyProfileSettingsDialog(
                profiles = profiles,
                targetProfile = targetProfile,
                isCopying = isCopyingSettings,
                errorMessage = settingsCopyError,
                onDismiss = {
                    settingsCopyError = null
                    settingsCopyTarget = null
                },
                onCopy = { sourceProfileId, copyProviderCredentials ->
                    settingsCopyError = null
                    val sourceProfile = profiles.firstOrNull { it.id == sourceProfileId }
                    val sourceName = sourceProfile?.name.orEmpty()
                    val copy = {
                        viewModel.copyProfileSettings(
                            sourceProfileId = sourceProfileId,
                            targetProfileId = targetProfile.id,
                            copyProviderCredentials = copyProviderCredentials
                        ) { result ->
                            if (result.isSuccess) {
                                settingsCopyTarget = null
                                profileActionMessage = context.getString(
                                    R.string.profile_copy_settings_success,
                                    sourceName,
                                    targetProfile.name
                                )
                            } else {
                                settingsCopyError = context.getString(R.string.profile_copy_settings_error)
                            }
                        }
                    }
                    if (sourceProfile != null) runWithProfilePin(sourceProfile, copy) else copy()
                }
            )
        }

        // Delete confirmation dialog
        profileToDelete?.let { profile ->
            val primaryDialogFocusRequester = remember(profile.id) { FocusRequester() }
            LaunchedEffect(profile.id) {
                repeat(2) { withFrameNanos { } }
                runCatching { primaryDialogFocusRequester.requestFocus() }
            }
            FetchDialog(
                onDismiss = { profileToDelete = null },
                title = stringResource(R.string.profile_delete_confirm_title),
                subtitle = stringResource(R.string.profile_delete_confirm_subtitle),
                width = 420.dp,
                suppressFirstKeyUp = false
            ) {
                // Focus starts on Cancel so one extra OK press can't delete the profile.
                Button(
                    onClick = { profileToDelete = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(primaryDialogFocusRequester),
                    shape = ButtonDefaults.shape(FetchTheme.shapes.button),
                    colors = ButtonDefaults.colors(
                        containerColor = FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.profile_cancel))
                }
                Button(
                    onClick = {
                        viewModel.deleteProfile(profile.id) { deleted ->
                            if (!deleted) {
                                profileActionMessage = context.getString(R.string.profile_delete_error)
                            }
                        }
                        profileToDelete = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ButtonDefaults.shape(FetchTheme.shapes.button),
                    colors = ButtonDefaults.colors(
                        containerColor = FetchStatusTones.errorContainer,
                        contentColor = FetchTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.profile_delete_btn))
                }
            }
        }

        // Skipped once the main profile has a PIN, however it got one.
        val nudgeParent = parentProfile?.takeIf { !parentHasPin }
        val nudgeFor = parentPinNudgeFor
        if (nudgeFor != null && nudgeParent != null) {
            ParentPinNudgeDialog(
                restrictedProfile = nudgeFor,
                mainProfile = nudgeParent,
                onSetPin = {
                    parentPinNudgeFor = null
                    pinOverlayError = null
                    pinOverlayState = ProfilePinOverlayState.Set(nudgeParent)
                },
                onDismiss = { parentPinNudgeFor = null }
            )
        }

        // Offered once the main profile has a PIN, while the lock is off and some profile is open.
        val lockOfferTarget = lockOfferFor
        val lockOfferParent = parentProfile?.takeIf { parentHasPin && !lockUnrestrictedProfiles }
        val unlockedProfiles = profiles.filter { profile ->
            !profile.isPrimary && profile.id != lockOfferTarget?.id &&
                profilePinEnabled[profile.id] != true &&
                profileRestrictions[profile.id]?.isRestricted != true
        }
        if (lockOfferTarget != null && lockOfferParent != null && unlockedProfiles.isNotEmpty()) {
            LockProfilesOfferDialog(
                restrictedProfile = lockOfferTarget,
                unlockedProfiles = unlockedProfiles,
                mainProfile = lockOfferParent,
                onAccept = {
                    viewModel.setLockUnrestrictedProfiles(true)
                    lockOfferFor = null
                },
                onDismiss = { lockOfferFor = null }
            )
        }

        // Drawn in its own window so it sits above the editors and dialogs that asked for it.
        pinCheck?.let { check ->
            val dismissPinCheck = {
                pinCheck = null
                pinCheckError = null
            }
            val moveCheckTo: (ProfilePinOverlayState) -> Unit = { next -> pinCheck = check.copy(stage = next) }
            Dialog(
                onDismissRequest = dismissPinCheck,
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(FetchTheme.colors.Background)
                ) {
                    ProfilePinOverlay(
                        state = check.stage,
                        isWorking = isPinOperationInProgress,
                        errorMessage = pinCheckError,
                        forgotPin = forgotPinOption(check.stage) { next ->
                            pinCheckError = null
                            moveCheckTo(next)
                        },
                        onClearError = { pinCheckError = null },
                        onDismiss = dismissPinCheck,
                        onSubmit = { pin ->
                            if (check.stage is ProfilePinOverlayState.VerifyAccess) {
                                viewModel.verifyProfilePin(check.profile.id, pin) { result ->
                                    result.onSuccess { verify ->
                                        if (verify.unlocked) {
                                            dismissPinCheck()
                                            check.onVerified()
                                        } else {
                                            pinCheckError = if (verify.retryAfterSeconds > 0) {
                                                context.getString(R.string.profile_pin_locked, verify.retryAfterSeconds)
                                            } else {
                                                context.getString(R.string.profile_pin_incorrect_for_profile, check.profile.name)
                                            }
                                        }
                                    }.onFailure {
                                        pinCheckError = context.getString(R.string.profile_pin_verify_error)
                                    }
                                }
                            } else {
                                // A reset proves the main profile's PIN, so the action goes ahead after it.
                                submitPinResetStep(
                                    check.stage,
                                    pin,
                                    moveTo = moveCheckTo,
                                    setError = { pinCheckError = it },
                                    onReset = { _, message ->
                                        dismissPinCheck()
                                        profileActionMessage = message
                                        check.onVerified()
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSelectionBackground(
    focusedAvatarColor: Color,
    profileBackground: ProfileBackgroundArtwork?
) {
    val animatedAvatarColor by animateColorAsState(
        targetValue = focusedAvatarColor,
        animationSpec = tween(durationMillis = FetchMotion.tokens.durations.slow),
        label = "focusedAvatarColor"
    )
    // Use fixed dark colors so the gradient is consistent.
    // Otherwise, profile selector and splash screen needs to know about user template
    val baseBg = FetchPrimitives.neutral925
    val baseBgElevated = FetchPrimitives.neutral875
    val gradientTop = lerp(baseBgElevated, animatedAvatarColor, 0.3f)
    val gradientMid = lerp(baseBg, animatedAvatarColor, 0.14f)
    val halfFadeStrong = animatedAvatarColor.copy(alpha = 0.26f)
    val halfFadeSoft = animatedAvatarColor.copy(alpha = 0.08f)

    AnimatedContent(
        targetState = profileBackground,
        transitionSpec = {
            fadeIn(tween(FetchMotion.tokens.durations.medium)) togetherWith fadeOut(tween(FetchMotion.tokens.durations.fast))
        },
        contentKey = { background ->
            when (background) {
                is ProfileBackgroundArtwork.Catalog -> "catalog:${background.background.id}"
                is ProfileBackgroundArtwork.Custom -> "custom:${background.url}"
                null -> null
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "profileBackground"
    ) { background ->
        when (background) {
            is ProfileBackgroundArtwork.Catalog -> ProfileBackgroundImage(
                background = background.background,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            is ProfileBackgroundArtwork.Custom -> CustomProfileBackgroundImage(
                imageUrl = background.url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .background(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to gradientTop,
                                    0.42f to gradientMid,
                                    1f to baseBg
                                )
                            )
                        )
                        .background(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to halfFadeStrong,
                                    0.45f to halfFadeSoft,
                                    0.72f to Color.Transparent,
                                    1f to Color.Transparent
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun ProfileSelectionMainContent(
    screenTitle: String,
    screenSubtitle: String,
    screenHint: String,
    isManagementMode: Boolean,
    onToggleManagement: (() -> Unit)?,
    profiles: List<UserProfile>,
    activeProfileId: Int,
    canAddProfile: Boolean,
    profilePinEnabled: Map<Int, Boolean>,
    profileRestrictions: Map<Int, ProfileRestrictions>,
    avatarImageUrlsById: Map<String, String>,
    brandWordmarkRes: Int? = null,
    onProfileFocused: (UserProfile?) -> Unit,
    onProfileSelected: (UserProfile) -> Unit,
    onProfileLongPress: (UserProfile) -> Unit,
    onAddProfileClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = ProfileSelectionSpacing.ScreenPaddingHorizontal,
                vertical = ProfileSelectionSpacing.ScreenPaddingVertical
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MemberBrandWordmark(
            height = ProfileSelectionSpacing.LogoHeight,
            contentDescription = stringResource(R.string.cd_fetch_logo),
            drawableOverride = brandWordmarkRes
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.LogoToHeading))

        Text(
            text = screenTitle,
            color = FetchTheme.colors.TextPrimary,
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.HeadingToSubheading))

        Text(
            text = screenSubtitle,
            color = FetchTheme.colors.TextSecondary,
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.weight(1f, fill = true))

        ProfileGrid(
            profiles = profiles,
            activeProfileId = activeProfileId,
            isManagementMode = isManagementMode,
            canAddProfile = canAddProfile,
            profilePinEnabled = profilePinEnabled,
            profileRestrictions = profileRestrictions,
            avatarImageUrlsById = avatarImageUrlsById,
            onProfileFocused = onProfileFocused,
            onProfileSelected = onProfileSelected,
            onProfileLongPress = onProfileLongPress,
            onAddProfileClick = onAddProfileClick
        )

        Spacer(modifier = Modifier.weight(1f, fill = true))

        if (onToggleManagement != null) {
            OverlayButton(
                text = stringResource(
                    if (isManagementMode) R.string.profile_manage_done else R.string.profile_manage_button
                ),
                isPrimary = false,
                onClick = onToggleManagement
            )
            Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
        }

        Text(
            text = screenHint,
            color = FetchTheme.colors.TextTertiary.copy(alpha = 0.9f),
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
private fun ProfileGrid(
    profiles: List<UserProfile>,
    activeProfileId: Int,
    isManagementMode: Boolean,
    canAddProfile: Boolean,
    profilePinEnabled: Map<Int, Boolean>,
    profileRestrictions: Map<Int, ProfileRestrictions>,
    avatarImageUrlsById: Map<String, String>,
    onProfileFocused: (UserProfile?) -> Unit,
    onProfileSelected: (UserProfile) -> Unit,
    onProfileLongPress: (UserProfile) -> Unit,
    onAddProfileClick: () -> Unit
) {
    val totalItems = profiles.size + if (canAddProfile) 1 else 0
    val initialFocusIndex = remember(profiles, activeProfileId, canAddProfile) {
        profiles.indexOfFirst { it.id == activeProfileId }
            .takeIf { it >= 0 }
            ?: if (profiles.isNotEmpty()) 0 else if (canAddProfile) 0 else -1
    }
    val focusRequesters = remember(totalItems) {
        List(totalItems) { FocusRequester() }
    }

    LaunchedEffect(totalItems, initialFocusIndex, isManagementMode) {
        repeat(2) { withFrameNanos { } }
        if (focusRequesters.isNotEmpty() && initialFocusIndex in focusRequesters.indices) {
            runCatching { focusRequesters[initialFocusIndex].requestFocus() }
        }
    }

    if (profiles.isEmpty() && !canAddProfile) {
        Text(
            text = stringResource(R.string.profile_selection_empty),
            color = FetchTheme.colors.TextSecondary,
            style = MaterialTheme.typography.titleLarge
        )
    } else {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val defaultGridWidth = profileGridWidth(
                itemCount = totalItems,
                cardWidth = ProfileSelectionSpacing.CardWidth,
                itemGap = ProfileSelectionSpacing.GridItemGap
            )
            val fullSizeTightGridWidth = profileGridWidth(
                itemCount = totalItems,
                cardWidth = ProfileSelectionSpacing.CardWidth,
                itemGap = ProfileSelectionSpacing.CompactGridItemGap
            )
            val useCompactCards = defaultGridWidth > maxWidth && fullSizeTightGridWidth > maxWidth
            val gridItemGap = if (defaultGridWidth > maxWidth) {
                ProfileSelectionSpacing.CompactGridItemGap
            } else {
                ProfileSelectionSpacing.GridItemGap
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(gridItemGap),
                verticalAlignment = Alignment.Top
            ) {
                profiles.forEachIndexed { index, profile ->
                    ProfileCard(
                        profile = profile,
                        avatarImageUrl = profile.avatarUrl?.takeIf { it.isNotBlank() }
                            ?: profile.avatarId?.let(avatarImageUrlsById::get),
                        focusRequester = focusRequesters[index],
                        compact = useCompactCards,
                        isLocked = profilePinEnabled[profile.id] == true,
                        isKids = profileRestrictions[profile.id]?.isKidsProfile == true,
                        onFocused = { onProfileFocused(profile) },
                        onClick = { onProfileSelected(profile) },
                        onLongPress = { onProfileLongPress(profile) }
                    )
                }
                if (canAddProfile) {
                    AddProfileCard(
                        focusRequester = focusRequesters[profiles.size],
                        compact = useCompactCards,
                        onFocused = { onProfileFocused(null) },
                        onClick = onAddProfileClick
                    )
                }
            }
        }
    }
}

private fun profileGridWidth(
    itemCount: Int,
    cardWidth: Dp,
    itemGap: Dp
): Dp {
    if (itemCount <= 0) return 0.dp
    val gapCount = itemCount - 1
    return (cardWidth.value * itemCount + itemGap.value * gapCount).dp
}

@Composable
private fun ProfileCard(
    profile: UserProfile,
    avatarImageUrl: String?,
    focusRequester: FocusRequester,
    compact: Boolean,
    isLocked: Boolean,
    isKids: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    val interactionSource = remember { MutableInteractionSource() }
    val focusProgress by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = FetchMotion.tokens.durations.fast, easing = ProfileCardFocusEasing),
        label = "profileFocusProgress"
    )
    val itemScale = 1f + (0.04f * focusProgress)
    val avatarSize = androidx.compose.ui.unit.lerp(
        if (compact) ProfileSelectionSpacing.CompactAvatarSize else 96.dp,
        if (compact) ProfileSelectionSpacing.CompactFocusedAvatarSize else 102.dp,
        focusProgress
    )
    val outerAvatarSize = androidx.compose.ui.unit.lerp(
        if (compact) ProfileSelectionSpacing.CompactOuterAvatarSize else 114.dp,
        if (compact) ProfileSelectionSpacing.CompactFocusedOuterAvatarSize else 122.dp,
        focusProgress
    )
    val ringWidth = androidx.compose.ui.unit.lerp(FetchTheme.spacing.hairline, FetchTheme.strokes.focus, focusProgress)
    val nameColor = lerp(
        FetchTheme.colors.TextSecondary,
        FetchTheme.colors.TextPrimary,
        focusProgress
    )
    val nameWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium

    Column(
        modifier = Modifier
            .width(
                if (compact) ProfileSelectionSpacing.CompactCardWidth
                else ProfileSelectionSpacing.CardWidth
            )
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }
                if (longPressKeyTracker.handle(native, ::isProfileSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        longPressTriggered = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    longPressTriggered &&
                    (isProfileSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                ) {
                    longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                false
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .semantics { role = Role.Button }
            .padding(
                horizontal = ProfileSelectionSpacing.CardPaddingHorizontal,
                vertical = ProfileSelectionSpacing.CardPaddingVertical
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(
                if (compact) ProfileSelectionSpacing.CompactAvatarContainer
                else ProfileSelectionSpacing.AvatarContainer
            ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(outerAvatarSize)
                    .clip(CircleShape)
                    .border(
                        width = FetchTheme.spacing.hairline,
                        color = FetchTheme.colors.Border.copy(alpha = 0.75f),
                        shape = CircleShape
                    )
                    .border(
                        border = FetchTheme.focusRing.border(ringWidth, focusProgress),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                ProfileAvatarCircle(
                    name = profile.name,
                    colorHex = profile.avatarColorHex,
                    size = avatarSize,
                    avatarImageUrl = avatarImageUrl
                )
            }

            if (isLocked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = FetchTheme.spacing.xxs, y = -FetchTheme.spacing.hairline)
                        .size(if (compact) 22.dp else 26.dp)
                        .clip(CircleShape)
                        .background(FetchTheme.colors.SurfaceVariant, CircleShape)
                        .border(
                            width = FetchTheme.spacing.xxs,
                            color = FetchTheme.colors.Background,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = stringResource(R.string.cd_profile_locked),
                        tint = Color.White,
                        modifier = Modifier.size(if (compact) 12.dp else 14.dp)
                    )
                }
            }

            if (profile.isPrimary) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = FetchTheme.spacing.xxs, y = FetchTheme.spacing.hairline)
                        .size(if (compact) 22.dp else 26.dp)
                        .clip(CircleShape)
                        .background(FetchContentColors.primaryProfileBadge, CircleShape)
                        .border(
                            width = FetchTheme.spacing.xxs,
                            color = FetchTheme.colors.Background,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u2605",
                        color = FetchPrimitives.neutral925,
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(
            modifier = Modifier.height(
                if (compact) ProfileSelectionSpacing.CompactAvatarToName
                else ProfileSelectionSpacing.AvatarToName
            )
        )

        Text(
            text = profile.name,
            style = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).copy(
                textDirection = profile.name.contentTextDirection()
            ),
            color = nameColor,
            fontWeight = nameWeight,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.NameToMeta))

        Box(
            modifier = Modifier.height(ProfileSelectionSpacing.MetaSlotHeight),
            contentAlignment = Alignment.TopCenter
        ) {
            // Badge strings are stored in normal case; the badge style shows them in capitals.
            val badgeLocale = LocalConfiguration.current.locales[0]
            if (profile.isPrimary) {
                Text(
                    text = stringResource(R.string.profile_selection_primary_badge).uppercase(badgeLocale),
                    color = FetchContentColors.primaryProfileBadge,
                    style = FetchTheme.textStyles.badge
                )
            } else if (isKids) {
                Text(
                    text = stringResource(R.string.profile_selection_kids_badge).uppercase(badgeLocale),
                    color = FetchTheme.colors.Info,
                    style = FetchTheme.textStyles.badge
                )
            }
        }
    }
}

private fun parseProfileColor(colorHex: String): Color {
    return runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(FetchPrimitives.blue500)
}

@Composable
private fun AddProfileCard(
    focusRequester: FocusRequester,
    compact: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focusProgress by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = FetchMotion.tokens.durations.fast, easing = ProfileCardFocusEasing),
        label = "addFocusProgress"
    )
    val itemScale = 1f + (0.04f * focusProgress)
    val outerAvatarSize = androidx.compose.ui.unit.lerp(
        if (compact) ProfileSelectionSpacing.CompactOuterAvatarSize else 114.dp,
        if (compact) ProfileSelectionSpacing.CompactFocusedOuterAvatarSize else 122.dp,
        focusProgress
    )
    val ringWidth = androidx.compose.ui.unit.lerp(FetchTheme.spacing.hairline, FetchTheme.strokes.focus, focusProgress)
    val nameColor = lerp(
        FetchTheme.colors.TextTertiary,
        FetchTheme.colors.TextPrimary,
        focusProgress
    )
    val plusColor = lerp(
        FetchTheme.colors.TextTertiary,
        Color.White,
        focusProgress
    )
    val addBackgroundColor = lerp(
        Color.White.copy(alpha = 0.06f),
        Color.White.copy(alpha = 0.12f),
        focusProgress
    )

    Column(
        modifier = Modifier
            .width(
                if (compact) ProfileSelectionSpacing.CompactCardWidth
                else ProfileSelectionSpacing.CardWidth
            )
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .semantics { role = Role.Button }
            .padding(
                horizontal = ProfileSelectionSpacing.CardPaddingHorizontal,
                vertical = ProfileSelectionSpacing.CardPaddingVertical
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(
                if (compact) ProfileSelectionSpacing.CompactAvatarContainer
                else ProfileSelectionSpacing.AvatarContainer
            ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(outerAvatarSize)
                    .clip(CircleShape)
                    .border(
                        width = FetchTheme.spacing.hairline,
                        color = FetchTheme.colors.Border.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
                    .border(
                        border = FetchTheme.focusRing.border(ringWidth, focusProgress),
                        shape = CircleShape
                    )
                    .background(addBackgroundColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(if (compact) 30.dp else 34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(if (compact) 22.dp else 26.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(FetchRadii.tokens.full))
                            .background(plusColor)
                    )
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(if (compact) 22.dp else 26.dp)
                            .clip(RoundedCornerShape(FetchRadii.tokens.full))
                            .background(plusColor)
                    )
                }
            }
        }

        Spacer(
            modifier = Modifier.height(
                if (compact) ProfileSelectionSpacing.CompactAvatarToName
                else ProfileSelectionSpacing.AvatarToName
            )
        )

        Text(
            text = stringResource(R.string.profile_add_new),
            color = nameColor,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.NameToMeta))
        Box(modifier = Modifier.height(ProfileSelectionSpacing.MetaSlotHeight))
    }
}

@Composable
private fun CreateProfileOverlay(
    profiles: List<UserProfile>,
    avatarCatalog: List<AvatarCatalogItem>,
    isCreating: Boolean,
    onRequestProfilePin: (UserProfile, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onCreateProfile: (NewProfileSetup) -> Unit
) {
    BackHandler(onBack = onDismiss)

    var profileName by remember { mutableStateOf("") }
    val defaultColorHex = remember { nextProfileColor(profiles) }
    var selectedColorHex by remember { mutableStateOf(defaultColorHex) }
    var selectedAvatarId by remember { mutableStateOf<String?>(null) }
    var focusedAvatarName by remember { mutableStateOf<String?>(null) }
    var selectedCopySourceId by remember { mutableStateOf<Int?>(null) }
    var copyProviderCredentials by remember { mutableStateOf(false) }
    var showSettingsSourceDialog by remember { mutableStateOf(false) }
    var showSetupDialog by remember { mutableStateOf(false) }
    var isKidsProfile by remember { mutableStateOf(false) }
    var usesMainAddons by remember { mutableStateOf(false) }
    val mainProfileName = profiles.firstOrNull { it.isPrimary }?.name.orEmpty()
    val selectedAvatar = remember(avatarCatalog, selectedAvatarId) {
        avatarCatalog.find { it.id == selectedAvatarId }
    }
    val nameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { nameFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    native.keyCode == AndroidKeyEvent.KEYCODE_BACK
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = ProfileSelectionSpacing.EditorPanelMaxWidth)
                .clip(RoundedCornerShape(FetchRadii.tokens.xl))
                .background(FetchTheme.colors.BackgroundElevated)
                .border(FetchTheme.spacing.hairline, FetchTheme.colors.Border, RoundedCornerShape(FetchRadii.tokens.xl))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // prevent dismiss when clicking the panel
                )
                .padding(FetchTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header row: title left, create button right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = FetchTheme.spacing.xl),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.profile_create_title),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
                    OverlayButton(
                        text = stringResource(R.string.profile_cancel),
                        isPrimary = false,
                        enabled = !isCreating,
                        onClick = onDismiss
                    )
                    OverlayButton(
                        text = if (isCreating) stringResource(R.string.profile_creating)
                               else stringResource(R.string.profile_create_btn),
                        isPrimary = true,
                        enabled = profileName.isNotBlank() && !isCreating,
                        onClick = {
                            onCreateProfile(
                                NewProfileSetup(
                                    name = profileName,
                                    colorHex = selectedColorHex,
                                    avatarId = selectedAvatarId,
                                    copyFromProfileId = selectedCopySourceId,
                                    copyProviderCredentials = copyProviderCredentials,
                                    isKidsProfile = isKidsProfile,
                                    usesMainAddons = usesMainAddons
                                )
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .width(ProfileSelectionSpacing.EditorPreviewWidth)
                        .padding(
                            start = FetchTheme.spacing.xl,
                            top = FetchTheme.spacing.md,
                            end = FetchTheme.spacing.xl,
                            bottom = FetchTheme.spacing.md
                        ),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProfileAvatarCircle(
                        name = profileName.ifEmpty { "?" },
                        colorHex = selectedColorHex,
                        size = ProfileSelectionSpacing.EditorPreviewAvatarSize,
                        avatarImageUrl = selectedAvatar?.imageUrl
                    )

                    Text(
                        text = profileName.ifBlank { stringResource(R.string.profile_name_placeholder) },
                        style = MaterialTheme.typography.headlineMedium.copy(
                            textDirection = profileName.contentTextDirection()
                        ),
                        color = if (profileName.isBlank()) FetchTheme.colors.TextSecondary else FetchTheme.colors.TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    ProfileNameField(
                        value = profileName,
                        onValueChange = { if (it.length <= 20) profileName = it },
                        focusRequester = nameFocusRequester
                    )

                    ProfileColorPicker(
                        selectedColorHex = selectedColorHex,
                        onColorSelected = { selectedColorHex = it }
                    )

                    val selectedCopySource = profiles.firstOrNull { it.id == selectedCopySourceId }
                    val setupParts = listOfNotNull(
                        stringResource(R.string.profile_setup_part_kids).takeIf { isKidsProfile },
                        stringResource(R.string.profile_setup_part_addons, mainProfileName).takeIf { usesMainAddons },
                        selectedCopySource?.let { stringResource(R.string.profile_setup_part_settings, it.name) }
                    )
                    OverlayButton(
                        text = if (setupParts.isEmpty()) {
                            stringResource(R.string.profile_setup_button)
                        } else {
                            stringResource(R.string.profile_setup_summary, setupParts.joinToString(" · "))
                        },
                        isPrimary = false,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCreating,
                        onClick = { showSetupDialog = true }
                    )

                }

                Spacer(modifier = Modifier.width(ProfileSelectionSpacing.EditorDividerSpacing))
                EditorSectionDivider()
                Spacer(modifier = Modifier.width(ProfileSelectionSpacing.EditorDividerSpacing))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
                ) {
                    Text(
                        text = stringResource(R.string.profile_choose_avatar),
                        modifier = Modifier.fillMaxWidth(),
                        color = FetchTheme.colors.TextSecondary,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center
                    )


                    if (avatarCatalog.isNotEmpty()) {
                        AvatarPickerGrid(
                            avatars = avatarCatalog,
                            selectedAvatarId = selectedAvatarId,
                            onAvatarSelected = { avatar ->
                                if (selectedAvatarId == avatar.id) {
                                    selectedAvatarId = null
                                    selectedColorHex = defaultColorHex
                                } else {
                                    selectedAvatarId = avatar.id
                                    avatar.bgColor?.let { selectedColorHex = it }
                                }
                            },
                            onAvatarFocused = { avatar ->
                                focusedAvatarName = avatar?.displayName
                            },
                            modifier = Modifier.heightIn(max = 320.dp)
                        )

                        Text(
                            text = focusedAvatarName ?: stringResource(R.string.profile_avatar_focus_hint),
                            modifier = Modifier.fillMaxWidth(),
                            color = if (focusedAvatarName != null) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextTertiary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(FetchRadii.tokens.xl))
                                .background(FetchTheme.colors.BackgroundCard)
                                .border(FetchTheme.spacing.hairline, FetchTheme.colors.Border, RoundedCornerShape(FetchRadii.tokens.xl)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.profile_choose_avatar),
                                color = FetchTheme.colors.TextTertiary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
        }
    }

    if (showSetupDialog) {
        val selectedCopySource = profiles.firstOrNull { it.id == selectedCopySourceId }
        ProfileSetupDialog(
            mainProfileName = mainProfileName,
            isKidsProfile = isKidsProfile,
            usesMainAddons = usesMainAddons,
            settingsSourceLabel = if (selectedCopySource == null) {
                stringResource(R.string.profile_copy_settings_create_fresh)
            } else {
                stringResource(R.string.profile_copy_settings_create_source, selectedCopySource.name)
            },
            onKidsProfileChange = { isKidsProfile = it },
            onUsesMainAddonsChange = { usesMainAddons = it },
            onChooseSettingsSource = { showSettingsSourceDialog = true },
            onDismiss = { showSetupDialog = false }
        )
    }

    if (showSettingsSourceDialog) {
        ProfileSettingsSourceDialog(
            profiles = profiles,
            selectedSourceProfileId = selectedCopySourceId,
            copyProviderCredentials = copyProviderCredentials,
            onDismiss = { showSettingsSourceDialog = false },
            onConfirm = { sourceProfileId, shouldCopyProviderCredentials ->
                val applySource = {
                    selectedCopySourceId = sourceProfileId
                    copyProviderCredentials = shouldCopyProviderCredentials
                    showSettingsSourceDialog = false
                }
                val sourceProfile = profiles.firstOrNull { it.id == sourceProfileId }
                if (sourceProfile != null && sourceProfileId != selectedCopySourceId) {
                    onRequestProfilePin(sourceProfile, applySource)
                } else {
                    applySource()
                }
            }
        )
    }
}

private fun isProfileSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun keyCodeToDigit(keyCode: Int): Char? {
    return when (keyCode) {
        AndroidKeyEvent.KEYCODE_0,
        AndroidKeyEvent.KEYCODE_NUMPAD_0 -> '0'
        AndroidKeyEvent.KEYCODE_1,
        AndroidKeyEvent.KEYCODE_NUMPAD_1 -> '1'
        AndroidKeyEvent.KEYCODE_2,
        AndroidKeyEvent.KEYCODE_NUMPAD_2 -> '2'
        AndroidKeyEvent.KEYCODE_3,
        AndroidKeyEvent.KEYCODE_NUMPAD_3 -> '3'
        AndroidKeyEvent.KEYCODE_4,
        AndroidKeyEvent.KEYCODE_NUMPAD_4 -> '4'
        AndroidKeyEvent.KEYCODE_5,
        AndroidKeyEvent.KEYCODE_NUMPAD_5 -> '5'
        AndroidKeyEvent.KEYCODE_6,
        AndroidKeyEvent.KEYCODE_NUMPAD_6 -> '6'
        AndroidKeyEvent.KEYCODE_7,
        AndroidKeyEvent.KEYCODE_NUMPAD_7 -> '7'
        AndroidKeyEvent.KEYCODE_8,
        AndroidKeyEvent.KEYCODE_NUMPAD_8 -> '8'
        AndroidKeyEvent.KEYCODE_9,
        AndroidKeyEvent.KEYCODE_NUMPAD_9 -> '9'
        else -> null
    }
}

@Composable
private fun rememberKeyboardVisibilityState(): KeyboardVisibilityState {
    val view = LocalView.current
    var state by remember(view) { mutableStateOf(KeyboardVisibilityState(isVisible = false, bottomPx = 0)) }

    DisposableEffect(view) {
        val rect = Rect()
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            view.getWindowVisibleDisplayFrame(rect)
            val rootHeight = view.rootView.height
            val obscuredHeight = (rootHeight - rect.height()).coerceAtLeast(0)
            val rootInsets = ViewCompat.getRootWindowInsets(view)
            val imeInsets = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val threshold = (rootHeight * 0.12f).toInt()
            val keyboardVisible = imeVisible || obscuredHeight > threshold
            val bottomPx = when {
                imeVisible && imeInsets != null -> imeInsets.bottom
                keyboardVisible -> obscuredHeight
                else -> 0
            }
            state = KeyboardVisibilityState(
                isVisible = keyboardVisible,
                bottomPx = bottomPx
            )
        }

        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    return state
}

@Composable
private fun EditProfileOverlay(
    profile: UserProfile,
    avatarCatalog: List<AvatarCatalogItem>,
    profileBackgroundCatalog: List<ProfileBackgroundCatalogItem>,
    hasProfileBackgroundAccess: Boolean,
    preferences: ProfileEditorPreferences?,
    isSaving: Boolean,
    avatarUrlResolver: (String?) -> String?,
    onDismiss: () -> Unit,
    onSaveProfile: (UserProfile, ProfileEditorPreferences?) -> Unit
) {
    BackHandler(onBack = onDismiss)

    var profileName by remember { mutableStateOf(profile.name) }
    var selectedColorHex by remember { mutableStateOf(profile.avatarColorHex) }
    var selectedAvatarId by remember(profile.id, profile.avatarId) {
        mutableStateOf(profile.avatarId)
    }
    var selectedBackgroundId by remember(profile.id, profile.profileBackgroundId) {
        mutableStateOf(profile.profileBackgroundId)
    }
    var selectedBackgroundUrl by remember(profile.id, profile.profileBackgroundUrl) {
        mutableStateOf(profile.profileBackgroundUrl?.takeIf { it.isNotBlank() })
    }
    var selectedEditorTab by remember(profile.id) { mutableStateOf(ProfileEditorTab.Avatar) }
    // Changes stay here until Save, like the rest of the editor; null while untouched.
    var editedPreferences by remember(profile.id) { mutableStateOf<ProfileEditorPreferences?>(null) }
    var focusedAvatarName by remember { mutableStateOf<String?>(null) }
    val selectedAvatar = remember(avatarCatalog, selectedAvatarId) {
        avatarCatalog.find { it.id == selectedAvatarId }
    }
    val hasChangedAvatarSelection = selectedAvatarId != profile.avatarId
    val previewAvatarImageUrl = when {
        selectedAvatar != null -> selectedAvatar.imageUrl
        !hasChangedAvatarSelection -> profile.avatarUrl?.takeIf { it.isNotBlank() }
            ?: avatarUrlResolver(profile.avatarId)
        else -> null
    }
    val nameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { nameFocusRequester.requestFocus() }
    }

    // Backgrounds come from a sync server's catalog or a custom URL; without either there's only "Normal".
    val showBackgroundTab = hasProfileBackgroundAccess &&
        (profileBackgroundCatalog.isNotEmpty() || !profile.profileBackgroundUrl.isNullOrBlank())
    LaunchedEffect(showBackgroundTab) {
        if (!showBackgroundTab && selectedEditorTab == ProfileEditorTab.Background) {
            selectedEditorTab = ProfileEditorTab.Avatar
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    native.keyCode == AndroidKeyEvent.KEYCODE_BACK
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = ProfileSelectionSpacing.EditorPanelMaxWidth)
                .clip(RoundedCornerShape(FetchRadii.tokens.xl))
                .background(FetchTheme.colors.BackgroundElevated)
                .border(FetchTheme.spacing.hairline, FetchTheme.colors.Border, RoundedCornerShape(FetchRadii.tokens.xl))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(FetchTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header row: title left, save button right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = FetchTheme.spacing.xl),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)) {
                    Text(
                        text = stringResource(R.string.profile_edit_header),
                        color = FetchTheme.colors.TextSecondary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            textDirection = profile.name.contentTextDirection()
                        ),
                        color = Color.White
                    )
                }
                OverlayButton(
                    text = if (isSaving) stringResource(R.string.profile_saving)
                           else stringResource(R.string.profile_save),
                    isPrimary = true,
                    enabled = profileName.isNotBlank() && !isSaving,
                    onClick = {
                        onSaveProfile(
                            profile.copy(
                                name = profileName,
                                avatarColorHex = selectedColorHex,
                                avatarId = selectedAvatarId,
                                avatarUrl = if (hasChangedAvatarSelection) null else profile.avatarUrl,
                                profileBackgroundId = selectedBackgroundId,
                                profileBackgroundUrl = selectedBackgroundUrl
                            ),
                            editedPreferences
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .width(ProfileSelectionSpacing.EditorPreviewWidth)
                        .padding(start = FetchTheme.spacing.xl, top = FetchTheme.spacing.xl, end = FetchTheme.spacing.xl, bottom = FetchTheme.spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProfileAvatarCircle(
                        name = profileName.ifEmpty { "?" },
                        colorHex = selectedColorHex,
                        size = ProfileSelectionSpacing.EditorPreviewAvatarSize,
                        avatarImageUrl = previewAvatarImageUrl
                    )

                    Text(
                        text = profileName.ifBlank { stringResource(R.string.profile_name_placeholder) },
                        style = MaterialTheme.typography.headlineMedium.copy(
                            textDirection = profileName.contentTextDirection()
                        ),
                        color = if (profileName.isBlank()) FetchTheme.colors.TextSecondary else FetchTheme.colors.TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    ProfileNameField(
                        value = profileName,
                        onValueChange = { if (it.length <= 20) profileName = it },
                        focusRequester = nameFocusRequester
                    )

                    ProfileColorPicker(
                        selectedColorHex = selectedColorHex,
                        onColorSelected = { selectedColorHex = it }
                    )

                    OverlayButton(
                        text = stringResource(R.string.profile_cancel),
                        isPrimary = false,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDismiss
                    )
                }

                Spacer(modifier = Modifier.width(ProfileSelectionSpacing.EditorDividerSpacing))
                EditorSectionDivider()
                Spacer(modifier = Modifier.width(ProfileSelectionSpacing.EditorDividerSpacing))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
                ) {
                    ProfileEditorTabs(
                        selectedTab = selectedEditorTab,
                        showBackgroundTab = showBackgroundTab,
                        onTabSelected = { selectedEditorTab = it },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    when (selectedEditorTab) {
                        ProfileEditorTab.Avatar -> {
                            Text(
                                text = stringResource(R.string.profile_choose_avatar),
                                modifier = Modifier.fillMaxWidth(),
                                color = FetchTheme.colors.TextTertiary,
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center
                            )


                            if (avatarCatalog.isNotEmpty()) {
                                AvatarPickerGrid(
                                    avatars = avatarCatalog,
                                    selectedAvatarId = selectedAvatarId,
                                    onAvatarSelected = { avatar ->
                                        if (selectedAvatarId == avatar.id) {
                                            selectedAvatarId = null
                                            selectedColorHex = profile.avatarColorHex
                                        } else {
                                            selectedAvatarId = avatar.id
                                            avatar.bgColor?.let { selectedColorHex = it }
                                        }
                                    },
                                    onAvatarFocused = { avatar ->
                                        focusedAvatarName = avatar?.displayName
                                    },
                                    modifier = Modifier.heightIn(max = 280.dp)
                                )

                                Text(
                                    text = focusedAvatarName ?: stringResource(R.string.profile_avatar_focus_hint),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (focusedAvatarName != null) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextTertiary,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(RoundedCornerShape(FetchRadii.tokens.xl))
                                        .background(FetchTheme.colors.BackgroundCard)
                                        .border(
                                            FetchTheme.spacing.hairline,
                                            FetchTheme.colors.Border,
                                            RoundedCornerShape(FetchRadii.tokens.xl)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.profile_choose_avatar),
                                        color = FetchTheme.colors.TextTertiary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }

                        ProfileEditorTab.Background -> {
                            Text(
                                text = stringResource(R.string.profile_choose_background),
                                modifier = Modifier.fillMaxWidth(),
                                color = FetchTheme.colors.TextSecondary,
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center
                            )


                            ProfileBackgroundPicker(
                                backgrounds = profileBackgroundCatalog,
                                selectedBackgroundId = selectedBackgroundId,
                                customBackgroundUrl = profile.profileBackgroundUrl,
                                selectedBackgroundUrl = selectedBackgroundUrl,
                                standardBackgroundColor = parseProfileColor(selectedColorHex),
                                onStandardBackgroundSelected = {
                                    selectedBackgroundId = null
                                    selectedBackgroundUrl = null
                                },
                                onBackgroundSelected = { background ->
                                    selectedBackgroundId = if (
                                        selectedBackgroundUrl == null &&
                                        selectedBackgroundId == background.id
                                    ) {
                                        null
                                    } else {
                                        background.id
                                    }
                                    selectedBackgroundUrl = null
                                },
                                onCustomBackgroundSelected = {
                                    val customUrl = profile.profileBackgroundUrl
                                        ?.takeIf { it.isNotBlank() }
                                    if (
                                        selectedBackgroundId == null &&
                                        selectedBackgroundUrl == customUrl
                                    ) {
                                        selectedBackgroundUrl = null
                                    } else {
                                        selectedBackgroundId = null
                                        selectedBackgroundUrl = customUrl
                                    }
                                }
                            )
                        }

                        ProfileEditorTab.Preferences -> {
                            Text(
                                text = stringResource(R.string.profile_preferences_hint),
                                modifier = Modifier.fillMaxWidth(),
                                color = FetchTheme.colors.TextSecondary,
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center
                            )
                            ProfileEditorPreferencesPanel(
                                preferences = editedPreferences ?: preferences,
                                onPreferencesChange = { editedPreferences = it }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
        }
    }
}

@Composable
internal fun ProfilePinOverlay(
    state: ProfilePinOverlayState,
    isWorking: Boolean,
    errorMessage: String?,
    forgotPin: ForgotPinOption? = null,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val density = LocalDensity.current
    val keyboardState = rememberKeyboardVisibilityState()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember(state) { FocusRequester() }
    val forgotPinFocusRequester = remember(state) { FocusRequester() }
    val canFocusForgotPin = forgotPin?.onReset != null
    val isSingleEntryMode = state !is ProfilePinOverlayState.Set
    var pin by remember(state) { mutableStateOf("") }
    var draftPin by remember(state) { mutableStateOf<String?>(null) }
    var entryStage by remember(state) { mutableStateOf(ProfilePinEntryStage.Create) }
    var internalErrorMessage by remember(state) { mutableStateOf<String?>(null) }
    var isInputFocused by remember(state) { mutableStateOf(false) }
    var keyboardWasVisible by remember(state) { mutableStateOf(false) }
    val shakeOffset = remember(state) { androidx.compose.animation.core.Animatable(0f) }
    val fullMotion = FetchTheme.fullMotion
    val resolvedErrorMessage = if (isSingleEntryMode) errorMessage else internalErrorMessage ?: errorMessage
    val isErrorState = !resolvedErrorMessage.isNullOrEmpty()
    val mismatchMessage = stringResource(R.string.profile_pin_overlay_mismatch)
    val shouldUseCompactLayout = keyboardState.isVisible
    val headerFontSize by animateFloatAsState(
        targetValue = if (shouldUseCompactLayout) 28f else 36f,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinHeaderFontSize"
    )
    val headerLineHeight by animateFloatAsState(
        targetValue = if (shouldUseCompactLayout) 34f else 42f,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinHeaderLineHeight"
    )
    val supportFontSize by animateFloatAsState(
        targetValue = if (shouldUseCompactLayout) 16f else 20f,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinSupportFontSize"
    )
    val supportLineHeight by animateFloatAsState(
        targetValue = if (shouldUseCompactLayout) 24f else 28f,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinSupportLineHeight"
    )
    val topPadding by animateDpAsState(
        targetValue = if (shouldUseCompactLayout) 40.dp else FetchTheme.spacing.none,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinOverlayTopPadding"
    )
    val headingGap by animateDpAsState(
        targetValue = if (shouldUseCompactLayout) 10.dp else ProfileSelectionSpacing.PinKickerToHeading,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinHeadingGap"
    )
    val boxesGap by animateDpAsState(
        targetValue = if (shouldUseCompactLayout) FetchTheme.spacing.xl else ProfileSelectionSpacing.PinHeadingToBoxes,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinBoxesGap"
    )
    val supportGap by animateDpAsState(
        targetValue = if (shouldUseCompactLayout) 18.dp else ProfileSelectionSpacing.PinBoxesToSupport,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinSupportGap"
    )
    val backHintGap by animateDpAsState(
        targetValue = if (shouldUseCompactLayout) 10.dp else 14.dp,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinBackHintGap"
    )
    val contentBottomPadding = if (keyboardState.isVisible) {
        with(density) { keyboardState.bottomPx.toDp() } + 28.dp
    } else {
        28.dp
    }

    BackHandler(enabled = isInputFocused && keyboardState.isVisible) {
        focusManager.clearFocus(force = true)
    }

    BackHandler(onBack = onDismiss)

    suspend fun playErrorAnimation() {
        shakeOffset.snapTo(0f)
        // Reduced motion: the red PIN boxes and the message carry the error; the boxes don't shake.
        if (!fullMotion) return
        listOf(-22f, 18f, -14f, 10f, -6f, 0f).forEach { offset ->
            shakeOffset.animateTo(offset, animationSpec = tween(42))
        }
    }

    LaunchedEffect(state) {
        repeat(2) { withFrameNanos { } }
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(keyboardState.isVisible, isInputFocused) {
        if (keyboardState.isVisible) {
            keyboardWasVisible = true
        } else if (keyboardWasVisible && isInputFocused) {
            keyboardWasVisible = false
            focusManager.clearFocus(force = true)
        }
    }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrEmpty()) {
            pin = ""
            playErrorAnimation()
        }
    }

    LaunchedEffect(pin, entryStage, isWorking) {
        if (pin.length != ProfilePinLength || isWorking) return@LaunchedEffect
        // Clear pin before dispatching so the effect can't re-trigger with the
        // same input after isWorking flips back to false at the end of the RPC.
        if (isSingleEntryMode) {
            val submitted = pin
            pin = ""
            onSubmit(submitted)
        } else {
            if (entryStage == ProfilePinEntryStage.Create) {
                draftPin = pin
                pin = ""
                internalErrorMessage = null
                entryStage = ProfilePinEntryStage.Confirm
            } else if (draftPin == pin) {
                val submitted = pin
                pin = ""
                onSubmit(submitted)
            } else {
                pin = ""
                draftPin = null
                entryStage = ProfilePinEntryStage.Create
                internalErrorMessage = mismatchMessage
                playErrorAnimation()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { runCatching { focusRequester.requestFocus() } }
            )
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != AndroidKeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_BACK,
                    AndroidKeyEvent.KEYCODE_ESCAPE -> {
                        if (isInputFocused) {
                            focusManager.clearFocus(force = true)
                        } else {
                            onDismiss()
                        }
                        true
                    }

                    // The PIN field is invisible, so move between it and "Forgot PIN?" directly.
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (canFocusForgotPin && isInputFocused) {
                            runCatching { forgotPinFocusRequester.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (canFocusForgotPin && !isInputFocused) {
                            runCatching { focusRequester.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }

                    AndroidKeyEvent.KEYCODE_DEL,
                    AndroidKeyEvent.KEYCODE_CLEAR -> {
                        if (!isWorking && pin.isNotEmpty()) {
                            pin = pin.dropLast(1)
                            if (!errorMessage.isNullOrEmpty()) onClearError()
                            if (!isSingleEntryMode) internalErrorMessage = null
                        }
                        true
                    }

                    else -> {
                        val digit = keyCodeToDigit(native.keyCode)
                        if (digit != null && !isWorking && pin.length < ProfilePinLength) {
                            pin += digit
                            if (!errorMessage.isNullOrEmpty()) onClearError()
                            if (!isSingleEntryMode) internalErrorMessage = null
                            true
                        } else {
                            false
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val headingText = when {
            state is ProfilePinOverlayState.Unlock -> stringResource(R.string.profile_pin_overlay_unlock_heading, state.profile.name)
            state is ProfilePinOverlayState.VerifyCurrentForChange -> stringResource(R.string.profile_pin_overlay_change_verify_heading, state.profile.name)
            state is ProfilePinOverlayState.VerifyCurrentForRemove -> stringResource(R.string.profile_pin_overlay_remove_verify_heading, state.profile.name)
            state is ProfilePinOverlayState.VerifyCurrentForDelete -> stringResource(R.string.profile_pin_overlay_delete_verify_heading, state.profile.name)
            state is ProfilePinOverlayState.VerifyAccess -> stringResource(R.string.profile_pin_overlay_access_heading, state.profile.name)
            state is ProfilePinOverlayState.ResetVerifyParent -> stringResource(R.string.profile_pin_overlay_reset_heading, state.profile.name)
            state is ProfilePinOverlayState.ParentGate -> stringResource(R.string.parental_gate_heading, state.profile.name)
            entryStage == ProfilePinEntryStage.Confirm -> stringResource(R.string.profile_pin_overlay_confirm_heading)
            else -> stringResource(R.string.profile_pin_overlay_set_heading, state.profile.name)
        }
        val supportText = when {
            !resolvedErrorMessage.isNullOrEmpty() -> resolvedErrorMessage
            isWorking && isSingleEntryMode -> stringResource(R.string.profile_pin_verifying)
            isWorking -> stringResource(R.string.action_saving)
            state is ProfilePinOverlayState.Unlock -> stringResource(R.string.profile_pin_overlay_unlock_support)
            state is ProfilePinOverlayState.VerifyCurrentForChange -> stringResource(R.string.profile_pin_overlay_change_verify_support)
            state is ProfilePinOverlayState.VerifyCurrentForRemove -> stringResource(R.string.profile_pin_overlay_remove_verify_support)
            state is ProfilePinOverlayState.VerifyCurrentForDelete -> stringResource(R.string.profile_pin_overlay_delete_verify_support)
            state is ProfilePinOverlayState.VerifyAccess -> stringResource(R.string.profile_pin_overlay_access_support)
            state is ProfilePinOverlayState.ResetVerifyParent -> stringResource(R.string.profile_pin_overlay_reset_support, state.target.name)
            state is ProfilePinOverlayState.ParentGate && state.openProfile != null ->
                stringResource(R.string.parental_gate_open_support, state.openProfile.name)
            state is ProfilePinOverlayState.ParentGate -> stringResource(R.string.parental_gate_support)
            entryStage == ProfilePinEntryStage.Confirm -> stringResource(R.string.profile_pin_overlay_confirm_support)
            else -> stringResource(R.string.profile_pin_overlay_set_support)
        }
        val supportTextColor = if (isErrorState) {
            FetchStatusTones.errorText
        } else {
            FetchTheme.colors.TextSecondary
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ProfileSelectionSpacing.ScreenPaddingHorizontal),
            verticalArrangement = if (shouldUseCompactLayout) Arrangement.Top else Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Spacer(modifier = Modifier.height(headingGap))

            Text(
                text = headingText,
                color = FetchTheme.colors.TextPrimary,
                fontSize = headerFontSize.sp,
                lineHeight = headerLineHeight.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(boxesGap))

            ProfilePinBoxes(
                value = pin,
                isWorking = isWorking,
                isErrorState = isErrorState,
                isInputFocused = isInputFocused,
                compactMode = shouldUseCompactLayout,
                modifier = Modifier.graphicsLayer {
                    translationX = shakeOffset.value
                }
            )

            Spacer(modifier = Modifier.height(supportGap))

            Text(
                text = supportText,
                modifier = Modifier
                    .widthIn(max = ProfileSelectionSpacing.PinSupportMaxWidth)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = supportTextColor,
                fontSize = supportFontSize.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = supportLineHeight.sp
            )

            if (forgotPin != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val onReset = forgotPin.onReset
                if (onReset != null) {
                    OverlayButton(
                        text = forgotPin.label,
                        isPrimary = false,
                        enabled = !isWorking,
                        modifier = Modifier.focusRequester(forgotPinFocusRequester),
                        onClick = onReset
                    )
                } else {
                    Text(
                        text = forgotPin.label,
                        modifier = Modifier.widthIn(max = ProfileSelectionSpacing.PinSupportMaxWidth),
                        color = FetchTheme.colors.TextTertiary,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(backHintGap))

            Text(
                text = stringResource(R.string.profile_pin_overlay_back_hint),
                color = FetchTheme.colors.TextTertiary,
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(contentBottomPadding))
        }

        val pinProgressDescription = stringResource(R.string.cd_profile_pin_progress, pin.length, ProfilePinLength)
        BasicTextField(
            value = pin,
            onValueChange = { value ->
                if (!isWorking) {
                    pin = value.filter(Char::isDigit).take(ProfilePinLength)
                    if (!errorMessage.isNullOrEmpty()) onClearError()
                    if (!isSingleEntryMode) internalErrorMessage = null
                }
            },
            // No alpha = 0f here: Compose prunes fully transparent nodes from the accessibility
            // tree. Transparent text and cursor at hairline size already draw nothing.
            modifier = Modifier
                .size(FetchTheme.spacing.hairline)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    isInputFocused = state.isFocused
                }
                .semantics {
                    contentDescription = headingText
                    stateDescription = pinProgressDescription
                },
            singleLine = true,
            textStyle = TextStyle(color = Color.Transparent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            cursorBrush = SolidColor(Color.Transparent)
        )
    }
}

@Composable
private fun ProfilePinBoxes(
    value: String,
    isWorking: Boolean,
    isErrorState: Boolean,
    isInputFocused: Boolean,
    compactMode: Boolean,
    modifier: Modifier = Modifier
) {
    val boxSize by animateDpAsState(
        targetValue = if (compactMode) 90.dp else ProfileSelectionSpacing.PinBoxSize,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinBoxSize"
    )
    val boxGap by animateDpAsState(
        targetValue = if (compactMode) 10.dp else ProfileSelectionSpacing.PinBoxGap,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinBoxGap"
    )
    val activeCursorHeight by animateDpAsState(
        targetValue = if (compactMode) 28.dp else 40.dp,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinCursorHeight"
    )
    val dotSize by animateDpAsState(
        targetValue = if (compactMode) FetchTheme.spacing.md else FetchTheme.spacing.lg,
        animationSpec = tween(FetchMotion.tokens.durations.fast),
        label = "pinDotSize"
    )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(boxGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(ProfilePinLength) { index ->
            val isFilled = index < value.length
            val isActive = index == value.length.coerceAtMost(ProfilePinLength - 1) &&
                value.length < ProfilePinLength &&
                isInputFocused &&
                !isWorking
            val borderColor by animateColorAsState(
                targetValue = when {
                    isErrorState -> FetchStatusTones.errorBorder
                    isFilled -> Color.White.copy(alpha = 0.92f)
                    isActive -> Color.White
                    else -> Color.White.copy(alpha = 0.72f)
                },
                animationSpec = tween(FetchMotion.tokens.durations.quick),
                label = "pinBoxBorder"
            )
            val backgroundColor by animateColorAsState(
                targetValue = when {
                    isErrorState -> FetchStatusTones.errorContainer.copy(alpha = 0.76f)
                    isFilled -> Color.White.copy(alpha = 0.07f)
                    isActive -> Color.White.copy(alpha = 0.04f)
                    else -> Color.Transparent
                },
                animationSpec = tween(FetchMotion.tokens.durations.quick),
                label = "pinBoxBackground"
            )
            val borderWidth by animateDpAsState(
                targetValue = if (isActive || isErrorState) FetchTheme.spacing.xxs else FetchTheme.spacing.hairline,
                animationSpec = tween(FetchMotion.tokens.durations.quick),
                label = "pinBoxBorderWidth"
            )

            Box(
                modifier = Modifier
                    .size(boxSize)
                    .clip(RoundedCornerShape(FetchTheme.radii.xxs))
                    .background(backgroundColor)
                    .border(
                        border = if (isActive && !isErrorState) {
                            FetchTheme.focusRing.border(borderWidth)
                        } else {
                            BorderStroke(borderWidth, borderColor)
                        },
                        shape = RoundedCornerShape(FetchTheme.radii.xxs)
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isFilled -> {
                        Box(
                            modifier = Modifier
                                .size(dotSize)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }

                    isActive -> PinCursor(height = activeCursorHeight)
                }
            }
        }
    }
    }
}

/** The caret in the active PIN box. Its blink lives here and is read in the layer, so only the caret redraws. */
@Composable
private fun PinCursor(height: Dp) {
    val blink = if (FetchTheme.fullMotion) {
        rememberInfiniteTransition(label = "pinCursor").animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(FetchMotion.tokens.durations.slow),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pinCursorAlpha"
        )
    } else {
        null
    }
    Box(
        modifier = Modifier
            .width(FetchTheme.spacing.xxs)
            .height(height)
            .graphicsLayer { alpha = blink?.value ?: 1f }
            .background(Color.White)
    )
}

@Composable
private fun EditorSectionDivider() {
    Box(
        modifier = Modifier
            .padding(top = ProfileSelectionSpacing.EditorPreviewTopOffset)
            .width(FetchTheme.spacing.hairline)
            .height(ProfileSelectionSpacing.EditorDividerHeight)
            .background(FetchTheme.colors.Border.copy(alpha = 0.9f))
    )
}

@Composable
private fun ProfileNameField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) FetchTheme.colors.FocusBackground else Color.White.copy(alpha = 0.05f),
        animationSpec = tween(FetchMotion.tokens.durations.quick),
        label = "profileNameBackground"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) FetchTheme.spacing.xxs else FetchTheme.spacing.hairline,
        animationSpec = tween(FetchMotion.tokens.durations.quick),
        label = "profileNameBorderWidth"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProfileSelectionSpacing.EditorFieldRadius))
            .background(backgroundColor)
            .border(
                border = if (isFocused) {
                    FetchTheme.focusRing.border(borderWidth)
                } else {
                    BorderStroke(borderWidth, FetchTheme.colors.Border)
                },
                shape = RoundedCornerShape(ProfileSelectionSpacing.EditorFieldRadius)
            )
            .padding(horizontal = FetchTheme.spacing.lg, vertical = 14.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_name_placeholder),
                color = FetchTheme.colors.TextTertiary,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = Color.White,
                textDirection = TextDirection.Content
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.moveFocus(FocusDirection.Down)
                }
            ),
            cursorBrush = FetchTheme.focusRing.brush()
        )
    }
}

@Composable
private fun OverlayButton(
    text: String,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.White.copy(alpha = 0.04f)
            isPrimary && isFocused -> FetchTheme.colors.SecondaryVariant
            isPrimary -> FetchTheme.colors.Secondary
            isFocused -> FetchTheme.colors.FocusBackground
            else -> Color.White.copy(alpha = 0.06f)
        },
        animationSpec = tween(FetchMotion.tokens.durations.quick),
        label = "btnBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> FetchTheme.colors.Border
            isFocused -> FetchTheme.colors.FocusRing
            isPrimary -> FetchTheme.colors.Secondary
            else -> FetchTheme.colors.Border
        },
        animationSpec = tween(FetchMotion.tokens.durations.quick),
        label = "btnBorder"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) FetchTheme.spacing.xxs else FetchTheme.spacing.hairline,
        animationSpec = tween(FetchMotion.tokens.durations.quick),
        label = "btnBorderWidth"
    )
    val textColor = when {
        !enabled -> FetchTheme.colors.TextSecondary
        isPrimary && isFocused -> FetchTheme.colors.OnSecondaryVariant
        isFocused -> FetchTheme.colors.FocusContent
        isPrimary -> FetchTheme.colors.OnSecondary
        else -> FetchTheme.colors.TextPrimary
    }

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(FetchTheme.shapes.button)
            .background(bgColor)
            .border(
                border = if (isFocused && enabled) {
                    FetchTheme.focusRing.border(borderWidth)
                } else {
                    BorderStroke(borderWidth, borderColor)
                },
                shape = FetchTheme.shapes.button
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (enabled && native.action == AndroidKeyEvent.ACTION_UP && isProfileSelectKey(native.keyCode)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .semantics { role = Role.Button }
            .padding(horizontal = FetchTheme.spacing.xl, vertical = FetchTheme.spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            style = FetchTheme.textStyles.button,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** First palette colour no profile uses yet, so new profiles don't all look alike. */
internal fun nextProfileColor(profiles: List<UserProfile>): String {
    val used = profiles.map { it.avatarColorHex.uppercase() }.toSet()
    return PROFILE_AVATAR_COLORS.firstOrNull { it.uppercase() !in used }
        ?: PROFILE_AVATAR_COLORS[profiles.size % PROFILE_AVATAR_COLORS.size]
}

@Composable
private fun profileColorName(colorHex: String): String? {
    val res = when (colorHex.uppercase()) {
        "#E53935" -> R.string.cd_profile_color_crimson
        "#1E88E5" -> R.string.cd_profile_color_ocean
        "#8E24AA" -> R.string.cd_profile_color_violet
        "#43A047" -> R.string.cd_profile_color_emerald
        "#FFB300" -> R.string.cd_profile_color_amber
        "#D81B60" -> R.string.cd_profile_color_rose
        "#00ACC1" -> R.string.cd_profile_color_cyan
        "#6D4C41" -> R.string.cd_profile_color_brown
        else -> return null
    }
    return stringResource(res)
}

// One row so it fits the editor's left column on a 960x540dp screen.
@Composable
private fun ProfileColorPicker(
    selectedColorHex: String,
    onColorSelected: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PROFILE_AVATAR_COLORS.forEachIndexed { index, colorHex ->
            ProfileColorSwatch(
                colorHex = colorHex,
                colorName = profileColorName(colorHex)
                    ?: stringResource(R.string.cd_profile_color_position, index + 1, PROFILE_AVATAR_COLORS.size),
                isSelected = colorHex.equals(selectedColorHex, ignoreCase = true),
                onClick = { onColorSelected(colorHex) }
            )
        }
    }
}

@Composable
private fun ProfileColorSwatch(
    colorHex: String,
    colorName: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.25f else 1f,
        animationSpec = tween(FetchMotion.tokens.durations.quick),
        label = "swatchScale"
    )
    Box(
        modifier = Modifier
            .size(22.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused }
            .clip(CircleShape)
            .background(parseProfileColor(colorHex), CircleShape)
            .border(
                border = when {
                    isFocused -> FetchTheme.focusRing.border(FetchTheme.spacing.xxs)
                    isSelected -> BorderStroke(FetchTheme.spacing.xxs, Color.White)
                    else -> BorderStroke(FetchTheme.spacing.hairline, Color.White.copy(alpha = 0.2f))
                },
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .semantics {
                role = Role.RadioButton
                selected = isSelected
                contentDescription = colorName
            },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}
