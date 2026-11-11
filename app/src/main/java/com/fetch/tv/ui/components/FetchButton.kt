package com.fetch.tv.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import com.fetch.tv.ui.theme.FetchStatusTones
import com.fetch.tv.ui.theme.FetchTheme

/** What a [FetchButton] is for. Each maps to one colour pair at rest and one when focused. */
enum class FetchButtonStyle {
    /** The default: a card-coloured fill that turns to the neutral Highlight pair on focus. */
    Secondary,

    /** The screen's main action, in the theme's accent. */
    Primary,

    /** Deletes or removes something. Stays red when focused, so the danger shows as it is pressed. */
    Destructive,

    /** A destructive action that should not dominate its row: a card fill with a red label. */
    DestructiveQuiet
}

/**
 * The colours behind every [FetchButton]. Screens that still use tv-material `Button` directly
 * read these too, so they match while they wait to be migrated.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
object FetchButtonDefaults {

    @Composable
    @ReadOnlyComposable
    fun shape(): Shape = FetchTheme.shapes.button

    /**
     * [selected] marks the current choice in a set of buttons: it takes the Highlight pair at rest,
     * so selection doesn't depend on a FocusBackground fill about 1.16:1 from its neighbours.
     */
    @Composable
    fun colors(style: FetchButtonStyle = FetchButtonStyle.Secondary, selected: Boolean = false): ButtonColors =
        when (style) {
            FetchButtonStyle.Secondary -> ButtonDefaults.colors(
                containerColor = if (selected) FetchTheme.colors.Highlight else FetchTheme.colors.BackgroundCard,
                contentColor = if (selected) FetchTheme.colors.OnHighlight else FetchTheme.colors.TextPrimary,
                focusedContainerColor = FetchTheme.colors.Highlight,
                focusedContentColor = FetchTheme.colors.OnHighlight
            )
            FetchButtonStyle.Primary -> ButtonDefaults.colors(
                containerColor = FetchTheme.colors.Primary,
                contentColor = FetchTheme.colors.OnPrimary,
                focusedContainerColor = FetchTheme.colors.Highlight,
                focusedContentColor = FetchTheme.colors.OnHighlight
            )
            FetchButtonStyle.Destructive -> ButtonDefaults.colors(
                containerColor = FetchStatusTones.errorContainer,
                contentColor = FetchTheme.colors.TextPrimary,
                focusedContainerColor = FetchTheme.colors.Error,
                focusedContentColor = FetchTheme.colors.OnHighlight
            )
            FetchButtonStyle.DestructiveQuiet -> ButtonDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                contentColor = FetchStatusTones.errorText,
                focusedContainerColor = FetchTheme.colors.Highlight,
                focusedContentColor = FetchTheme.colors.OnHighlight
            )
        }
}

/**
 * Fetch's button. Use this rather than tv-material `Button` so every screen's buttons share one
 * shape, one set of colours per [style] and one focus treatment; a guard test counts the raw
 * `Button(` calls left in each screen directory and only lets the number fall.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FetchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: FetchButtonStyle = FetchButtonStyle.Secondary,
    selected: Boolean = false,
    /** Leads a [selected] button with a check mark, so the choice doesn't rest on colour alone. */
    showSelectedCheck: Boolean = true,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = FetchButtonDefaults.shape(),
    /** Null keeps tv-material's focus growth; 1 keeps a button in a dense list still. */
    focusedScale: Float? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        enabled = enabled,
        scale = when {
            focusedScale != null -> ButtonDefaults.scale(focusedScale = focusedScale)
            // Reduced motion: focus shows by colour and the ring, without the button growing.
            !FetchTheme.fullMotion -> ButtonDefaults.scale(focusedScale = 1f)
            else -> ButtonDefaults.scale()
        },
        shape = ButtonDefaults.shape(shape),
        colors = FetchButtonDefaults.colors(style, selected),
        border = ButtonDefaults.border(focusedBorder = FetchFocusSpec.border(shape)),
        contentPadding = contentPadding
    ) {
        if (selected && showSelectedCheck) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(FETCH_CHECK_SIZE)
            )
            Spacer(modifier = Modifier.width(FetchTheme.spacing.xs))
        }
        content()
    }
}

/**
 * An icon-only [FetchButton]. [contentDescription] is required: an icon button has no other label
 * for TalkBack to read.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FetchIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null
) {
    FetchIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        onLongClick = onLongClick
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(FETCH_ICON_SIZE))
    }
}

/** An icon button with custom content, for an icon that changes or carries a badge. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FetchIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        enabled = enabled,
        shape = IconButtonDefaults.shape(FetchTheme.shapes.iconButton),
        border = IconButtonDefaults.border(focusedBorder = FetchFocusSpec.border(FetchTheme.shapes.iconButton)),
        scale = if (FetchTheme.fullMotion) IconButtonDefaults.scale() else IconButtonDefaults.scale(focusedScale = 1f),
        colors = IconButtonDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            contentColor = FetchTheme.colors.TextPrimary,
            focusedContainerColor = FetchTheme.colors.Highlight,
            focusedContentColor = FetchTheme.colors.OnHighlight
        ),
        content = content
    )
}

private val FETCH_ICON_SIZE = 24.dp
private val FETCH_CHECK_SIZE = 18.dp
