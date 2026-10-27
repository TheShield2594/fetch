package com.fetch.tv.ui.screens.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fetch.tv.R
import com.fetch.tv.ui.screens.addon.QrCodeOverlay

/** One QR code for every phone page: remote and keyboard, accounts, ranking and more. */
@Composable
fun PhoneHubScreen(
    onBackPress: () -> Unit,
    viewModel: PhoneHubViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val qrCode by viewModel.qrCode.collectAsStateWithLifecycle()
    val pendingAddon by viewModel.pendingAddon.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        viewModel.onScreenOpened()
        onDispose { viewModel.onScreenClosed() }
    }

    val instruction = when {
        state.error != null -> state.error.orEmpty()
        state.pairedPhones > 0 -> pluralStringResource(R.plurals.phone_hub_paired, state.pairedPhones, state.pairedPhones)
        else -> stringResource(R.string.phone_hub_instruction)
    }

    QrCodeOverlay(
        qrBitmap = qrCode,
        serverUrl = state.url,
        instruction = instruction,
        onClose = onBackPress,
        hasPendingChange = pendingAddon != null,
        qrSize = 260.dp
    )
    PhoneAddonConfirmDialog(viewModel)
}
