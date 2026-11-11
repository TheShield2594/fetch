package com.fetch.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.ui.theme.FetchTheme

/** What a [FetchBadge] says about the thing it sits on. */
enum class FetchBadgeTone {
    /** A fact: a type, a count, "In library". */
    Neutral,

    /** Something the user should notice first, such as the top result. */
    Accent
}

/**
 * A small, non-focusable label on a card or in a line of text. One shape (the theme's badge
 * radius), one text style and one padding, so badges read as the same thing wherever they appear.
 */
@Composable
fun FetchBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: FetchBadgeTone = FetchBadgeTone.Neutral
) {
    val (container, content) = badgeColors(tone)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = modifier
            .clip(FetchTheme.shapes.badge)
            .background(container)
            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xxs)
    )
}

@Composable
private fun badgeColors(tone: FetchBadgeTone): Pair<Color, Color> = when (tone) {
    FetchBadgeTone.Neutral -> FetchTheme.colors.SurfaceVariant to FetchTheme.colors.TextPrimary
    FetchBadgeTone.Accent -> FetchTheme.colors.Primary to FetchTheme.colors.OnPrimary
}
