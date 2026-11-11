package com.fetch.tv.ui.screens.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.ButtonDefaults
import com.fetch.tv.ui.components.FetchButton

/**
 * The secondary button used across the detail screen and its overlays and dialogs. It has the
 * hero's button shape and shows focus the way the hero actions do: the neutral Highlight fill
 * plus the focus ring. The accent stays for Play.
 */
@Composable
internal fun DetailButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable () -> Unit
) {
    FetchButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding
    ) {
        content()
    }
}

/**
 * One option in a set of choices (a comments filter, a season, an episode). The selected option
 * takes the Highlight fill and a check mark, which stays while it has focus, so the current choice
 * reads without colour and is never confused with focus, which adds the ring.
 */
@Composable
internal fun DetailChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    FetchButton(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        selected = selected,
        focusedScale = 1f
    ) {
        content()
    }
}
