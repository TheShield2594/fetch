package com.fetch.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.ui.theme.FetchContentColors
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The small pill in a card's corner ("New", "New season", "2h left"): Continue Watching's badge,
 * shared with the Library grid so a new episode looks the same in both.
 */
@Composable
fun ContentCornerBadge(
    text: String,
    background: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(FetchTheme.shapes.badge)
            .background(background)
            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xs)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = FetchTheme.colors.TextPrimary
        )
    }
}

/** A show's new-episode (or new-season) badge, in Continue Watching's colours. */
@Composable
fun NewEpisodeBadge(
    text: String,
    newSeason: Boolean,
    modifier: Modifier = Modifier
) {
    ContentCornerBadge(
        text = text,
        background = if (newSeason) FetchContentColors.newSeasonBadge else FetchContentColors.newEpisodeBadge,
        modifier = modifier
    )
}

/**
 * The check box a card shows in selection mode: filled with a tick when chosen, an empty ring
 * otherwise. Decoration only; the card states "Selected" for TalkBack.
 */
@Composable
fun SelectionCheckMarker(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = FetchTheme.shapes.badge
    Box(
        modifier = modifier
            .size(FetchTheme.spacing.xl)
            .clip(shape)
            .background(if (selected) FetchTheme.colors.TextPrimary else FetchTheme.colors.Scrim)
            .border(FetchTheme.strokes.medium, FetchTheme.colors.TextPrimary, shape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = FetchTheme.colors.Background,
                modifier = Modifier.size(FetchTheme.spacing.lg)
            )
        }
    }
}

/**
 * "● 4K DV": a title that starts at once, because its Best match is saved and fresh (#854). The
 * dot is Home's ready dot; [label] is the stream's quality when known, else "Ready".
 */
@Composable
fun ReadyChip(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(FetchTheme.shapes.badge)
            .background(FetchTheme.colors.Scrim)
            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(FetchTheme.spacing.sm)
                .background(FetchContentColors.readyDot, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = FetchTheme.colors.TextPrimary,
            maxLines = 1
        )
    }
}
