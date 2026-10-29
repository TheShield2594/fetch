package com.fetch.tv.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import com.fetch.tv.R
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.parental.ProfilePinManager
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.UserProfile
import com.fetch.tv.ui.theme.FetchTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Asks for the parent PIN before an action a restricted profile may not take on its own:
 * opening settings, managing addons or phone pages, or leaving the profile.
 */
fun interface ParentalGate {
    /** Runs [action] right away when no PIN is needed, otherwise after the parent PIN. */
    fun runGuarded(onCancel: () -> Unit, action: () -> Unit)

    fun runGuarded(action: () -> Unit) = runGuarded(onCancel = {}, action = action)

    /**
     * Runs [action] after the parent PIN whenever the active profile isn't the parent's, even
     * one with no limits: hiding a title from it is the parent's call.
     */
    fun runParentGuarded(action: () -> Unit) = runGuarded(action)

    /** Goes up each time an open gate locks again; see [ParentalControls.gateLocks]. */
    val locks: StateFlow<Int> get() = NeverLocked

    /**
     * Marks a guarded screen as open. The gate stays unlocked while one is, and locks again
     * when the last one closes.
     */
    fun openGuardedScreen(key: Any) {}

    fun closeGuardedScreen(key: Any) {}

    companion object {
        val Open = ParentalGate { _, action -> action() }
        private val NeverLocked: StateFlow<Int> = MutableStateFlow(0)
    }
}

val LocalParentalGate = staticCompositionLocalOf { ParentalGate.Open }

internal data class ParentalGateRequest(
    val parentProfile: UserProfile,
    val onUnlocked: () -> Unit,
    val onCancel: () -> Unit
)

