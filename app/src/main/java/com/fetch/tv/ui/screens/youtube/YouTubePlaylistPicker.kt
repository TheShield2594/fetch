package com.fetch.tv.ui.screens.youtube

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.data.youtube.YouTubePlaylistOption
import com.fetch.tv.data.youtube.YouTubePlaylistPrivacy
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.screens.library.SelectedCheckmark
import com.fetch.tv.ui.screens.library.selectableButtonColors
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The account's playlists, Watch later first, each checked when it holds [videoTitle]'s video.
 * Pressing one saves the video there, or takes it out; the last row makes a new playlist with it.
 * Shared by the long-press menu and the player, which pass in where the playlists live.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun YouTubePlaylistPicker(
    videoTitle: String,
    /** Null while loading, and when they couldn't be read ([failed]). */
    playlists: List<YouTubePlaylistOption>?,
    failed: Boolean,
    onBack: () -> Unit,
    onToggle: (YouTubePlaylistOption) -> Unit,
    onCreate: (title: String, privacy: YouTubePlaylistPrivacy) -> Unit
) {
    val firstFocusRequester = remember { FocusRequester() }
    val newFocusRequester = remember { FocusRequester() }
    var naming by remember { mutableStateOf(false) }
    // Cancelling a new playlist comes back to its row rather than the top of the list.
    var backFromNaming by remember { mutableStateOf(false) }

    if (naming) {
        YouTubeNewPlaylistDialog(
            onCreate = { title, privacy ->
                naming = false
                backFromNaming = false
                onCreate(title, privacy)
            },
            onCancel = {
                naming = false
                backFromNaming = true
            }
        )
        return
    }

    val loading = playlists == null && !failed
    // Focus waits for the list, which may still be loading when this opens.
    LaunchedEffect(loading) {
        when {
            loading -> Unit
            backFromNaming || playlists.isNullOrEmpty() -> newFocusRequester.requestFocusAfterFrames()
            else -> firstFocusRequester.requestFocusAfterFrames()
        }
    }
    FetchDialog(
        onDismiss = onBack,
        title = stringResource(R.string.youtube_menu_save_to_playlist_title),
        subtitle = videoTitle,
        scrollable = true
    ) {
        when {
            failed -> YouTubeMenuMessage(stringResource(R.string.youtube_menu_playlists_failed))
            playlists == null -> YouTubeMenuMessage(stringResource(R.string.youtube_menu_playlists_loading))
            playlists.isEmpty() -> YouTubeMenuMessage(stringResource(R.string.youtube_menu_playlists_empty))
            else -> playlists.forEachIndexed { index, option ->
                YouTubeMenuButton(
                    text = option.title,
                    onClick = { onToggle(option) },
                    icon = if (option.containsVideo) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier
                )
            }
        }
        if (!loading) {
            YouTubeMenuButton(
                text = stringResource(R.string.youtube_new_playlist),
                onClick = { naming = true },
                icon = Icons.Default.Add,
                modifier = Modifier.focusRequester(newFocusRequester)
            )
        }
    }
}

/** A name and who can see it, for a playlist made from the picker with the video already in it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun YouTubeNewPlaylistDialog(
    onCreate: (title: String, privacy: YouTubePlaylistPrivacy) -> Unit,
    onCancel: () -> Unit
) {
    val nameFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var name by remember { mutableStateOf("") }
    // The field only takes typing once selected, so moving past it doesn't open the keyboard.
    var editing by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(YouTubePlaylistPrivacy.PRIVATE) }

    LaunchedEffect(Unit) { nameFocusRequester.requestFocusAfterFrames() }

    FetchDialog(
        onDismiss = onCancel,
        title = stringResource(R.string.youtube_new_playlist_title),
        scrollable = true
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocusRequester)
                .onFocusChanged { if (!it.isFocused) editing = false }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == KeyEvent.ACTION_DOWN && native.keyCode.isSelectKey()) {
                        editing = true
                        keyboardController?.show()
                    }
                    false
                },
            readOnly = !editing,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    editing = false
                    keyboardController?.hide()
                }
            ),
            label = { androidx.compose.material3.Text(stringResource(R.string.youtube_new_playlist_name)) },
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = TextDirection.Content),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = FetchTheme.colors.TextPrimary,
                unfocusedTextColor = FetchTheme.colors.TextPrimary,
                focusedContainerColor = FetchTheme.colors.BackgroundCard,
                unfocusedContainerColor = FetchTheme.colors.BackgroundCard,
                focusedBorderColor = FetchTheme.colors.FocusRing,
                unfocusedBorderColor = FetchTheme.colors.Border,
                focusedLabelColor = FetchTheme.colors.TextSecondary,
                unfocusedLabelColor = FetchTheme.colors.TextTertiary,
                cursorColor = FetchTheme.colors.FocusRing
            )
        )

        Text(
            text = stringResource(R.string.youtube_new_playlist_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
            YouTubePlaylistPrivacy.entries.forEach { option ->
                val isSelected = option == privacy
                Button(
                    onClick = { privacy = option },
                    modifier = Modifier.semantics { selected = isSelected },
                    colors = selectableButtonColors(isSelected)
                ) {
                    if (isSelected) SelectedCheckmark()
                    Text(stringResource(option.labelRes()))
                }
            }
        }

        YouTubeMenuButton(
            text = stringResource(R.string.youtube_new_playlist_create),
            onClick = { onCreate(name.trim(), privacy) },
            enabled = name.isNotBlank()
        )
    }
}

private fun Int.isSelectKey(): Boolean =
    this == KeyEvent.KEYCODE_DPAD_CENTER || this == KeyEvent.KEYCODE_ENTER || this == KeyEvent.KEYCODE_NUMPAD_ENTER

private fun YouTubePlaylistPrivacy.labelRes(): Int = when (this) {
    YouTubePlaylistPrivacy.PRIVATE -> R.string.youtube_privacy_private
    YouTubePlaylistPrivacy.UNLISTED -> R.string.youtube_privacy_unlisted
    YouTubePlaylistPrivacy.PUBLIC -> R.string.youtube_privacy_public
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun YouTubeMenuButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            contentColor = FetchTheme.colors.TextPrimary
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null)
            Spacer(Modifier.width(FetchTheme.spacing.sm))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun YouTubeMenuMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = FetchTheme.colors.TextSecondary
    )
}
