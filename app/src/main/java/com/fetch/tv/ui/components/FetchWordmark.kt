package com.fetch.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fetch.tv.ui.theme.DotoFamily
import com.fetch.tv.ui.theme.FetchTheme

/**
 * Fetch's dot-matrix wordmark, drawn rather than a bitmap so it stays crisp at the rail's small
 * size. The logo only; content titles never use the dot-matrix face.
 *
 * Sized in dp, not sp, so a larger system font scale can't push it past the collapsed rail:
 * Doto is monospaced at 0.6em, so "FETCH" is about 3.6 × [size] wide with its letter spacing
 * (50dp at the default, inside the rail's 72dp). It never wraps.
 */
@Composable
fun FetchWordmark(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Text(
        text = "FETCH",
        style = TextStyle(
            fontFamily = DotoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            lineHeight = fontSize,
            letterSpacing = fontSize * 0.12f
        ),
        color = FetchTheme.colors.TextPrimary,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.clearAndSetSemantics { contentDescription = "Fetch" }
    )
}
