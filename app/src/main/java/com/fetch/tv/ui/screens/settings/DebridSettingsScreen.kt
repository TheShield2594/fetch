@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import com.fetch.tv.ui.theme.FetchTheme

import android.content.Context
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.debrid.DebridDeviceAuthorization
import com.fetch.tv.core.debrid.DebridDeviceAuthorizationTokenResult
import com.fetch.tv.core.debrid.DebridProvider
import com.fetch.tv.core.debrid.DebridProviderAuthMethod
import com.fetch.tv.core.debrid.DebridProviders
import com.fetch.tv.core.qr.QrCodeGenerator
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.domain.model.DebridStreamAudioChannel
import com.fetch.tv.domain.model.DebridStreamAudioTag
import com.fetch.tv.domain.model.DebridStreamEncode
import com.fetch.tv.domain.model.DebridStreamLanguage
import com.fetch.tv.domain.model.DebridStreamPreferences
import com.fetch.tv.domain.model.DebridStreamQuality
import com.fetch.tv.domain.model.DebridStreamResolution
import com.fetch.tv.domain.model.DebridStreamSortCriterion
import com.fetch.tv.domain.model.DebridStreamSortDirection
import com.fetch.tv.domain.model.DebridStreamSortKey
import com.fetch.tv.domain.model.DebridStreamVisualTag
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.screens.addon.QrCodeOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun DebridSettingsContent(
    viewModel: DebridSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The name-template phone page is only served while this screen is open.
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopFormatterQrMode() }
    }
    // The first two rows are gated on a connected provider and cannot take focus without one,
    // so the entry requester goes to the first row that can accept it. Left on a gated row the
    // request is declined, focus stays on the category rail, and the next press walks the rail
    // instead of entering these options.
    //
    // The last of the three is the first account row, which is ungated and so always takes the
    // request. That rests on DebridProviders.visible() having something in it: it filters a fixed
    // list on a compile time flag, so it is empty only if every provider is hidden at once, and
    // this screen has nothing to show at all in that case.
    val entryOnCloudLibrary = uiState.hasCloudLibraryProvider
    val entryOnDebridToggle = !entryOnCloudLibrary && uiState.hasResolverProvider
    // The first visible account row, which is always enabled whatever is connected, so it is the
    // one row that can always take the entry requester.
    val entryOnFirstAccount = !entryOnCloudLibrary && !entryOnDebridToggle
    var activeApiKeyDialog by remember { mutableStateOf<String?>(null) }
    var activeDeviceAuthDialog by remember { mutableStateOf<String?>(null) }
    var showResolverPicker by remember { mutableStateOf(false) }
    var showPrepareCountDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activeResolverProvider = uiState.activeResolverProvider

    LaunchedEffect(uiState.serverError) {
        val error = uiState.serverError ?: return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.debrid_title),
            subtitle = stringResource(R.string.debrid_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val state = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = FetchTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "debrid_notice") {
                        DebridInfoText(text = stringResource(R.string.debrid_experimental_notice))
                    }

                    item(key = "debrid_cloud_library") {
                        SettingsToggleRow(
                            title = stringResource(R.string.debrid_cloud_library),
                            subtitle = stringResource(R.string.debrid_cloud_library_description),
                            checked = uiState.canUseCloudLibrary,
                            onToggle = { viewModel.setCloudLibraryEnabled(!uiState.cloudLibraryEnabled) },
                            modifier = Modifier
                                .padding(top = FetchTheme.spacing.xxs)
                                .then(
                                    if (initialFocusRequester != null && entryOnCloudLibrary) {
                                        Modifier.focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                ),
                            enabled = uiState.hasCloudLibraryProvider
                        )
                    }

                    item(key = "debrid_enabled") {
                        SettingsToggleRow(
                            title = stringResource(R.string.debrid_enable_title),
                            subtitle = stringResource(R.string.debrid_enable_subtitle),
                            checked = uiState.canResolvePlayableLinks,
                            onToggle = { viewModel.onEvent(DebridSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                            modifier = if (initialFocusRequester != null && entryOnDebridToggle) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            },
                            enabled = uiState.hasResolverProvider
                        )
                    }

                    if (uiState.canResolvePlayableLinks && uiState.resolverProviders.size > 1 && activeResolverProvider != null) {
                        item(key = "debrid_resolve_with") {
                            SettingsActionRow(
                                title = stringResource(R.string.debrid_resolve_with),
                                subtitle = stringResource(R.string.debrid_resolve_with_description),
                                value = activeResolverProvider.displayName,
                                onClick = { showResolverPicker = true },
                                enabled = true
                            )
                        }
                    }

                    if (!uiState.hasResolverProvider) {
                        item(key = "debrid_add_key_first") {
                            DebridInfoText(text = stringResource(R.string.debrid_add_key_first))
                        }
                    }

                    item(key = "debrid_account_section") {
                        SettingsSectionLabel(text = stringResource(R.string.debrid_section_account))
                    }

                    DebridProviders.visible().forEachIndexed { providerIndex, provider ->
                        item(key = "debrid_${provider.id}_api_key") {
                            SettingsActionRow(
                                title = provider.displayName,
                                subtitle = if (provider.authMethod == DebridProviderAuthMethod.DeviceCode) {
                                    stringResource(R.string.debrid_provider_device_description, provider.displayName)
                                } else {
                                    stringResource(R.string.debrid_provider_description, provider.displayName)
                                },
                                value = providerCredentialStatus(
                                    provider = provider,
                                    credential = uiState.apiKeyFor(provider.id),
                                    notSetLabel = stringResource(R.string.debrid_not_set),
                                    connectedLabel = stringResource(R.string.debrid_connected)
                                ),
                                onClick = {
                                    when (provider.authMethod) {
                                        DebridProviderAuthMethod.DeviceCode -> activeDeviceAuthDialog = provider.id
                                        DebridProviderAuthMethod.ApiKey -> activeApiKeyDialog = provider.id
                                    }
                                },
                                modifier = if (
                                    initialFocusRequester != null &&
                                    entryOnFirstAccount &&
                                    providerIndex == 0
                                ) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                                enabled = true
                            )
                        }
                    }

                    if (uiState.canResolvePlayableLinks) {
                        item(key = "debrid_instant_section") {
                            SettingsSectionLabel(text = stringResource(R.string.debrid_section_instant_playback))
                        }

                        item(key = "debrid_prepare_links") {
                            val prepareEnabled = uiState.instantPlaybackPreparationLimit > 0
                            SettingsToggleRow(
                                title = stringResource(R.string.debrid_prepare_instant_playback),
                                subtitle = stringResource(R.string.debrid_prepare_instant_playback_description),
                                checked = prepareEnabled,
                                onToggle = { viewModel.setInstantPlaybackPreparationEnabled(!prepareEnabled) },
                                enabled = true
                            )
                        }

                        if (uiState.instantPlaybackPreparationLimit > 0) {
                            item(key = "debrid_prepare_count") {
                                SettingsActionRow(
                                    title = stringResource(R.string.debrid_prepare_stream_count),
                                    subtitle = null,
                                    value = prepareCountLabel(uiState.instantPlaybackPreparationLimit),
                                    onClick = { showPrepareCountDialog = true },
                                    enabled = true
                                )
                            }
                        }
                    }

                    item(key = "debrid_formatting_section") {
                        SettingsSectionLabel(text = stringResource(R.string.debrid_section_formatting))
                    }

                    item(key = "debrid_formatter") {
                        SettingsActionRow(
                            title = stringResource(R.string.debrid_formatter_title),
                            subtitle = stringResource(R.string.debrid_formatter_subtitle),
                            value = stringResource(R.string.debrid_formatter_configure),
                            onClick = { viewModel.startFormatterQrMode() },
                            enabled = uiState.enabled
                        )
                    }

                    item(key = "debrid_formatter_reset") {
                        SettingsActionRow(
                            title = stringResource(R.string.debrid_formatter_reset_title),
                            subtitle = stringResource(R.string.debrid_formatter_reset_subtitle),
                            value = stringResource(R.string.layout_reset_default),
                            onClick = { viewModel.resetFormatterTemplates() },
                            enabled = uiState.enabled
                        )
                    }

                    if (uiState.canResolvePlayableLinks) {
                        item(key = "debrid_filters_section") {
                            SettingsSectionLabel(text = stringResource(R.string.debrid_section_filters))
                        }
                        item(key = "debrid_filters_moved") {
                            DebridInfoText(text = stringResource(R.string.debrid_stream_rules_moved))
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = state)
            }
        }
    }

    activeApiKeyDialog?.let { providerId ->
        DebridProviders.byId(providerId)?.let { provider ->
            DebridApiKeyDialog(
                title = stringResource(R.string.debrid_api_key_dialog_title, provider.displayName),
                subtitle = stringResource(R.string.debrid_api_key_dialog_subtitle, provider.displayName),
                placeholder = stringResource(R.string.debrid_api_key_dialog_placeholder, provider.displayName),
                currentValue = uiState.apiKeyFor(provider.id),
                viewModel = viewModel,
                onSave = { value, onSaved -> viewModel.validateAndSaveProviderApiKey(provider.id, value, onSaved) },
                onSaved = { activeApiKeyDialog = null },
                onClear = {
                    viewModel.validateAndSaveProviderApiKey(provider.id, "") {}
                    activeApiKeyDialog = null
                },
                onDismiss = { activeApiKeyDialog = null }
            )
        }
    }

    activeDeviceAuthDialog?.let { providerId ->
        DebridProviders.byId(providerId)?.let { provider ->
            DebridDeviceAuthDialog(
                provider = provider,
                currentValue = uiState.apiKeyFor(provider.id),
                viewModel = viewModel,
                onConnected = { token -> viewModel.saveProviderCredential(provider.id, token) },
                onDisconnect = { viewModel.saveProviderCredential(provider.id, "") },
                onDismiss = { activeDeviceAuthDialog = null }
            )
        }
    }

    if (showResolverPicker) {
        DebridResolverProviderDialog(
            providers = uiState.resolverProviders,
            selectedProviderId = uiState.activeResolverProvider?.id,
            onSelected = { providerId ->
                viewModel.setPreferredResolverProviderId(providerId)
                showResolverPicker = false
            },
            onDismiss = { showResolverPicker = false }
        )
    }

    if (showPrepareCountDialog) {
        DebridPrepareCountDialog(
            selectedLimit = uiState.instantPlaybackPreparationLimit,
            onLimitSelected = { limit ->
                viewModel.setInstantPlaybackPreparationLimit(limit)
                showPrepareCountDialog = false
            },
            onDismiss = { showPrepareCountDialog = false }
        )
    }

    if (uiState.isFormatterQrModeActive) {
        QrCodeOverlay(
            qrBitmap = uiState.formatterQrCodeBitmap,
            serverUrl = uiState.formatterServerUrl,
            instruction = stringResource(R.string.debrid_formatter_qr_instruction),
            onClose = { viewModel.stopFormatterQrMode() }
        )
    }
}

