package com.fetch.tv.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fetch.tv.R
import com.fetch.tv.data.youtube.YouTubeService
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.rememberRawSvgPainter

@Composable
fun YouTubeSettingsContent(
    viewModel: YouTubeSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val signedInCount by viewModel.signedIn.collectAsStateWithLifecycle()
    var showSignInDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val signedInToast = stringResource(R.string.youtube_signed_in_toast)
    val signedIn = state.mode == YouTubeConnectionMode.SIGNED_IN

    LaunchedEffect(signedInCount) {
        if (signedInCount > 0 && showSignInDialog) {
            showSignInDialog = false
            Toast.makeText(context, signedInToast, Toast.LENGTH_LONG).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.youtube_name),
            subtitle = stringResource(R.string.youtube_settings_subtitle)
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            val focusModifier = if (initialFocusRequester != null) {
                Modifier.focusRequester(initialFocusRequester)
            } else {
                Modifier
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = FetchTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (signedIn) {
                        item(key = "youtube_account") {
                            SettingsActionRow(
                                title = stringResource(R.string.youtube_account_title),
                                subtitle = stringResource(
                                    R.string.youtube_signed_in_as,
                                    state.accountName ?: stringResource(R.string.youtube_account_fallback)
                                ),
                                value = state.accountHandle,
                                onClick = {},
                                leadingRawIconRes = R.raw.youtube_logo,
                                modifier = focusModifier
                            )
                        }
                        item(key = "youtube_sign_out") {
                            SettingsActionRow(
                                title = stringResource(R.string.youtube_sign_out_title),
                                subtitle = stringResource(R.string.youtube_sign_out_subtitle),
                                onClick = viewModel::onSignOutClick,
                                enabled = !state.isLoading,
                                leadingIcon = Icons.AutoMirrored.Filled.Logout
                            )
                        }
                    } else {
                        item(key = "youtube_sign_in") {
                            SettingsActionRow(
                                title = stringResource(R.string.youtube_sign_in_title),
                                subtitle = stringResource(R.string.youtube_sign_in_subtitle),
                                onClick = {
                                    showSignInDialog = true
                                    if (state.mode == YouTubeConnectionMode.SIGNED_OUT) viewModel.onSignInClick()
                                },
                                leadingRawIconRes = R.raw.youtube_logo,
                                modifier = focusModifier
                            )
                        }
                    }
                    item(key = "youtube_note") {
                        SettingsNote(text = stringResource(R.string.youtube_settings_note))
                    }
                    state.statusMessage?.takeIf { !showSignInDialog }?.let { message ->
                        item(key = "youtube_status") { SettingsNote(text = message) }
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    if (showSignInDialog && !signedIn) {
        YouTubeSignInDialog(
            state = state,
            onStartSignIn = viewModel::onSignInClick,
            onRetryPolling = viewModel::onRetryPolling,
            onDismiss = {
                showSignInDialog = false
                viewModel.onCancelSignIn()
            }
        )
    }
}

@Composable
private fun YouTubeSignInDialog(
    state: YouTubeSettingsUiState,
    onStartSignIn: () -> Unit,
    onRetryPolling: () -> Unit,
    onDismiss: () -> Unit
) {
    FetchDialog(
        onDismiss = onDismiss,
        title = "",
        width = 720.dp,
        titleTextAlign = TextAlign.Center,
        suppressFirstKeyUp = false
    ) {
        val displayUrl = state.verificationUrl ?: YouTubeService.ACTIVATION_URL
        TrackingDeviceAuthContent(
            providerName = stringResource(R.string.youtube_name),
            logo = rememberRawSvgPainter(R.raw.youtube_logo, 40.dp),
            logoContentDescription = stringResource(R.string.cd_youtube_logo),
            qrContentDescription = stringResource(R.string.cd_youtube_qr),
            instruction = stringResource(R.string.youtube_sign_in_instruction),
            userCode = state.userCode,
            displayUrl = displayUrl,
            // The activate page doesn't take the code in its address, so the QR opens the page.
            qrUrl = displayUrl,
            expiresAtEpochMs = state.codeExpiresAtMillis,
            isLoading = state.isLoading,
            isPolling = state.isPolling,
            credentialsConfigured = true,
            statusMessage = state.statusMessage,
            errorMessage = state.errorMessage,
            missingCredentialsMessage = "",
            onStartConnection = onStartSignIn,
            onRetryPolling = onRetryPolling,
            onDismiss = onDismiss,
            logoLabel = stringResource(R.string.youtube_name)
        )
    }
}
