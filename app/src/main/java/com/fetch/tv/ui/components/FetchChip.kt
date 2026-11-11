package com.fetch.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.ui.theme.FetchTheme

/**
 * Fetch's chip: a filter or a choice in a row of options (search type filters, source filters,
 * genres). It draws exactly as the Sources screen's addon chips do, so chips look the same on the
 * detail page, in Search and on Sources: a card fill with a hairline edge at rest, the neutral
 * Highlight pair when focused or selected, and the focus ring. Selection is also announced, so it
 * never rests on colour alone.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FetchChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = FetchTheme.shapes.chip
    val textColor = if (isFocused || selected) FetchTheme.colors.OnHighlight else FetchTheme.colors.TextSecondary
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .semantics { this.selected = selected }
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
            },
        colors = FilterChipDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.Highlight,
            selectedContainerColor = FetchTheme.colors.Highlight,
            focusedSelectedContainerColor = FetchTheme.colors.Highlight,
            contentColor = textColor,
            focusedContentColor = textColor,
            selectedContentColor = textColor,
            focusedSelectedContentColor = textColor
        ),
        border = FilterChipDefaults.border(
            border = FetchFocusSpec.restingBorder(shape),
            focusedBorder = FetchFocusSpec.border(shape, FetchTheme.colors.Highlight),
            selectedBorder = Border(
                border = BorderStroke(FetchTheme.strokes.hairline, FetchTheme.colors.Primary),
                shape = shape
            ),
            focusedSelectedBorder = FetchFocusSpec.border(shape, FetchTheme.colors.Highlight)
        ),
        shape = FilterChipDefaults.shape(shape = shape)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