@Composable
private fun DebridInfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = FetchTheme.colors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = FetchTheme.spacing.sm)
    )
}

@Composable
private fun prepareCountLabel(limit: Int): String {
    return if (limit == 1) {
        stringResource(R.string.debrid_prepare_count_one)
    } else {
        stringResource(R.string.debrid_prepare_count_many, limit)
    }
}

@Composable
private fun DebridPrepareCountDialog(
    selectedLimit: Int,
    onLimitSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(1, 2, 3, 5)

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.debrid_prepare_stream_count),
        subtitle = stringResource(R.string.debrid_prepare_stream_count_warning),
        options = options.map { limit ->
            SettingsPickerOption(limit, prepareCountLabel(limit))
        },
        selectedValue = selectedLimit,
        onOptionSelected = onLimitSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 280.dp
    )
}

@Composable
private fun DebridResolverProviderDialog(
    providers: List<DebridProvider>,
    selectedProviderId: String?,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = selectedProviderId ?: providers.firstOrNull()?.id.orEmpty()

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.debrid_resolve_with),
        subtitle = stringResource(R.string.debrid_resolve_with_description),
        options = providers.map { provider ->
            SettingsPickerOption(provider.id, provider.displayName)
        },
        selectedValue = selected,
        onOptionSelected = onSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 280.dp
    )
}

