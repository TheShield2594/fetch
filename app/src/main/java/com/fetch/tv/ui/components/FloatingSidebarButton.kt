package com.fetch.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.ui.theme.FetchComponents
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The full-screen fade behind the open sidebar. Opaque behind the labels (the open sidebar is
 * 300dp, about a third of a 960dp TV screen), so bright page content such as the hero's Resume
 * button never shows through them, then easing to a light dim over the rest of the page: the
 * page stays visible, just stepped back, while the rail has focus.
 */
internal fun sidebarScrimBrush(background: Color, contentDim: Float): Brush = Brush.horizontalGradient(
    0f to background,
    0.28f to background,
    0.5f to background.copy(alpha = contentDim),
    1f to background.copy(alpha = contentDim)
)

/**
 * One rail entry: an icon tile, and while the rail has focus ([expanded]) its label beside it.
 * Every label shows while the rail is open, so the whole menu reads at once, and they slide in
 * together with the rail.
 *
 * Focus is the loudest state: the focused item fills white with a ring, whether or not it is the
 * current page. The current page, unfocused, is tinted in the accent with an accent icon, so it
 * says "you are here" without competing with focus.
 */
@Composable
internal fun FloatingSidebarButton(
    label: String,
    selected: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    leading: @Composable (tint: Color) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val tokens = FetchComponents.tokens.sidebar
    // Snaps with the focus ring: focus moves through the sidebar far too often for a colour fade.
    val tileColor = when {
        isFocused -> FetchTheme.colors.Highlight
        selected -> FetchTheme.colors.Accent.copy(alpha = SELECTED_TINT_ALPHA)
        else -> Color.Transparent
    }
    val iconTint = when {
        isFocused -> FetchTheme.colors.OnHighlight
        selected -> FetchTheme.colors.Accent
        else -> FetchTheme.colors.TextSecondary
    }
    val tileShape = RoundedCornerShape(FetchTheme.radii.md)

    // The ring sits outside the circle with a small gap, so the slot is wider than the circle.
    val ringGap = FetchTheme.strokes.focus + 2.dp
    val slotSize = tokens.floatingButtonSize + ringGap * 2
    Row(
        modifier = modifier
            .height(slotSize)
            .focusProperties { canFocus = expanded }
            .onFocusChanged { isFocused = it.hasFocus }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Tab
                this.selected = selected
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(slotSize)
                .then(
                    if (isFocused) {
                        Modifier.border(FetchTheme.focusRing.border(FetchTheme.strokes.focus), tileShape)
                    } else {
                        Modifier
                    }
                )
                .padding(ringGap)
                .background(tileColor, tileShape),
            contentAlignment = Alignment.Center
        ) {
            leading(iconTint)
        }
        if (expanded) {
            Text(
                text = label,
                color = if (isFocused || selected) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextSecondary,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.focusLabelEntrance()
            )
        }
    }
}

/** The current page's tile: the accent at this strength, so its icon in full accent still reads on it. */
private const val SELECTED_TINT_ALPHA = 0.18f

/**
 * Makes focus that enters the sidebar land on [currentPage], the current page's item.
 *
 * The TV drawer pulls focus into itself whenever it opens, and left alone that lands on the first
 * item. Home then flashed for a couple of frames on every other page before focus moved on to the
 * current page's item.
 */
internal fun Modifier.sidebarFocusEntry(currentPage: FocusRequester?): Modifier =
    focusProperties { onEnter = { currentPage?.requestFocus() } }
        .focusGroup()

/**
 * Brings a focus label in beside its icon: it fades in while sliding [FetchTheme.spacing.sm] out
 * of the icon. Only the draw layer animates, so the label's width never changes frame to frame
 * and nothing beside it moves. Call it on a label that is composed only while it is visible;
 * it leaves at once when removed.
 */
@Composable
internal fun Modifier.focusLabelEntrance(): Modifier {
    val fullMotion = FetchTheme.fullMotion
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, FetchMotion.enterTween()) }
    val slidePx = with(LocalDensity.current) { FetchTheme.spacing.sm.toPx() }
    // Start toward the icon: the icon is on the start side.
    val startTranslationX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) slidePx else -slidePx
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationX = if (fullMotion) (1f - p) * startTranslationX else 0f
    }
}
