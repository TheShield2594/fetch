package com.fetch.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import kotlin.math.roundToInt

/**
 * The whole percentage a card's progress bar shows, for TalkBack: 1–99, or null when the bar isn't
 * drawn (nothing watched yet, or finished). Rounded into 1–99 so "0% watched" is never read for a
 * title that has been started, nor "100%" for one that hasn't finished.
 */
fun cardProgressPercent(progress: Float?): Int? =
    progress?.takeIf { it > 0f && it < 1f }?.let { (it * 100).roundToInt().coerceIn(1, 99) }

/** "45% watched" for a card's progress bar, so the bar isn't information only sighted people get. */
@Composable
@ReadOnlyComposable
fun cardProgressDescription(progress: Float?): String? =
    cardProgressPercent(progress)?.let { stringResource(R.string.cd_card_progress, it) }
