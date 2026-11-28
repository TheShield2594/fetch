package com.fetch.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.contentTextDirection

/** One choice in a filter's panel. [count], when known, follows the label: how many titles it keeps. */
@Immutable
data class FetchFilterOption(
    val key: String,
    val label: String,
    val selected: Boolean,
    val count: Int? = null
)

/**
 * A labelled filter showing its value ("Sort  Rating ⌄") that opens its choices in a panel
 * anchored under it (#851). Discover, See all and the Library all use it, so a filter looks and
 * behaves the same on every screen.
 *
 * The panel is drawn over the content, so nothing below moves. The current choice takes focus
 * when it opens; Up from the first choice, Back, or a pick closes it and puts focus back on the
 * chip. A long list scrolls inside the panel.
 */
@Composable
fun FetchFilterChip(
    title: String,
    value: String,
    active: Boolean,
    expanded: Boolean,
    options: List<FetchFilterOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (FetchFilterOption) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    focusEnabled: Boolean = true
) {
    val anchorFocusRequester = remember { FocusRequester() }
    var wasExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            wasExpanded = true
        } else if (wasExpanded) {
            wasExpanded = false
            anchorFocusRequester.requestFocusAfterFrames(frames = 1)
        }
    }

    // Where the chip sits on screen, so the panel can be sized to the room above or below it.
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    Box(modifier = modifier.onGloballyPositioned { anchorBounds = it.boundsInWindow() }) {
        FetchChipSurface(
            selected = active,
            onClick = { onExpandedChange(!expanded) },
            focusEnabled = focusEnabled,
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(
                    if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier
                )
                .focusRequester(anchorFocusRequester)
        ) { contentColor ->
            FetchFilterChipLabel(title = title, value = value, expanded = expanded, contentColor = contentColor)
        }
        if (expanded) {
            FetchFilterPanel(
                options = options,
                anchorBounds = anchorBounds,
                onDismiss = { onExpandedChange(false) },
                onSelect = { option ->
                    onExpandedChange(false)
                    onSelect(option)
                }
            )
        }
    }
}

/** A filter chip's content: its name, the current value, and an arrow for whether its choices are open. */
@Composable
private fun FetchFilterChipLabel(title: String, value: String, expanded: Boolean, contentColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(textDirection = value.contentTextDirection()),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) {
                stringResource(R.string.cd_collapse, title)
            } else {
                stringResource(R.string.cd_expand, title)
            },
            modifier = Modifier.size(FetchTheme.sizes.icons.sm),
            tint = contentColor
        )
    }
}

/** At most this share of the screen tall; a longer list scrolls inside the panel. */
private const val FILTER_PANEL_MAX_SCREEN_FRACTION = 0.55f

@Composable
private fun FetchFilterPanel(
    options: List<FetchFilterOption>,
    anchorBounds: Rect?,
    onDismiss: () -> Unit,
    onSelect: (FetchFilterOption) -> Unit
) {
    val density = LocalDensity.current
    val gap = FetchTheme.spacing.xs
    // Kept clear of the screen's top and bottom edges, as the screens' own content is.
    val margin = FetchTheme.spacing.screen.vertical
    val gapPx = with(density) { gap.roundToPx() }
    val marginPx = with(density) { margin.roundToPx() }
    val positionProvider = remember(gapPx, marginPx) { BelowAnchorPositionProvider(gapPx, marginPx) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    // No taller than the room on the roomier side of the chip, so it never runs off the screen
    // or over the chip; the provider then opens it on the side it fits.
    val roomHeight = anchorBounds?.let { bounds ->
        with(density) {
            val above = bounds.top.toDp() - gap - margin
            val below = screenHeight - bounds.bottom.toDp() - gap - margin
            maxOf(above, below)
        }
    }
    val maxHeight = minOf(
        screenHeight * FILTER_PANEL_MAX_SCREEN_FRACTION,
        roomHeight ?: screenHeight
    ).coerceAtLeast(FetchTheme.spacing.huge * 2)
    val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    val selectedFocusRequester = remember { FocusRequester() }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val shape = FetchTheme.shapes.menu

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = FetchTheme.spacing.huge * 4, max = FetchTheme.spacing.huge * 8)
                .heightIn(max = maxHeight)
                .background(FetchTheme.colors.BackgroundElevated, shape)
                .border(FetchTheme.strokes.hairline, FetchTheme.colors.Border, shape)
                .padding(FetchTheme.spacing.xs)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
        ) {
            options.forEachIndexed { index, option ->
                FetchFilterOptionRow(
                    option = option,
                    modifier = Modifier
                        .then(if (index == selectedIndex) Modifier.focusRequester(selectedFocusRequester) else Modifier)
                        .then(
                            if (index == 0) {
                                // Up from the first choice goes back to the chip, as Back does.
                                Modifier.onPreviewKeyEvent { event ->
                                    val native = event.nativeKeyEvent
                                    if (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                                        native.action == AndroidKeyEvent.ACTION_DOWN
                                    ) {
                                        latestDismiss()
                                        true
                                    } else {
                                        false
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ),
                    onClick = { onSelect(option) }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        selectedFocusRequester.requestFocusAfterFrames(frames = 1)
    }
}

@Composable
private fun FetchFilterOptionRow(
    option: FetchFilterOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val contentColor = if (focused) FetchTheme.colors.OnHighlight else FetchTheme.colors.TextPrimary
    val shape = FetchTheme.shapes.chip
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = FetchTheme.colors.Highlight
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = FetchFocusSpec.border(shape, FetchTheme.colors.Highlight)
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = FetchMotion.tokens.reducedMotionScale)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A tick, not colour alone, marks the current choice; the slot keeps labels aligned.
            Box(modifier = Modifier.size(FetchTheme.sizes.icons.sm)) {
                if (option.selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = contentColor,
                        modifier = Modifier.size(FetchTheme.sizes.icons.sm)
                    )
                }
            }
            Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = option.label.contentTextDirection()),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            option.count?.let { count ->
                Spacer(modifier = Modifier.width(FetchTheme.spacing.md))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) contentColor else FetchTheme.colors.TextSecondary
                )
            }
        }
    }
}

/**
 * Puts the panel under its chip, lined up with the chip's start edge and kept on screen, [marginPx]
 * from the top and bottom edges. When there is no room below, it opens above the chip instead.
 */
private class BelowAnchorPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val start = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left
        } else {
            anchorBounds.right - popupContentSize.width
        }
        val x = start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx
        val y = if (below + popupContentSize.height <= windowSize.height - marginPx) {
            below
        } else {
            (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(marginPx)
        }
        return IntOffset(x, y)
    }
}
