package com.fetch.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.screens.player.DialogButton
import com.fetch.tv.ui.theme.FetchTheme

/**
 * "Removed *Title* · Undo", shown while a Continue Watching removal waits out its undo window
 * (#816). Up from the Continue Watching row reaches Undo; Down or Back from it returns to the row.
 */
@Composable
internal fun ContinueWatchingUndoBanner(
    pending: PendingContinueWatchingRemoval,
    undoFocusRequester: FocusRequester,
    onUndo: () -> Unit,
    onReturnToRow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(FetchTheme.colors.BackgroundCard, RoundedCornerShape(FetchTheme.radii.md))
            .padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (pending.title.isBlank()) {
                stringResource(R.string.cw_removed_banner_untitled)
            } else {
                stringResource(R.string.cw_removed_banner, pending.title)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        DialogButton(
            text = stringResource(R.string.player_queue_undo),
            onClick = onUndo,
            isPrimary = false,
            modifier = Modifier
                .focusRequester(undoFocusRequester)
                .onPreviewKeyEvent { event ->
                    val code = event.nativeKeyEvent.keyCode
                    val back = code == AndroidKeyEvent.KEYCODE_BACK || code == AndroidKeyEvent.KEYCODE_ESCAPE
                    if (code != AndroidKeyEvent.KEYCODE_DPAD_DOWN && !back) return@onPreviewKeyEvent false
                    // Back is acted on when released, so its up event does not reach the screen.
                    val act = if (back) event.type == KeyEventType.KeyUp else event.type == KeyEventType.KeyDown
                    if (act) onReturnToRow()
                    true
                }
        )
    }
}
