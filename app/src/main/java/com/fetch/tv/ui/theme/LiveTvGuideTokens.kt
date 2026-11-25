package com.fetch.tv.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Live TV guide's grid (#660). A guide is laid out against a time axis, so its cells are
 * fixed sizes rather than the spacing scale, and they don't grow when focused: a growing cell
 * would cover the programme next to it.
 */
object LiveTvGuideTokens {
    val channelColumnWidth: Dp = 240.dp
    val rowHeight: Dp = 64.dp
    val rulerHeight: Dp = 28.dp
    val channelLogoWidth: Dp = 56.dp
    val channelLogoHeight: Dp = 40.dp
    val nowMarkerWidth: Dp = 2.dp
    /** The gap between two programmes on a row. */
    val programGap: Dp = 2.dp
    val recordingWidth: Dp = 280.dp
    val recordingHeight: Dp = 176.dp
    val recordingImageHeight: Dp = 110.dp
    val loadingIndicatorSize: Dp = 48.dp
    val loadingAreaHeight: Dp = 200.dp

    /** Guide cells keep their size when focused; the focus ring marks them. */
    const val FOCUSED_SCALE: Float = 1f
}
