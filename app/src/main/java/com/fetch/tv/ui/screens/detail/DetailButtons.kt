package com.fetch.tv.ui.screens.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The secondary button used across the detail screen and its overlays and dialogs. It has the
 * hero's button shape and shows focus the way the hero actions do: the neutral Highlight fill
 * plus the focus ring. The accent stays for Play.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DetailButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable () -> Unit
) {
    val shape = FetchTheme.shapes.button
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            contentColor = FetchTheme.colors.TextPrimary,
            focusedContainerColor = FetchTheme.colors.Highlight,
            focusedContentColor = FetchTheme.colors.OnHighlight
        ),
        shape = ButtonDefaults.shape(shape = shape),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.strokes.focus),
                shape = shape
            )
        ),
        contentPadding = contentPadding,
        content = { content() }
    )
}

/**
 * One option in a set of choices (a comments filter, a season, an episode). The selected option
 * takes the Highlight fill and a check mark, which stays while it has focus, so the current choice
 * reads without colour and is never confused with focus, which adds the ring.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DetailChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = FetchTheme.shapes.button
    Button(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        colors = ButtonDefaults.colors(
            containerColor = if (selected) FetchTheme.colors.Highlight else FetchTheme.colors.BackgroundCard,
            contentColor = if (selected) FetchTheme.colors.OnHighlight else FetchTheme.colors.TextPrimary,
            focusedContainerColor = FetchTheme.colors.Highlight,
            focusedContentColor = FetchTheme.colors.OnHighlight
        ),
        shape = ButtonDefaults.shape(shape = shape),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.strokes.focus),
                shape = shape
            )
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f)
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(FetchTheme.spacing.xs))
        }
        content()
    }
}
