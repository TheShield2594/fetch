@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import com.fetch.tv.ui.theme.FetchTheme

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.data.local.DEFAULT_INTRODB_API_URL
import com.fetch.tv.data.local.SkipMarkerSettingsDataStore
import com.fetch.tv.data.local.normalizeIntroDbUrl
import com.fetch.tv.ui.components.FetchDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SkipMarkerSettingsViewModel @Inject constructor(
    private val dataStore: SkipMarkerSettingsDataStore
) : ViewModel() {
    val introDbUrlOverride: StateFlow<String> = dataStore.introDbUrlOverride
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val introDbBaseUrl: StateFlow<String> = dataStore.introDbBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_INTRODB_API_URL)
    val introDbApiKey: StateFlow<String> = dataStore.introDbApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Saves [url] as the server override; returns false when it is not an http(s) address. */
    fun setIntroDbUrlOverride(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty() && normalizeIntroDbUrl(trimmed) == null) return false
        viewModelScope.launch { dataStore.setIntroDbUrlOverride(normalizeIntroDbUrl(trimmed).orEmpty()) }
        return true
    }

    fun setIntroDbApiKey(key: String) {
        viewModelScope.launch { dataStore.setIntroDbApiKey(key) }
    }
}

/** Advanced settings group for the community marker server (IntroDB) and the key to submit to it. */
@Composable
internal fun SkipMarkerSettingsGroup(
    viewModel: SkipMarkerSettingsViewModel = hiltViewModel()
) {
    val override by viewModel.introDbUrlOverride.collectAsStateWithLifecycle()
    val baseUrl by viewModel.introDbBaseUrl.collectAsStateWithLifecycle()
    val apiKey by viewModel.introDbApiKey.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SkipMarkerField?>(null) }
    val context = LocalContext.current
    val invalidUrl = stringResource(R.string.advanced_introdb_server_invalid)

    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(R.string.advanced_section_skip_markers)
    ) {
        SettingsActionRow(
            title = stringResource(R.string.advanced_introdb_server),
            subtitle = stringResource(R.string.advanced_introdb_server_subtitle),
            value = if (override.isBlank()) {
                stringResource(R.string.advanced_introdb_server_default, hostOf(baseUrl))
            } else {
                hostOf(override)
            },
            onClick = { editing = SkipMarkerField.SERVER }
        )
        SettingsActionRow(
            title = stringResource(R.string.advanced_introdb_api_key),
            subtitle = stringResource(R.string.advanced_introdb_api_key_subtitle),
            value = maskSecret(apiKey, stringResource(R.string.mdblist_not_set)),
            onClick = { editing = SkipMarkerField.API_KEY }
        )
    }

    when (editing) {
        SkipMarkerField.SERVER -> SettingsTextInputDialog(
            title = stringResource(R.string.advanced_introdb_server),
            subtitle = stringResource(R.string.advanced_introdb_server_dialog_subtitle),
            placeholder = DEFAULT_INTRODB_API_URL,
            initialValue = override,
            keyboardType = KeyboardType.Uri,
            onSave = { value ->
                if (viewModel.setIntroDbUrlOverride(value)) {
                    editing = null
                } else {
                    Toast.makeText(context, invalidUrl, Toast.LENGTH_SHORT).show()
                }
            },
            onClear = { viewModel.setIntroDbUrlOverride(""); editing = null },
            onDismiss = { editing = null }
        )
        SkipMarkerField.API_KEY -> SettingsTextInputDialog(
            title = stringResource(R.string.advanced_introdb_api_key),
            subtitle = stringResource(R.string.advanced_introdb_api_key_dialog_subtitle),
            placeholder = "idb_…",
            initialValue = apiKey,
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            onSave = { value -> viewModel.setIntroDbApiKey(value); editing = null },
            onClear = { viewModel.setIntroDbApiKey(""); editing = null },
            onDismiss = { editing = null }
        )
        null -> Unit
    }
}

private enum class SkipMarkerField { SERVER, API_KEY }

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

private fun maskSecret(value: String, notSetLabel: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}

/** Single-line text entry dialog with Cancel / Clear / Save, styled like the other settings inputs. */
@Composable
private fun SettingsTextInputDialog(
    title: String,
    subtitle: String,
    placeholder: String,
    initialValue: String,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    FetchDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier.fillMaxWidth().onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundElevated,
                focusedContainerColor = FetchTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(FetchTheme.radii.sm)
                ),
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(FetchTheme.radii.sm)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(FetchTheme.radii.sm)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = FetchTheme.colors.TextPrimary,
                        textDirection = TextDirection.Content
                    ),
                    cursorBrush = SolidColor(
                        if (isInputFocused) FetchTheme.colors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundElevated,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_cancel)) }
            Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundElevated,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_clear)) }
            Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
            Button(
                onClick = { onSave(value) },
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}
