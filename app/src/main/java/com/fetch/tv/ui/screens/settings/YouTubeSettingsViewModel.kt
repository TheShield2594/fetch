package com.fetch.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.R
import com.fetch.tv.data.local.YouTubeAuthDataStore
import com.fetch.tv.data.local.YouTubeAuthState
import com.fetch.tv.data.local.YouTubeSettingsDataStore
import com.fetch.tv.data.youtube.YouTubeAccount
import com.fetch.tv.data.youtube.YouTubeService
import com.fetch.tv.data.youtube.YouTubeTokenPollResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class YouTubeConnectionMode {
    SIGNED_OUT,
    AWAITING_APPROVAL,
    SIGNED_IN
}

data class YouTubeSettingsUiState(
    val mode: YouTubeConnectionMode = YouTubeConnectionMode.SIGNED_OUT,
    val accountName: String? = null,
    val accountHandle: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val codeExpiresAtMillis: Long? = null,
    val pollIntervalSeconds: Int = 5,
    val isLoading: Boolean = false,
    val isPolling: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val accounts: List<YouTubeAccount> = emptyList(),
    val isLoadingAccounts: Boolean = false,
    val selectedPageId: String? = null,
    val sponsorBlockEnabled: Boolean = YouTubeSettingsDataStore.DEFAULT_SPONSOR_BLOCK_ENABLED,
    val watchHistoryEnabled: Boolean = YouTubeSettingsDataStore.DEFAULT_WATCH_HISTORY_ENABLED,
    val autoplayEnabled: Boolean = YouTubeSettingsDataStore.DEFAULT_AUTOPLAY_ENABLED
)