@HiltViewModel
class ParentalGateViewModel @Inject constructor(
    private val parentalControls: ParentalControls,
    private val pinManager: ProfilePinManager,
    private val profileManager: ProfileManager
) : ViewModel(), ParentalGate {
    private val _request = MutableStateFlow<ParentalGateRequest?>(null)
    internal val request: StateFlow<ParentalGateRequest?> = _request.asStateFlow()

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _error = MutableStateFlow<GateError?>(null)
    val error: StateFlow<GateError?> = _error.asStateFlow()

    private val openGuardedScreens = mutableSetOf<Any>()

    override val locks: StateFlow<Int> = parentalControls.gateLocks

    sealed interface GateError {
        data object Incorrect : GateError
        data class Locked(val retryAfterSeconds: Int) : GateError
    }

    init {
        viewModelScope.launch {
            // An unlock never carries over to another profile.
            profileManager.activeProfileId.collect { parentalControls.lockGate() }
        }
    }

    fun needsGate(): Boolean = parentalControls.needsGate()

    override fun runGuarded(onCancel: () -> Unit, action: () -> Unit) =
        guard(parentalControls.guardPinProfileId(), onCancel, action)

    override fun runParentGuarded(action: () -> Unit) =
        guard(parentalControls.parentPinProfileId(), onCancel = {}, action = action)

    private fun guard(guardId: Int?, onCancel: () -> Unit, action: () -> Unit) {
        if (guardId == null || parentalControls.isGateUnlocked()) {
            action()
            return
        }
        val loaded = profileManager.profiles.value.firstOrNull { it.id == guardId }
        if (loaded != null) {
            ask(loaded, onCancel, action)
            return
        }
        // A PIN is set but the profile list hasn't caught up yet. Wait for it rather than
        // open the gate, and stay locked if the parent profile never shows up.
        viewModelScope.launch {
            val parent = withTimeoutOrNull(PARENT_PROFILE_WAIT_MS) {
                profileManager.profiles.mapNotNull { profiles -> profiles.firstOrNull { it.id == guardId } }.first()
            }
            if (parent != null) ask(parent, onCancel, action) else onCancel()
        }
    }

    private fun ask(parent: UserProfile, onCancel: () -> Unit, action: () -> Unit) {
        _error.value = null
        _request.value = ParentalGateRequest(parent, action, onCancel)
    }

    override fun openGuardedScreen(key: Any) {
        openGuardedScreens += key
    }

    override fun closeGuardedScreen(key: Any) {
        if (openGuardedScreens.remove(key) && openGuardedScreens.isEmpty()) parentalControls.lockGate()
    }

    fun submit(pin: String) {
        val current = _request.value ?: return
        if (_isWorking.value) return
        viewModelScope.launch {
            _isWorking.value = true
            val result = pinManager.verifyPin(current.parentProfile.id, pin).getOrNull()
            _isWorking.value = false
            when {
                result?.unlocked == true -> {
                    parentalControls.markGateUnlocked()
                    _request.value = null
                    _error.value = null
                    current.onUnlocked()
                    // A one-off action (hiding a title, leaving the profile) doesn't keep the
                    // gate open; only a guarded screen does, until it closes.
                    if (openGuardedScreens.isEmpty()) parentalControls.lockGate()
                }
                result != null && result.retryAfterSeconds > 0 -> _error.value = GateError.Locked(result.retryAfterSeconds)
                else -> _error.value = GateError.Incorrect
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun cancel() {
        val current = _request.value ?: return
        _request.value = null
        _error.value = null
        current.onCancel()
    }
}

/** Provides [LocalParentalGate] and draws the PIN prompt over [content] when one is pending. */
@Composable
fun ParentalGateHost(
    viewModel: ParentalGateViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val request by viewModel.request.collectAsStateWithLifecycle()
    val isWorking by viewModel.isWorking.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    CompositionLocalProvider(LocalParentalGate provides viewModel) {
        content()
    }

    val active = request ?: return
    val overlayState = remember(active) { ProfilePinOverlayState.ParentGate(active.parentProfile) }
    Dialog(
        onDismissRequest = viewModel::cancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FetchTheme.colors.Background)
        ) {
            ProfilePinOverlay(
                state = overlayState,
                isWorking = isWorking,
                errorMessage = when (val gateError = error) {
                    null -> null
                    ParentalGateViewModel.GateError.Incorrect -> context.getString(R.string.profile_pin_incorrect)
                    is ParentalGateViewModel.GateError.Locked ->
                        context.getString(R.string.profile_pin_locked, gateError.retryAfterSeconds)
                },
                // The gate asks for the main profile's PIN, which has no reset.
                forgotPin = ForgotPinOption(context.getString(R.string.profile_pin_forgot_main)),
                onClearError = viewModel::clearError,
                onDismiss = viewModel::cancel,
                onSubmit = viewModel::submit
            )
        }
    }
}

/**
 * Shows [content] only once the parent PIN has been entered, when the active profile needs
 * it. Backing out of the prompt calls [onCancel] (usually a back navigation).
 */
@Composable
fun ParentalGuardedRoute(
    onCancel: () -> Unit,
    content: @Composable () -> Unit
) {
    val gate = LocalParentalGate.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val key = remember(lifecycleOwner) { (lifecycleOwner as? NavBackStackEntry)?.id ?: Any() }
    // Open from the first composition until the back stack entry is gone, so moving on to
    // another guarded screen and back keeps the gate open, and leaving the last one locks it.
    DisposableEffect(gate, lifecycleOwner) {
        gate.openGuardedScreen(key)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) gate.closeGuardedScreen(key)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            if (lifecycleOwner !is NavBackStackEntry) {
                lifecycleOwner.lifecycle.removeObserver(observer)
                gate.closeGuardedScreen(key)
            }
        }
    }
    val locks by gate.locks.collectAsStateWithLifecycle()
    var unlockedAt by remember { mutableStateOf<Int?>(null) }
    // Asks again when the gate locks while the screen is up, such as after the app was in the
    // background.
    LaunchedEffect(gate, locks) {
        if (unlockedAt == locks) return@LaunchedEffect
        unlockedAt = null
        gate.runGuarded(onCancel = onCancel) { unlockedAt = locks }
    }
    if (unlockedAt == locks) content()
}

private const val PARENT_PROFILE_WAIT_MS = 2_000L
