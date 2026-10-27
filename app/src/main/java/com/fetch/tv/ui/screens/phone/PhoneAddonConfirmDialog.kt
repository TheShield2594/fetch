package com.fetch.tv.ui.screens.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.server.PhoneAddonRequest
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.screens.settings.SettingsDialogActionButton
import com.fetch.tv.ui.screens.settings.SettingsDialogActionRow
import com.fetch.tv.ui.theme.FetchTheme

/**
 * Asks whoever is at the TV before an addon sent from the phone is installed, showing what its
 * manifest says it provides. Shown wherever the phone hub's code is on screen.
 */
@Composable
fun PhoneAddonConfirmDialog(viewModel: PhoneHubViewModel) {
    val request by viewModel.pendingAddon.collectAsStateWithLifecycle()
    request?.let { pending ->
        PhoneAddonConfirmContent(
            request = pending,
            onConfirm = viewModel::confirmPendingAddon,
            onReject = viewModel::rejectPendingAddon
        )
    }
}

@Composable
private fun PhoneAddonConfirmContent(
    request: PhoneAddonRequest,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    FetchDialog(
        onDismiss = onReject,
        title = stringResource(R.string.phone_addon_confirm_title),
        subtitle = request.name,
        width = 560.dp
    ) {
        request.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextPrimary,
                maxLines = 4
            )
        }
        val provides = request.resources.map { resourceLabel(it) }
        if (provides.isNotEmpty()) {
            Text(
                text = stringResource(R.string.phone_addon_confirm_provides, provides.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary
            )
        }
        if (request.catalogCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.phone_addon_confirm_catalogs, request.catalogCount, request.catalogCount),
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary
            )
        }
        Text(
            text = request.url,
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextTertiary,
            maxLines = 2
        )
        Text(
            text = stringResource(R.string.phone_addon_confirm_trust),
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextSecondary
        )
        if (request.installing) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.phone_addon_installing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary
                )
            }
        } else {
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.phone_addon_decline), onClick = onReject)
                SettingsDialogActionButton(text = stringResource(R.string.phone_addon_install), primary = true, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun resourceLabel(resource: String): String = when (resource) {
    "stream" -> stringResource(R.string.phone_addon_resource_streams)
    "catalog" -> stringResource(R.string.phone_addon_resource_catalogs)
    "meta" -> stringResource(R.string.phone_addon_resource_meta)
    "subtitles" -> stringResource(R.string.phone_addon_resource_subtitles)
    else -> resource
}