@HiltViewModel
class YouTubeSettingsViewModel @Inject constructor(
    private val youTubeService: YouTubeService,
    private val authDataStore: YouTubeAuthDataStore,
    private val settingsDataStore: YouTubeSettingsDataStore,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(YouTubeSettingsUiState())
    val uiState: StateFlow<YouTubeSettingsUiState> = _uiState.asStateFlow()

    /** Fires once each time a sign-in completes, so the screen can close its dialog. */
    private val _signedIn = MutableStateFlow(0)
    val signedIn: StateFlow<Int> = _signedIn.asStateFlow()

    private var connectJob: Job? = null
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            authDataStore.state.collectLatest(::applyAuthState)
        }
        viewModelScope.launch {
            combine(
                settingsDataStore.sponsorBlockEnabled,
                settingsDataStore.watchHistoryEnabled,
                settingsDataStore.autoplayEnabled,
                ::Triple
            ).collectLatest { (sponsorBlock, history, autoplay) ->
                _uiState.update {
                    it.copy(sponsorBlockEnabled = sponsorBlock, watchHistoryEnabled = history, autoplayEnabled = autoplay)
                }
            }
        }
    }

    fun onSponsorBlockToggle() {
        viewModelScope.launch { settingsDataStore.setSponsorBlockEnabled(!_uiState.value.sponsorBlockEnabled) }
    }

    fun onAutoplayToggle() {
        viewModelScope.launch { settingsDataStore.setAutoplayEnabled(!_uiState.value.autoplayEnabled) }
    }

    fun onWatchHistoryToggle() {
        viewModelScope.launch { settingsDataStore.setWatchHistoryEnabled(!_uiState.value.watchHistoryEnabled) }
    }

    /** Lists the account's channels for the picker. */
    fun loadAccounts() {
        if (_uiState.value.isLoadingAccounts) return
        _uiState.update { it.copy(isLoadingAccounts = true) }
        viewModelScope.launch {
            val accounts = runCatching { youTubeService.listAccounts() }.getOrDefault(emptyList())
            _uiState.update { it.copy(accounts = accounts, isLoadingAccounts = false) }
        }
    }

    fun onAccountSelected(account: YouTubeAccount) {
        viewModelScope.launch { youTubeService.selectAccount(account) }
    }

    fun onSignInClick() {
        if (connectJob?.isActive == true || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
        connectJob = viewModelScope.launch {
            try {
                val result = youTubeService.startDeviceAuth()
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        errorMessage = if (result.isFailure) context.getString(R.string.youtube_error_start_failed) else null
                    )
                }
            } finally {
                connectJob = null
                _uiState.update { if (it.isLoading) it.copy(isLoading = false) else it }
            }
        }
    }

    fun onRetryPolling() = startPollingIfNeeded(force = true)

    fun onCancelSignIn() {
        connectJob?.cancel()
        connectJob = null
        pollJob?.cancel()
        pollJob = null
        viewModelScope.launch {
            youTubeService.cancelDeviceAuth()
            _uiState.update {
                it.copy(isLoading = false, isPolling = false, statusMessage = null, errorMessage = null)
            }
        }
    }

    fun onSignOutClick() {
        pollJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            youTubeService.signOut()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusMessage = context.getString(R.string.youtube_signed_out_status)
                )
            }
        }
    }

    private fun applyAuthState(state: YouTubeAuthState) {
        val mode = when {
            state.isSignedIn -> YouTubeConnectionMode.SIGNED_IN
            !state.deviceCode.isNullOrBlank() -> YouTubeConnectionMode.AWAITING_APPROVAL
            else -> YouTubeConnectionMode.SIGNED_OUT
        }
        _uiState.update {
            it.copy(
                mode = mode,
                accountName = state.accountName,
                accountHandle = state.accountHandle,
                selectedPageId = state.pageId,
                userCode = state.userCode,
                verificationUrl = state.verificationUrl,
                codeExpiresAtMillis = state.deviceCodeExpiresAt,
                pollIntervalSeconds = state.pollInterval ?: 5,
                isPolling = if (mode == YouTubeConnectionMode.AWAITING_APPROVAL) it.isPolling else false
            )
        }
        // The poll loop ends itself once the code is used up, so it can report why.
        if (mode == YouTubeConnectionMode.AWAITING_APPROVAL) startPollingIfNeeded(force = false)
        if (mode == YouTubeConnectionMode.SIGNED_IN && state.accountName.isNullOrBlank()) {
            viewModelScope.launch { youTubeService.refreshAccount() }
        }
    }

    private fun startPollingIfNeeded(force: Boolean) {
        if (pollJob?.isActive == true && !force) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPolling = true,
                    errorMessage = null,
                    statusMessage = context.getString(R.string.youtube_waiting_approval)
                )
            }
            while (true) {
                val expiresAt = _uiState.value.codeExpiresAtMillis
                if (expiresAt != null && System.currentTimeMillis() >= expiresAt) {
                    youTubeService.cancelDeviceAuth()
                    stopPolling(error = context.getString(R.string.youtube_error_code_expired))
                    break
                }
                delay(_uiState.value.pollIntervalSeconds.coerceAtLeast(1) * 1000L)
                when (val poll = youTubeService.pollDeviceToken()) {
                    YouTubeTokenPollResult.Pending -> Unit
                    is YouTubeTokenPollResult.SlowDown -> _uiState.update {
                        it.copy(pollIntervalSeconds = poll.intervalSeconds)
                    }
                    YouTubeTokenPollResult.Expired -> {
                        stopPolling(error = context.getString(R.string.youtube_error_code_expired))
                        break
                    }
                    YouTubeTokenPollResult.Denied -> {
                        stopPolling(error = context.getString(R.string.youtube_error_denied))
                        break
                    }
                    is YouTubeTokenPollResult.Failed -> {
                        stopPolling(error = poll.reason)
                        break
                    }
                    is YouTubeTokenPollResult.Approved -> {
                        _uiState.update {
                            it.copy(
                                isPolling = false,
                                errorMessage = null,
                                statusMessage = context.getString(
                                    R.string.youtube_signed_in_as,
                                    poll.accountName ?: context.getString(R.string.youtube_account_fallback)
                                )
                            )
                        }
                        _signedIn.update { it + 1 }
                        break
                    }
                }
            }
        }
    }

    private fun stopPolling(error: String) {
        _uiState.update { it.copy(isPolling = false, statusMessage = null, errorMessage = error) }
    }
}
