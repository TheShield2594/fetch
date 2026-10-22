package com.fetch.tv.ui.screens.addon

import com.fetch.tv.core.streams.AddonHealth
import com.fetch.tv.core.streams.AddonHealthStatus
import com.fetch.tv.core.streams.AddonRequestOutcome
import com.fetch.tv.ui.theme.FetchPrimitives
import com.fetch.tv.ui.theme.FetchTheme

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.ExperienceMode
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.util.contentTextDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import com.fetch.tv.R
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.fetch.tv.ui.util.rememberLongPressKeyTracker
import com.fetch.tv.core.addon.needsConfiguration
import com.fetch.tv.core.addon.isConfigurable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

private sealed interface AddonExperienceModeState {
    data object Loading : AddonExperienceModeState
    data class Loaded(val mode: ExperienceMode?) : AddonExperienceModeState
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AddonManagerScreen(
    viewModel: AddonManagerViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onBackPress: () -> Unit = {},
    onNavigateToCatalogOrder: () -> Unit = {},
    onNavigateToCollections: () -> Unit = {},
    onNavigateToFilterRows: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val addonHealth by viewModel.addonHealth.collectAsState()
    val firstAddonToggleFocusRequester = remember { FocusRequester() }
    val experienceModeState by remember(viewModel) {
        viewModel.experienceMode.map<ExperienceMode?, AddonExperienceModeState> {
            AddonExperienceModeState.Loaded(it)
        }
    }.collectAsState(initial = AddonExperienceModeState.Loading)
    val experienceMode = (experienceModeState as? AddonExperienceModeState.Loaded)?.mode
    if (experienceModeState is AddonExperienceModeState.Loading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FetchTheme.colors.Background),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator()
        }
        return
    }
    val isEssential = experienceMode == ExperienceMode.ESSENTIAL
    val webConfigMode = viewModel.webConfigMode(experienceMode ?: ExperienceMode.ADVANCED)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val surfaceFocusRequester = remember { FocusRequester() }
    val installButtonFocusRequester = remember { FocusRequester() }
    val textFieldFocusRequester = remember { FocusRequester() }
    val deleteDialogFocusRequester = remember { FocusRequester() }
    var isEditing by remember { mutableStateOf(false) }
    var addonUrlPendingDeletion by remember { mutableStateOf<String?>(null) }
    val hasHomeVisibleCatalogs = remember(uiState.installedAddons) {
        uiState.installedAddons.any { addon ->
            addon.enabled && addon.catalogs.any { catalog -> !catalog.isSearchOnlyCatalog() }
        }
    }
    val manageFromPhoneSubtitle = if (webConfigMode == com.fetch.tv.core.server.AddonWebConfigMode.COLLECTIONS_ONLY) {
        stringResource(R.string.addon_manage_collections_from_phone_subtitle)
    } else {
        stringResource(R.string.addon_manage_from_phone_subtitle)
    }
    val qrInstruction = if (webConfigMode == com.fetch.tv.core.server.AddonWebConfigMode.COLLECTIONS_ONLY) {
        stringResource(R.string.addon_qr_collections_scan_instruction)
    } else {
        stringResource(R.string.addon_qr_scan_instruction)
    }

    BackHandler { onBackPress() }

    val defaultRefreshAddonsSubtitle = stringResource(R.string.addon_refresh_default_subtitle)
    val refreshedAddonsSubtitle = stringResource(R.string.addon_refresh_done_subtitle)
    var refreshAddonsSubtitle by remember(defaultRefreshAddonsSubtitle) {
        mutableStateOf(defaultRefreshAddonsSubtitle)
    }

    LaunchedEffect(refreshAddonsSubtitle) {
        if (refreshAddonsSubtitle != defaultRefreshAddonsSubtitle) {
            delay(5_000)
            refreshAddonsSubtitle = defaultRefreshAddonsSubtitle
        }
    }

    // When isEditing changes to true, focus the text field and show keyboard
    LaunchedEffect(isEditing) {
        if (isEditing) {
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val requestInputBarFocus = {
        coroutineScope.launch {
            repeat(2) { withFrameNanos { } }
            runCatching { surfaceFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(uiState.isQrModeActive, uiState.pendingChange, addonUrlPendingDeletion) {
        if (!uiState.isQrModeActive && uiState.pendingChange == null && !isEditing && addonUrlPendingDeletion == null) {
            requestInputBarFocus()
        }
    }

    LaunchedEffect(addonUrlPendingDeletion) {
        if (addonUrlPendingDeletion != null) {
            repeat(2) { withFrameNanos { } }
            runCatching { deleteDialogFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(uiState.transientMessage) {
        if (uiState.transientMessage != null) {
            delay(3200)
            viewModel.clearTransientMessage()
        }
    }

    DisposableEffect(lifecycleOwner, uiState.isQrModeActive, uiState.pendingChange, addonUrlPendingDeletion) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                !uiState.isQrModeActive &&
                uiState.pendingChange == null &&
                !isEditing &&
                addonUrlPendingDeletion == null
            ) {
                requestInputBarFocus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopQrMode() }
    }

    val addonListScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.addon_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (showBuiltInHeader) FetchTheme.colors.TextPrimary else Color.Transparent
                )
            }

            if (viewModel.isReadOnly) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = FetchTheme.colors.BackgroundElevated),
                        shape = RoundedCornerShape(FetchTheme.radii.md)
                    ) {
                        Text(
                            text = stringResource(R.string.addon_readonly_notice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = FetchTheme.colors.TextSecondary,
                            modifier = androidx.compose.ui.Modifier.padding(FetchTheme.spacing.lg)
                        )
                    }
                }
            }

            if (!viewModel.isReadOnly) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        colors = CardDefaults.cardColors(containerColor = FetchTheme.colors.BackgroundCard),
                        shape = RoundedCornerShape(FetchTheme.radii.md)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = stringResource(R.string.addon_install_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                                color = FetchTheme.colors.TextPrimary,
                            )
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Surface always stays in the tree for stable D-pad focus
                                Surface(
                                    onClick = { isEditing = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(surfaceFocusRequester),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = FetchTheme.colors.BackgroundElevated,
                                        focusedContainerColor = FetchTheme.colors.BackgroundElevated
                                    ),
                                    border = ClickableSurfaceDefaults.border(
                                        border = Border(
                                            border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border),
                                            shape = RoundedCornerShape(FetchTheme.radii.md)
                                        ),
                                        focusedBorder = Border(
                                            border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                            shape = RoundedCornerShape(FetchTheme.radii.md)
                                        )
                                    ),
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.md)),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                                ) {
                                    Box(modifier = Modifier.padding(FetchTheme.spacing.md)) {
                                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        BasicTextField(
                                            value = uiState.installUrl,
                                            onValueChange = viewModel::onInstallUrlChange,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(textFieldFocusRequester)
                                                .onFocusChanged {
                                                    if (!it.isFocused && isEditing) {
                                                        isEditing = false
                                                        keyboardController?.hide()
                                                    }
                                                },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Uri,
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    viewModel.installAddon()
                                                    isEditing = false
                                                    keyboardController?.hide()
                                                    installButtonFocusRequester.requestFocus()
                                                }
                                            ),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = FetchTheme.colors.TextPrimary,
                                            textDirection = TextDirection.Content
                                            ),
                                            cursorBrush = SolidColor(if (isEditing) FetchTheme.colors.Primary else Color.Transparent),
                                            decorationBox = { innerTextField ->
                                                if (uiState.installUrl.isEmpty()) {
                                                    Text(
                                                        text = stringResource(R.string.addon_install_placeholder),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = FetchTheme.colors.TextTertiary
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        )
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        viewModel.installAddon()
                                        isEditing = false
                                        keyboardController?.hide()
                                        installButtonFocusRequester.requestFocus()
                                    },
                                    enabled = !uiState.isInstalling,
                                    modifier = Modifier
                                        .focusRequester(installButtonFocusRequester)
                                        .semantics { role = Role.Button },
                                    colors = ButtonDefaults.colors(
                                        containerColor = FetchTheme.colors.BackgroundCard,
                                        contentColor = FetchTheme.colors.TextPrimary,
                                        focusedContainerColor = FetchTheme.colors.FocusBackground,
                                        focusedContentColor = FetchTheme.colors.Primary
                                    ),
                                    shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                                ) {
                                    Text(text = if (uiState.isInstalling) stringResource(R.string.addon_installing) else stringResource(R.string.addon_install_btn))
                                }
                            }

                            AnimatedVisibility(visible = uiState.error != null) {
                                Text(
                                    text = uiState.error.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = FetchTheme.colors.Error,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                            }
                        }
                    }
                }

            }

            item {
                ManageFromPhoneCard(
                    subtitle = manageFromPhoneSubtitle,
                    onClick = { viewModel.startQrMode(webConfigMode) }
                )
            }

            if (!viewModel.isReadOnly && !isEssential && hasHomeVisibleCatalogs) {
                item {
                    CatalogOrderEntryCard(onClick = onNavigateToCatalogOrder)
                }
            }

            if (!isEssential) {
                item {
                    CollectionsEntryCard(onClick = onNavigateToCollections)
                }
                item {
                    FilterRowsEntryCard(onClick = onNavigateToFilterRows)
                }
            }

            item {
                RefreshAddonsEntryCard(
                    subtitle = refreshAddonsSubtitle,
                    onClick = {
                        viewModel.requestAddonSyncNow()
                        refreshAddonsSubtitle = refreshedAddonsSubtitle
                    },
                    modifier = Modifier.focusProperties {
                        down = firstAddonToggleFocusRequester
                    }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.addon_installed_section),
                        style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                        color = FetchTheme.colors.TextPrimary,
                    )
                    Spacer(modifier = Modifier.width(FetchTheme.spacing.md))
                    if (uiState.isLoading && uiState.installedAddons.isEmpty()) {
                        LoadingIndicator(modifier = Modifier.height(FetchTheme.spacing.xl))
                    }
                }
            }

            if (uiState.installedAddons.isEmpty() && !uiState.isLoading) {
                item {
                    Text(
                        text = stringResource(R.string.addon_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = FetchTheme.colors.TextSecondary
                    )
                }
            } else {
                itemsIndexed(
                    items = uiState.installedAddons,
                    key = { _, addon -> addon.baseUrl }
                ) { index, addon ->
                    val bringIntoViewRequester = remember { BringIntoViewRequester() }
                    AddonCard(
                        modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester),
                        addon = addon,
                        health = addonHealth[addon.displayName],
                        canMoveUp = index > 0,
                        canMoveDown = index < uiState.installedAddons.lastIndex,
                        onMoveUp = {
                            viewModel.moveAddonUp(addon.baseUrl)
                            addonListScope.launch {
                                kotlinx.coroutines.delay(50)
                                bringIntoViewRequester.bringIntoView()
                            }
                        },
                        onMoveDown = {
                            viewModel.moveAddonDown(addon.baseUrl)
                            addonListScope.launch {
                                kotlinx.coroutines.delay(50)
                                bringIntoViewRequester.bringIntoView()
                            }
                        },
                        onRemove = { addonUrlPendingDeletion = addon.baseUrl },
                        onEnabledChange = { enabled -> viewModel.setAddonEnabled(addon.baseUrl, enabled) },
                        onConfigure = if (addon.isConfigurable) {
                            { viewModel.configureAddon(addon) }
                        } else null,
                        isReadOnly = viewModel.isReadOnly,
                        showReorder = !isEssential,
                        toggleFocusRequester = if (index == 0) firstAddonToggleFocusRequester else null
                    )
                }
            }
        }

        // QR Code overlay — Popup renders above the entire screen
        if (uiState.isQrModeActive) {
            Popup(properties = PopupProperties(focusable = true)) {
                QrCodeOverlay(
                    qrBitmap = uiState.qrCodeBitmap,
                    serverUrl = uiState.serverUrl,
                    instruction = qrInstruction,
                    onClose = viewModel::stopQrMode,
                    hasPendingChange = uiState.pendingChange != null
                )
            }
        }

        uiState.configureTarget?.let { target ->
            Popup(properties = PopupProperties(focusable = true)) {
                AddonConfigureOverlay(
                    target = target,
                    statusMessage = uiState.transientMessage,
                    statusIsError = uiState.transientMessageIsError,
                    onConfirm = viewModel::confirmConfiguredUrl,
                    onReject = viewModel::rejectConfiguredUrl,
                    onClose = viewModel::stopConfigure
                )
            }
        }

        // Confirmation dialog overlay
        if (uiState.pendingChange != null) {
            Popup(properties = PopupProperties(focusable = true)) {
                uiState.pendingChange?.let { pending ->
                    ConfirmAddonChangesDialog(
                        pendingChange = pending,
                        onConfirm = viewModel::confirmPendingChange,
                        onReject = viewModel::rejectPendingChange
                    )
                }
            }
        }

        addonUrlPendingDeletion?.let { addonUrl ->
            FetchDialog(
                onDismiss = { addonUrlPendingDeletion = null },
                title = stringResource(R.string.addon_delete_confirm_title),
                subtitle = stringResource(R.string.addon_delete_confirm_message),
                suppressFirstKeyUp = false
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md, Alignment.End)
                ) {
                    Button(
                        onClick = { addonUrlPendingDeletion = null },
                        modifier = Modifier
                            .focusRequester(deleteDialogFocusRequester)
                            .semantics { role = Role.Button }
                    ) {
                        Text(stringResource(R.string.addon_delete_no))
                    }
                    Button(
                        onClick = {
                            viewModel.removeAddon(addonUrl)
                            addonUrlPendingDeletion = null
                        },
                        modifier = Modifier.semantics { role = Role.Button }
                    ) {
                        Text(stringResource(R.string.addon_delete_yes))
                    }
                }
            }
        }

        AddonMessageOverlay(
            message = uiState.transientMessage,
            isError = uiState.transientMessageIsError
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddonMessageOverlay(
    message: String?,
    isError: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(FetchTheme.spacing.xl),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val visibleMessage = message ?: return@AnimatedVisibility
            Surface(
                onClick = { },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isError) {
                        FetchPrimitives.red600.copy(alpha = 0.92f)
                    } else {
                        FetchPrimitives.green700.copy(alpha = 0.92f)
                    }
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = FetchTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Close else Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Text(
                        text = visibleMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageFromPhoneCard(
    subtitle: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .semantics { role = Role.Button },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.sm)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) FetchTheme.colors.Secondary else FetchTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.width(FetchTheme.spacing.lg))
                Column {
                    Text(
                        text = stringResource(R.string.addon_manage_from_phone_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                        color = FetchTheme.colors.TextPrimary,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = FetchTheme.colors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatalogOrderEntryCard(onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .semantics { role = Role.Button },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.sm)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Reorder,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) FetchTheme.colors.Secondary else FetchTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.width(FetchTheme.spacing.lg))
                Column {
                    Text(
                        text = stringResource(R.string.addon_reorder_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                        color = FetchTheme.colors.TextPrimary,
                    )
                    Text(
                        text = stringResource(R.string.addon_reorder_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = FetchTheme.colors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionsEntryCard(onClick: () -> Unit) {
    NavigationEntryCard(
        icon = Icons.Default.FolderOpen,
        title = stringResource(R.string.collections_card_title),
        subtitle = stringResource(R.string.collections_card_subtitle),
        onClick = onClick
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterRowsEntryCard(onClick: () -> Unit) {
    NavigationEntryCard(
        icon = Icons.Default.FilterList,
        title = stringResource(R.string.filter_rows_title),
        subtitle = stringResource(R.string.filter_rows_card_subtitle),
        onClick = onClick
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavigationEntryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .semantics { role = Role.Button },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.sm)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) FetchTheme.colors.Secondary else FetchTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.width(FetchTheme.spacing.lg))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                        color = FetchTheme.colors.TextPrimary,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = FetchTheme.colors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RefreshAddonsEntryCard(
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .semantics { role = Role.Button },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.sm)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) FetchTheme.colors.Secondary else FetchTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.width(FetchTheme.spacing.lg))
                Column {
                    Text(
                        text = stringResource(R.string.addon_refresh_action),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                        color = FetchTheme.colors.TextPrimary,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = FetchTheme.colors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun QrCodeOverlay(
    qrBitmap: Bitmap?,
    serverUrl: String?,
    instruction: String,
    onClose: () -> Unit,
    hasPendingChange: Boolean = false,
    qrSize: Dp = 220.dp
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(hasPendingChange) {
        if (!hasPendingChange) {
            focusRequester.requestFocus()
        }
    }

    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))

            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_qr_code),
                    modifier = Modifier.size(qrSize),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(FetchTheme.spacing.md))

            if (serverUrl != null) {
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.TextTertiary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))

            Surface(
                onClick = onClose,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .semantics { role = Role.Button },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = FetchTheme.colors.Surface,
                    focusedContainerColor = FetchTheme.colors.FocusBackground
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                        shape = RoundedCornerShape(50)
                    )
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = FetchTheme.spacing.xl, vertical = FetchTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = FetchTheme.colors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.addon_qr_close),
                        color = FetchTheme.colors.TextPrimary,
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ConfirmAddonChangesDialog(
    pendingChange: PendingChangeInfo,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BackHandler { onReject() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = { },
            modifier = Modifier
                .width(560.dp)
                .heightIn(max = 640.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = FetchTheme.colors.SurfaceVariant
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.xl))
        ) {
            Column(
                modifier = Modifier.padding(FetchTheme.spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.addon_confirm_title),
                    style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
                    color = FetchTheme.colors.TextPrimary,
                )

                Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))

                Text(
                    text = stringResource(R.string.addon_confirm_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary
                )

                Spacer(modifier = Modifier.height(FetchTheme.spacing.md))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(
                            color = FetchTheme.colors.Surface,
                            shape = RoundedCornerShape(FetchTheme.radii.md)
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FetchTheme.spacing.md)
                            .verticalScroll(scrollState)
                    ) {
                        if (pendingChange.addedUrls.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_added),
                                style = MaterialTheme.typography.titleSmall,
                                color = FetchTheme.colors.Success,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = FetchTheme.spacing.xs)
                            )
                            pendingChange.addedUrls.forEach { url ->
                                val displayName = pendingChange.addedNames[url] ?: url
                                Text(
                                    text = "+ $displayName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FetchTheme.colors.Success,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = FetchTheme.spacing.sm, bottom = FetchTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                        }

                        if (pendingChange.removedUrls.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_removed),
                                style = MaterialTheme.typography.titleSmall,
                                color = FetchTheme.colors.Error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = FetchTheme.spacing.xs)
                            )
                            pendingChange.removedUrls.forEach { url ->
                                val displayName = pendingChange.removedNames[url] ?: url
                                Text(
                                    text = "- $displayName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FetchTheme.colors.Error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = FetchTheme.spacing.sm, bottom = FetchTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                        }

                        if (pendingChange.catalogsReordered) {
                            Text(
                                text = stringResource(R.string.addon_confirm_catalog_reordered),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FetchTheme.colors.TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            )
                        }

                        if (pendingChange.disabledCatalogNames.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_catalogs_disabled),
                                style = MaterialTheme.typography.titleSmall,
                                color = FetchTheme.colors.Error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = FetchTheme.spacing.xs)
                            )
                            pendingChange.disabledCatalogNames.forEach { name ->
                                Text(
                                    text = "- $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FetchTheme.colors.Error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = FetchTheme.spacing.sm, bottom = FetchTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                        }

                        if (pendingChange.enabledCatalogNames.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_catalogs_enabled),
                                style = MaterialTheme.typography.titleSmall,
                                color = FetchTheme.colors.Success,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = FetchTheme.spacing.xs)
                            )
                            pendingChange.enabledCatalogNames.forEach { name ->
                                Text(
                                    text = "+ $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FetchTheme.colors.Success,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = FetchTheme.spacing.sm, bottom = FetchTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                        }

                        if (pendingChange.collectionsChanged) {
                            Text(
                                text = stringResource(R.string.addon_pending_collections_updated),
                                style = MaterialTheme.typography.titleSmall,
                                color = FetchTheme.colors.TextPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = FetchTheme.spacing.xs)
                            )
                            Text(
                                text = stringResource(R.string.addon_pending_collections_replace, pendingChange.proposedCollectionCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = FetchTheme.colors.TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = FetchTheme.spacing.sm, bottom = FetchTheme.spacing.xxs)
                            )
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                        }

                        if (
                            pendingChange.addedUrls.isEmpty() &&
                            pendingChange.removedUrls.isEmpty() &&
                            !pendingChange.catalogsReordered &&
                            pendingChange.disabledCatalogNames.isEmpty() &&
                            pendingChange.enabledCatalogNames.isEmpty() &&
                            !pendingChange.collectionsChanged
                        ) {
                            Text(
                                text = stringResource(R.string.addon_confirm_no_changes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FetchTheme.colors.TextSecondary
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.addon_confirm_total_addons, pendingChange.proposedUrls.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.TextTertiary,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.addon_confirm_total_catalogs, pendingChange.proposedCatalogOrderKeys.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.TextTertiary,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))

                if (pendingChange.isApplying) {
                    LoadingIndicator(modifier = Modifier.size(36.dp))
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
                    ) {
                        Surface(
                            onClick = onReject,
                            modifier = Modifier.semantics { role = Role.Button },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = FetchTheme.colors.Surface,
                                focusedContainerColor = FetchTheme.colors.FocusBackground
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                    shape = RoundedCornerShape(50)
                                )
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = FetchTheme.spacing.xl, vertical = FetchTheme.spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = FetchTheme.colors.TextPrimary
                                )
                                Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
                                Text(
                                    text = stringResource(R.string.addon_confirm_reject),
                                    color = FetchTheme.colors.TextPrimary,
                                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                                )
                            }
                        }

                        Surface(
                            onClick = onConfirm,
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .semantics { role = Role.Button },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = FetchTheme.colors.Secondary,
                                focusedContainerColor = FetchTheme.colors.SecondaryVariant
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                    shape = RoundedCornerShape(50)
                                )
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Text(
                                text = stringResource(R.string.addon_confirm_confirm),
                                modifier = Modifier.padding(horizontal = FetchTheme.spacing.xl, vertical = FetchTheme.spacing.md),
                                color = FetchTheme.colors.OnSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AddonCard(
    modifier: Modifier = Modifier,
    addon: Addon,
    health: AddonHealth? = null,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onConfigure: (() -> Unit)? = null,
    isReadOnly: Boolean = false,
    showReorder: Boolean = true,
    toggleFocusRequester: FocusRequester? = null
) {
    if (isReadOnly) {
        Surface(
            onClick = { },
            modifier = modifier
                .fillMaxWidth()
                .animateContentSize(),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                focusedContainerColor = FetchTheme.colors.BackgroundCard
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = RoundedCornerShape(FetchTheme.radii.md)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.md)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            AddonCardContent(addon = addon, health = health, isReadOnly = true)
        }
    } else {
        val internalToggleFocusRequester = remember { FocusRequester() }
        val effectiveToggleFocusRequester = toggleFocusRequester ?: internalToggleFocusRequester
        val longPressKeyTracker = rememberLongPressKeyTracker()

        Card(
            modifier = modifier
                .fillMaxWidth()
                .animateContentSize()
                .focusProperties {
                    enter = { effectiveToggleFocusRequester }
                }
                // Holding OK anywhere on a configurable addon's card opens its configuration.
                .onPreviewKeyEvent { event ->
                    val configure = onConfigure ?: return@onPreviewKeyEvent false
                    longPressKeyTracker.handle(event.nativeKeyEvent, ::isAddonCardSelectKey) { configure() }
                },
            colors = CardDefaults.cardColors(containerColor = FetchTheme.colors.BackgroundCard),
            shape = RoundedCornerShape(FetchTheme.radii.md)
        ) {
            AddonCardContent(
                addon = addon,
                health = health,
                isReadOnly = false,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove,
                onEnabledChange = onEnabledChange,
                onConfigure = onConfigure,
                showReorder = showReorder,
                toggleFocusRequester = effectiveToggleFocusRequester
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddonCardContent(
    addon: Addon,
    health: AddonHealth? = null,
    isReadOnly: Boolean,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onRemove: () -> Unit = {},
    onEnabledChange: (Boolean) -> Unit = {},
    onConfigure: (() -> Unit)? = null,
    showReorder: Boolean = true,
    toggleFocusRequester: FocusRequester? = null
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                    color = FetchTheme.colors.TextPrimary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (addon.version.isNotBlank()) {
                        Text(
                            text = "v${addon.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = FetchTheme.colors.TextSecondary
                        )
                    }
                    if (!addon.enabled) {
                        Text(
                            text = stringResource(R.string.addons_badge_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = FetchTheme.colors.TextSecondary
                        )
                    }
                    if (addon.needsConfiguration) {
                        Text(
                            text = stringResource(R.string.addon_badge_needs_setup),
                            style = MaterialTheme.typography.labelSmall,
                            color = FetchTheme.colors.Error
                        )
                    }
                    if (health != null && health.status != AddonHealthStatus.UNKNOWN) {
                        AddonHealthLabel(health)
                    }
                }
            }
            if (!isReadOnly) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { onEnabledChange(!addon.enabled) },
                        modifier = Modifier
                            .focusRequester(toggleFocusRequester ?: remember { FocusRequester() })
                            .semantics {
                                role = Role.Switch
                                toggleableState = if (addon.enabled) ToggleableState.On else ToggleableState.Off
                            },
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = FetchTheme.colors.FocusBackground
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(
                                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                shape = RoundedCornerShape(FetchTheme.radii.md)
                            )
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(FetchTheme.radii.md)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Switch(
                                checked = addon.enabled,
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = FetchTheme.colors.Secondary,
                                    checkedTrackColor = FetchTheme.colors.Secondary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                    if (showReorder) {
                        Button(
                            onClick = onMoveUp,
                            enabled = canMoveUp,
                            modifier = Modifier.semantics { role = Role.Button },
                            colors = ButtonDefaults.colors(
                                containerColor = FetchTheme.colors.BackgroundCard,
                                contentColor = FetchTheme.colors.TextSecondary,
                                focusedContainerColor = FetchTheme.colors.FocusBackground,
                                focusedContentColor = FetchTheme.colors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                        ) {
                            Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.cd_move_up))
                        }
                        Button(
                            onClick = onMoveDown,
                            enabled = canMoveDown,
                            modifier = Modifier.semantics { role = Role.Button },
                            colors = ButtonDefaults.colors(
                                containerColor = FetchTheme.colors.BackgroundCard,
                                contentColor = FetchTheme.colors.TextSecondary,
                                focusedContainerColor = FetchTheme.colors.FocusBackground,
                                focusedContentColor = FetchTheme.colors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                        ) {
                            Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.cd_move_down))
                        }
                    }
                    if (onConfigure != null) {
                        Button(
                            onClick = onConfigure,
                            modifier = Modifier.semantics { role = Role.Button },
                            colors = ButtonDefaults.colors(
                                containerColor = FetchTheme.colors.BackgroundCard,
                                contentColor = FetchTheme.colors.TextSecondary,
                                focusedContainerColor = FetchTheme.colors.FocusBackground,
                                focusedContentColor = FetchTheme.colors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                        ) {
                            Text(text = stringResource(R.string.addon_configure))
                        }
                    }
                    Button(
                        onClick = onRemove,
                        modifier = Modifier.semantics { role = Role.Button },
                        colors = ButtonDefaults.colors(
                            containerColor = FetchTheme.colors.BackgroundCard,
                            contentColor = FetchTheme.colors.TextSecondary,
                            focusedContainerColor = FetchTheme.colors.FocusBackground,
                            focusedContentColor = FetchTheme.colors.Error
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(FetchTheme.radii.md))
                    ) {
                        Text(text = stringResource(R.string.addon_remove))
                    }
                }
            }
        }

        if (!addon.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
            Text(
                text = addon.description ?: "",
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDirection = (addon.description ?: "").contentTextDirection()
                ),
                color = FetchTheme.colors.TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
        Text(
            text = addon.baseUrl,
            style = MaterialTheme.typography.bodySmall.copy(
                textDirection = addon.baseUrl.contentTextDirection()
            ),
            color = FetchTheme.colors.TextTertiary
        )

        Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
        Text(
            text = stringResource(R.string.addon_catalogs_types, addon.catalogs.size, addon.rawTypes.joinToString()),
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextTertiary
        )
    }
}

