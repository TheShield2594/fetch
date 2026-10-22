package com.fetch.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.tv.material3.Text
import com.fetch.tv.core.streams.SpecLine
import com.fetch.tv.ui.theme.FetchTheme

/**
 * Draws a [SpecLine] in Fetch's spec style: dimmed separators, and the cached
 * label in [cachedColor] so the one thing that means "starts instantly" stands out.
 *
 * On an accent-coloured surface, such as the detail page's Play button, pass the surface's
 * content colour as [cachedColor]; the accent would vanish there.
 */
@Composable
fun SpecStrip(
    line: SpecLine,
    modifier: Modifier = Modifier,
    style: TextStyle = FetchTheme.textStyles.spec,
    color: Color = FetchTheme.colors.TextSecondary,
    cachedColor: Color = FetchTheme.colors.Secondary
) {
    val separatorColor = color.copy(alpha = color.alpha * 0.5f)
    val text = remember(line, color, cachedColor, separatorColor) {
        buildAnnotatedString {
            line.parts.forEachIndexed { index, part ->
                if (index > 0) withStyle(SpanStyle(color = separatorColor)) { append(SpecLine.SEPARATOR) }
                if (part == line.cached && index == line.parts.lastIndex) {
                    withStyle(SpanStyle(color = cachedColor)) { append(part) }
                } else {
                    append(part)
                }
            }
        }
    }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