@Composable
private fun DebridDeviceAuthDialog(
    provider: DebridProvider,
    currentValue: String,
    viewModel: DebridSettingsViewModel,
    onConnected: (String) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val isConnected = currentValue.isNotBlank()
    var restartNonce by remember(provider.id) { mutableStateOf(0) }
    var session by remember(provider.id, restartNonce, isConnected) { mutableStateOf<DebridDeviceAuthorization?>(null) }
    var isStarting by remember(provider.id, restartNonce, isConnected) { mutableStateOf(!isConnected) }
    var isPolling by remember(provider.id, restartNonce, isConnected) { mutableStateOf(false) }
    var statusMessage by remember(provider.id, restartNonce, isConnected) { mutableStateOf<String?>(null) }

    val startingMessage = stringResource(R.string.debrid_device_auth_starting)
    val waitingMessage = stringResource(R.string.debrid_device_auth_waiting)
    val failedMessage = stringResource(R.string.debrid_device_auth_failed)
    val missingConfigurationMessage = stringResource(R.string.debrid_device_auth_missing_configuration)
    val expiredMessage = stringResource(R.string.debrid_device_auth_expired)

    LaunchedEffect(provider.id, restartNonce, isConnected) {
        if (isConnected) {
            isStarting = false
            isPolling = false
            statusMessage = null
            session = null
            return@LaunchedEffect
        }
        isStarting = true
        isPolling = false
        statusMessage = null
        val startResult = runCatching {
            viewModel.startDeviceAuthorization(provider.id)
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }
        session = startResult.getOrNull()
        isStarting = false
        statusMessage = if (session == null) {
            startResult.exceptionOrNull()?.message?.takeIf { it.contains("PREMIUMIZE_CLIENT_ID") }
                ?.let { missingConfigurationMessage }
                ?: failedMessage
        } else {
            waitingMessage
        }
    }

    LaunchedEffect(session?.deviceCode, restartNonce, isConnected) {
        if (isConnected) return@LaunchedEffect
        val activeSession = session ?: return@LaunchedEffect
        while (true) {
            delay(activeSession.intervalSeconds.coerceAtLeast(1) * 1_000L)
            isPolling = true
            val result = runCatching {
                viewModel.redeemDeviceAuthorization(provider.id, activeSession.deviceCode)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                if (error.isCancelledHttpRequest()) {
                    DebridDeviceAuthorizationTokenResult.Pending
                } else {
                    DebridDeviceAuthorizationTokenResult.Failed(null)
                }
            }
            isPolling = false
            when (result) {
                is DebridDeviceAuthorizationTokenResult.Authorized -> {
                    onConnected(result.accessToken)
                    onDismiss()
                    return@LaunchedEffect
                }
                DebridDeviceAuthorizationTokenResult.Pending -> {
                    statusMessage = waitingMessage
                }
                DebridDeviceAuthorizationTokenResult.Expired -> {
                    statusMessage = expiredMessage
                    return@LaunchedEffect
                }
                is DebridDeviceAuthorizationTokenResult.Failed -> {
                    statusMessage = result.message.toDeviceAuthStatusMessage(failedMessage)
                    return@LaunchedEffect
                }
                DebridDeviceAuthorizationTokenResult.Unsupported -> {
                    statusMessage = failedMessage
                    return@LaunchedEffect
                }
            }
        }
    }

    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(
            if (isConnected) R.string.debrid_disconnect_provider else R.string.debrid_connect_provider,
            provider.displayName
        ),
        width = 620.dp,
        titleTextAlign = TextAlign.Center,
        suppressFirstKeyUp = false
    ) {
        if (isConnected) {
            Text(
                text = stringResource(R.string.debrid_device_auth_connected, provider.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (isStarting) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
                Text(
                    text = startingMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary
                )
            }
        } else {
            session?.let { activeSession ->
                val qrBitmap = remember(activeSession.friendlyVerificationUrl) {
                    runCatching { QrCodeGenerator.generate(activeSession.friendlyVerificationUrl, 420, margin = 1) }.getOrNull()
                }
                Text(
                    text = stringResource(R.string.debrid_device_auth_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_qr_code),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(196.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                DebridDeviceAuthCodes(
                    userCode = activeSession.userCode,
                    verificationUrl = activeSession.friendlyVerificationUrl,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            statusMessage?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPolling) {
                        LoadingIndicator(modifier = Modifier.size(FetchTheme.spacing.lg))
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message == failedMessage || message == expiredMessage || message == missingConfigurationMessage) {
                            FetchTheme.colors.Error
                        } else {
                            FetchTheme.colors.TextSecondary
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            if (isConnected) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.debrid_disconnect),
                    onClick = {
                        onDisconnect()
                        onDismiss()
                    },
                    primary = true
                )
            }
            if (!isConnected && !isStarting && session == null) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { restartNonce += 1 }
                )
            }
        }
    }
}

