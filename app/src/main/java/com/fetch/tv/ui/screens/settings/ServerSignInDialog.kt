@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.data.mediaserver.ServerConnection
import com.fetch.tv.data.mediaserver.ServerFailure
import com.fetch.tv.data.mediaserver.ServerProvider
import com.fetch.tv.data.mediaserver.ServerSignInMethod
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.screens.account.InputField
import com.fetch.tv.ui.theme.FetchTheme

internal data class ServerSignInRequest(
    val provider: ServerProvider,
    val address: String = "",
    val username: String = ""
)

@Composable
internal fun ServerSignInDialog(
    request: ServerSignInRequest,
    viewModel: MediaServersViewModel,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit
) {
    if (request.provider.signInMethod == ServerSignInMethod.LINK_CODE) {
        ServerLinkDialog(request.provider, viewModel, onConnected, onDismiss)
        return
    }
    val state by viewModel.signIn.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(request.address) }
    var username by remember { mutableStateOf(request.username) }
    var password by remember { mutableStateOf("") }
    val provider = request.provider
    val canConnect = !state.connecting && address.isNotBlank() && username.isNotBlank()
    val connect = {
        if (canConnect) {
            viewModel.connect(provider, address, username, password) { connection ->
                password = ""
                onConnected(connection)
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.resetSignIn() }

    FetchDialog(
        onDismiss = { if (!state.connecting) onDismiss() },
        title = stringResource(R.string.servers_sign_in_title, provider.displayName),
        subtitle = stringResource(R.string.servers_sign_in_subtitle, provider.displayName),
        width = 640.dp,
        scrollable = true
    ) {
        SignInField(label = stringResource(R.string.servers_address)) {
            InputField(
                value = address,
                onValueChange = { address = it },
                placeholder = stringResource(R.string.servers_address_hint),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SignInField(label = stringResource(R.string.servers_username)) {
            InputField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(R.string.servers_username),
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SignInField(label = stringResource(R.string.servers_password)) {
            InputField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.servers_password),
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeAction = connect,
                modifier = Modifier.fillMaxWidth()
            )
        }
        state.error?.let { failure ->
            Text(
                text = failure.signInMessage(provider),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.Error
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                enabled = !state.connecting
            )
            SettingsDialogActionButton(
                text = stringResource(if (state.connecting) R.string.servers_connecting else R.string.servers_connect),
                onClick = connect,
                primary = true,
                enabled = canConnect
            )
        }
    }
}

@Composable
private fun SignInField(label: String, field: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = FetchTheme.colors.TextSecondary
        )
        field()
    }
}

@Composable
internal fun ServerFailure.signInMessage(provider: ServerProvider): String = when (this) {
    ServerFailure.AUTH_REQUIRED -> stringResource(R.string.servers_error_auth)
    ServerFailure.NOT_FOUND -> stringResource(R.string.servers_error_address)
    ServerFailure.UNREACHABLE -> stringResource(R.string.servers_error_unreachable)
    ServerFailure.UNSUPPORTED -> stringResource(R.string.servers_error_unsupported, provider.displayName, provider.minimumVersion)
    ServerFailure.FORBIDDEN -> stringResource(R.string.servers_error_forbidden)
    ServerFailure.INCOMPLETE,
    ServerFailure.FAILED -> stringResource(R.string.servers_error_failed)
}
