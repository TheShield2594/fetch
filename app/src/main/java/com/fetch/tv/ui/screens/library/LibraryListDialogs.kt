package com.fetch.tv.ui.screens.library

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.LibraryListPrivacy
import kotlinx.coroutines.delay
import com.fetch.tv.domain.model.localizedTitle
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import com.fetch.tv.R
import com.fetch.tv.core.tracking.TrackingListManagementCapabilities
import com.fetch.tv.ui.screens.ambient.AmbientDialogEffect
import com.fetch.tv.ui.theme.FetchStatusTones

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ManageListsDialog(
    tabs: List<LibraryListTab>,
    capabilities: TrackingListManagementCapabilities,
    selectedKey: String?,
    errorMessage: String?,
    pending: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val personalTabs = remember(tabs) { tabs.filter { it.type == LibraryListTab.Type.PERSONAL } }
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(personalTabs.size, pending) {
        if (pending) return@LaunchedEffect
        val target = firstFocusRequester
        val focused = runCatching { target.requestFocus() }.isSuccess
        if (!focused) {
            delay(16)
            runCatching { target.requestFocus() }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        AmbientDialogEffect(onDismiss)
        Box(
            modifier = Modifier
                .width(620.dp)
                .background(FetchTheme.colors.BackgroundElevated, RoundedCornerShape(FetchTheme.radii.xl))
                .padding(FetchTheme.spacing.xl)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.library_manage_lists),
                    style = MaterialTheme.typography.titleLarge,
                    color = FetchTheme.colors.TextPrimary
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FetchStatusTones.errorText
                    )
                }

                if (personalTabs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.library_no_lists),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FetchTheme.extendedColors.textSecondary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(personalTabs, key = { it.key }) { tab ->
                            val isSelected = tab.key == selectedKey
                            Button(
                                onClick = { onSelect(tab.key) },
                                enabled = !pending,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (tab.key == personalTabs.firstOrNull()?.key) {
                                            Modifier.focusRequester(firstFocusRequester)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .semantics { selected = isSelected },
                                colors = selectableButtonColors(isSelected)
                            ) {
                                if (isSelected) SelectedCheckmark()
                                Text(
                                    text = tab.localizedTitle(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onCreate,
                        modifier = if (personalTabs.isEmpty()) Modifier.focusRequester(firstFocusRequester) else Modifier,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = FetchTheme.colors.BackgroundCard,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_create)) }
                    Button(
                        onClick = onEdit,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = FetchTheme.colors.BackgroundCard,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_edit)) }
                    if (capabilities.supportsReordering) {
                        Button(
                            onClick = onMoveUp,
                            enabled = !pending && selectedKey != null,
                            colors = ButtonDefaults.colors(
                                containerColor = FetchTheme.colors.BackgroundCard,
                                contentColor = FetchTheme.colors.TextPrimary
                            )
                        ) { Text(stringResource(R.string.library_list_move_up)) }
                        Button(
                            onClick = onMoveDown,
                            enabled = !pending && selectedKey != null,
                            colors = ButtonDefaults.colors(
                                containerColor = FetchTheme.colors.BackgroundCard,
                                contentColor = FetchTheme.colors.TextPrimary
                            )
                        ) { Text(stringResource(R.string.library_list_move_down)) }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onDelete,
                        enabled = !pending && selectedKey != null,
                        colors = destructiveButtonColors()
                    ) {
                        // Name the list Delete acts on, so the target is never ambiguous.
                        val selectedTitle = personalTabs.firstOrNull { it.key == selectedKey }?.localizedTitle()
                        Text(
                            text = if (selectedTitle != null) {
                                stringResource(R.string.library_list_delete_named, selectedTitle)
                            } else {
                                stringResource(R.string.library_list_delete)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onDismiss,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = FetchTheme.colors.BackgroundCard,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_close)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ListEditorDialog(
    state: LibraryListEditorState,
    capabilities: TrackingListManagementCapabilities,
    pending: Boolean,
    onNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onPrivacyChanged: (LibraryListPrivacy) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val nameFocusRequester = remember { FocusRequester() }
    val descriptionFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var nameEditing by remember { mutableStateOf(false) }
    var descriptionEditing by remember { mutableStateOf(false) }

    fun isSelectKey(keyCode: Int): Boolean {
        return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
    }

    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    FetchDialog(
        onDismiss = onCancel,
        title = if (state.mode == LibraryListEditorState.Mode.CREATE) stringResource(R.string.library_list_create_dialog_title) else stringResource(R.string.library_list_edit_dialog_title),
        width = 560.dp,
        scrollable = true
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = state.name,
            onValueChange = onNameChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        nameEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        nameEditing = true
                        descriptionEditing = false
                        keyboardController?.show()
                    }
                    false
                },
            enabled = !pending,
            readOnly = !nameEditing,
            singleLine = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    nameEditing = false
                    keyboardController?.hide()
                }
            ),
            label = { androidx.compose.material3.Text(stringResource(R.string.library_list_name_label)) },
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                textDirection = TextDirection.Content
            ),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = FetchTheme.colors.TextPrimary,
                unfocusedTextColor = FetchTheme.colors.TextPrimary,
                focusedContainerColor = FetchTheme.colors.BackgroundCard,
                unfocusedContainerColor = FetchTheme.colors.BackgroundCard,
                focusedBorderColor = FetchTheme.colors.FocusRing,
                unfocusedBorderColor = FetchTheme.colors.Border,
                focusedLabelColor = FetchTheme.colors.TextSecondary,
                unfocusedLabelColor = FetchTheme.colors.TextTertiary,
                cursorColor = FetchTheme.colors.FocusRing,
            )
        )

        if (capabilities.supportsDescription) {
            androidx.compose.material3.OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(descriptionFocusRequester)
                    .onFocusChanged {
                        if (!it.isFocused) {
                            descriptionEditing = false
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                            descriptionEditing = true
                            nameEditing = false
                            keyboardController?.show()
                        }
                        false
                    },
                enabled = !pending,
                readOnly = !descriptionEditing,
                minLines = 3,
                maxLines = 5,
                label = { androidx.compose.material3.Text(stringResource(R.string.library_list_description_label)) },
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                    textDirection = TextDirection.Content
                ),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = FetchTheme.colors.TextPrimary,
                    unfocusedTextColor = FetchTheme.colors.TextPrimary,
                    focusedContainerColor = FetchTheme.colors.BackgroundCard,
                    unfocusedContainerColor = FetchTheme.colors.BackgroundCard,
                    focusedBorderColor = FetchTheme.colors.FocusRing,
                    unfocusedBorderColor = FetchTheme.colors.Border,
                    focusedLabelColor = FetchTheme.colors.TextSecondary,
                    unfocusedLabelColor = FetchTheme.colors.TextTertiary,
                    cursorColor = FetchTheme.colors.FocusRing,
                )
            )
        }

        // Local lists are always private; a single choice isn't worth a row.
        if (capabilities.privacyOptions.size > 1) {
            Text(
                text = stringResource(R.string.library_list_privacy),
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.extendedColors.textSecondary
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(capabilities.privacyOptions, key = { it.name }) { privacy ->
                    val isSelected = privacy == state.privacy
                    Button(
                        onClick = { onPrivacyChanged(privacy) },
                        enabled = !pending,
                        modifier = Modifier.semantics { selected = isSelected },
                        colors = selectableButtonColors(isSelected)
                    ) {
                        if (isSelected) SelectedCheckmark()
                        Text(privacy.apiValue.replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }

        Button(
            onClick = onSave,
            enabled = !pending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                contentColor = FetchTheme.colors.TextPrimary
            )
        ) {
            Text(if (pending) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
        }
    }
}

/**
 * Colors for a Library button that can be the current choice. The selected one takes the neutral
 * Highlight pair, since a FocusBackground fill was about 1.16:1 from its neighbours.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun selectableButtonColors(selected: Boolean) = ButtonDefaults.colors(
    containerColor = if (selected) FetchTheme.colors.Highlight else FetchTheme.colors.BackgroundCard,
    contentColor = if (selected) FetchTheme.colors.OnHighlight else FetchTheme.colors.TextPrimary
)

/** Leading checkmark for the selected option, so selection isn't carried by color alone. */
@Composable
internal fun SelectedCheckmark() {
    Icon(
        imageVector = Icons.Default.Check,
        contentDescription = null,
        modifier = Modifier.size(18.dp)
    )
    Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
}

/**
 * Colors for a destructive Library button. The focused pair is set too: without it, focus fell back
 * to the library's white fill and the danger color vanished exactly when the button was about to
 * be pressed.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun destructiveButtonColors() = ButtonDefaults.colors(
    containerColor = FetchStatusTones.errorContainer,
    contentColor = FetchTheme.colors.TextPrimary,
    focusedContainerColor = FetchTheme.colors.Error,
    focusedContentColor = FetchTheme.colors.OnHighlight
)
