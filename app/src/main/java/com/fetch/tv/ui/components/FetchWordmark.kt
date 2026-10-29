package com.fetch.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fetch.tv.ui.theme.DotoFamily
import com.fetch.tv.ui.theme.FetchTheme

/**
 * Fetch's dot-matrix wordmark, drawn rather than a bitmap so it stays crisp at the rail's small
 * size. The logo only; content titles never use the dot-matrix face.
 */
@Composable
fun FetchWordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 18.sp) {
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
        modifier = modifier.clearAndSetSemantics { contentDescription = "Fetch" }
    )
}