/** "● Slow · 7.4 s" — so a dead or sluggish addon is obvious instead of silently slowing searches. */
@Composable
private fun AddonHealthLabel(health: AddonHealth) {
    val seconds = (health.averageLatencyMs ?: health.lastLatencyMs ?: 0L) / 1000.0
    val latency = stringResource(R.string.addon_health_latency, seconds)
    val (color, text) = when (health.status) {
        AddonHealthStatus.FAILING -> FetchTheme.colors.Error to if (health.lastOutcome == AddonRequestOutcome.TIMEOUT) {
            stringResource(R.string.addon_health_timed_out)
        } else {
            stringResource(R.string.addon_health_failing)
        }
        AddonHealthStatus.SLOW -> FetchTheme.colors.Warning to stringResource(R.string.addon_health_slow, latency)
        else -> FetchTheme.colors.Secondary to stringResource(R.string.addon_health_ok, latency)
    }
    Text(
        text = "\u25CF $text",
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

private fun isAddonCardSelectKey(keyCode: Int): Boolean =
    keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * Configure an addon from the TV: one QR opens the addon's own `/configure` page on the phone,
 * the other opens the local "Send to TV" page that hands the configured link back.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddonConfigureOverlay(
    target: AddonConfigureTarget,
    statusMessage: String?,
    statusIsError: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val confirmFocusRequester = remember { FocusRequester() }
    val received = target.receivedUrl
    LaunchedEffect(received != null) {
        runCatching { (if (received != null) confirmFocusRequester else focusRequester).requestFocus() }
    }
    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.addon_configure_title, target.name),
                style = MaterialTheme.typography.headlineSmall,
                color = FetchTheme.colors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                AddonConfigureQrStep(
                    step = stringResource(R.string.addon_configure_step_open),
                    bitmap = target.configureQr,
                    url = target.configureUrl
                )
                if (target.sendToTvUrl != null) {
                    AddonConfigureQrStep(
                        step = stringResource(R.string.addon_configure_step_send),
                        bitmap = target.sendToTvQr,
                        url = target.sendToTvUrl
                    )
                }
            }
            Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))
            Text(
                text = when {
                    target.isInstalling -> stringResource(R.string.addon_installing)
                    !statusMessage.isNullOrBlank() -> statusMessage
                    target.sendToTvUrl == null -> stringResource(R.string.addon_configure_no_server)
                    else -> stringResource(R.string.addon_configure_waiting)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (statusIsError && !target.isInstalling && !statusMessage.isNullOrBlank()) {
                    FetchTheme.colors.Error
                } else {
                    FetchTheme.colors.TextSecondary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.width(620.dp)
            )
            if (received != null) {
                Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
                Text(
                    text = stringResource(R.string.addon_configure_received, received),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    modifier = Modifier.width(620.dp)
                )
                Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
                    Button(
                        onClick = onReject,
                        modifier = Modifier.semantics { role = Role.Button }
                    ) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .focusRequester(confirmFocusRequester)
                            .semantics { role = Role.Button }
                    ) { Text(stringResource(R.string.addon_configure_install)) }
                }
            }
            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
            Surface(
                onClick = onClose,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .semantics { role = Role.Button },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = FetchTheme.colors.Surface,
                    focusedContainerColor = FetchTheme.colors.FocusBackground
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                        shape = RoundedCornerShape(50)
                    )
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text(
                    text = stringResource(R.string.addon_qr_close),
                    color = FetchTheme.colors.TextPrimary,
                    modifier = Modifier.padding(horizontal = FetchTheme.spacing.xl, vertical = FetchTheme.spacing.md)
                )
            }
        }
    }
}

@Composable
private fun AddonConfigureQrStep(step: String, bitmap: Bitmap?, url: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(260.dp)
    ) {
        Text(
            text = step,
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
        Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_qr_code),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextTertiary,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}