@Composable
private fun DebridDeviceAuthCodes(
    userCode: String,
    verificationUrl: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        Text(
            text = userCode,
            style = MaterialTheme.typography.headlineSmall,
            color = FetchTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = verificationUrl,
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextTertiary,
            textAlign = TextAlign.Center
        )
    }
}

private fun Throwable.isCancelledHttpRequest(): Boolean {
    val text = listOfNotNull(message, toString())
        .joinToString(" ")
        .lowercase()
    return "code=-999" in text ||
        ("nsurlerrordomain" in text && ("cancelled" in text || "canceled" in text))
}

private fun String?.toDeviceAuthStatusMessage(fallback: String): String {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return fallback
    val lower = value.lowercase()
    return if (
        value.length > 180 ||
        "exception in http request" in lower ||
        "nsurlerrordomain" in lower ||
        "userinfo=" in lower
    ) {
        fallback
    } else {
        value
    }
}

@Composable
private fun DebridApiKeyDialog(
    title: String,
    subtitle: String,
    placeholder: String,
    currentValue: String,
    viewModel: DebridSettingsViewModel,
    onSave: (String, () -> Unit) -> Unit,
    onSaved: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val validating by viewModel.validating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val submit = {
        if (!validating) {
            focusManager.clearFocus()
            keyboardController?.hide()
            onSave(value, onSaved)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.validationError.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    FetchDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = 700.dp,
        suppressFirstKeyUp = false
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundElevated,
                focusedContainerColor = FetchTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = FetchTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            when {
                                native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                    native.action == KeyEvent.ACTION_DOWN -> true
                                (native.keyCode == KeyEvent.KEYCODE_ENTER ||
                                    native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                                    native.action == KeyEvent.ACTION_DOWN -> {
                                    submit()
                                    true
                                }
                                else -> false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = FetchTheme.colors.TextPrimary,
                                            textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(
                        if (isInputFocused) FetchTheme.colors.Primary else Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = FetchTheme.colors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.action_clear),
                onClick = onClear
            )
            SettingsDialogActionButton(
                text = if (validating) stringResource(R.string.action_saving) else stringResource(R.string.action_save),
                onClick = { submit() },
                primary = true,
                enabled = !validating
            )
        }
    }
}

private fun maskDebridApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "****" else "******${trimmed.takeLast(4)}"
}

private fun providerCredentialStatus(
    provider: DebridProvider,
    credential: String,
    notSetLabel: String,
    connectedLabel: String
): String =
    when (provider.authMethod) {
        DebridProviderAuthMethod.DeviceCode -> if (credential.isBlank()) notSetLabel else connectedLabel
        DebridProviderAuthMethod.ApiKey -> maskDebridApiKey(credential, notSetLabel)
    }
