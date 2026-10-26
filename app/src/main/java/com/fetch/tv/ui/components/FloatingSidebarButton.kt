package com.fetch.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.ui.theme.FetchComponents
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The full-screen fade behind the open sidebar: a soft fade from the left edge instead of a
 * panel, so the icons and labels stay readable over artwork while the page runs edge to edge.
 * It is fully opaque across the open sidebar's width (300dp, about a third of a 960dp TV screen):
 * at anything less, bright page content such as the Home hero's white Resume button still showed
 * through, and the focused label's pill cut it into a stray fragment ("sume S2 E5").
 */
internal fun sidebarScrimBrush(background: Color): Brush = Brush.horizontalGradient(
    0f to background,
    0.32f to background,
    0.55f to background.copy(alpha = 0.4f),
    1f to Color.Transparent
)

/**
 * One sidebar entry: a round icon that keeps an accent fill for the current page, and a label
 * that slides out beside it while it has focus.
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
    val circleColor by animateColorAsState(
        targetValue = when {
            selected -> FetchTheme.colors.Secondary
            isFocused -> FetchTheme.colors.FocusBackground
            else -> Color.Transparent
        },
        label = "sidebarCircle"
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            selected -> FetchTheme.colors.OnSecondary
            isFocused -> FetchTheme.colors.TextPrimary
            else -> FetchTheme.colors.TextSecondary
        },
        label = "sidebarIconTint"
    )

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
                        Modifier.border(FetchTheme.focusRing.border(FetchTheme.strokes.focus), CircleShape)
                    } else {
                        Modifier
                    }
                )
                .padding(ringGap)
                .background(circleColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            leading(iconTint)
        }
        AnimatedVisibility(
            visible = isFocused && expanded,
            enter = fadeIn(tween(FetchMotion.tokens.durations.fast)) +
                expandHorizontally(tween(FetchMotion.tokens.durations.fast)),
            exit = fadeOut(tween(FetchMotion.tokens.durations.quick)) +
                shrinkHorizontally(tween(FetchMotion.tokens.durations.quick))
        ) {
            Text(
                text = label,
                color = FetchTheme.colors.TextPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(FetchTheme.colors.BackgroundCard, FetchTheme.shapes.navItem)
                    .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
            )
        }
    }
}

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
