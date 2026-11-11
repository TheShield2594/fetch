package com.fetch.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class FetchStrokeTokens(
    val none: Dp,
    val hairline: Dp,
    val medium: Dp,
    val focus: Dp,
    val heavy: Dp,
    val progress: Dp
)

object FetchStrokes {
    val tokens = FetchStrokeTokens(
        none = 0.dp,
        hairline = 1.dp,
        medium = 2.dp,
        focus = 2.dp,
        heavy = 3.dp,
        progress = 4.dp
    )
}
