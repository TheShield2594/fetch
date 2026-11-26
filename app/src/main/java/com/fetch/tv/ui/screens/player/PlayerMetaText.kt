package com.fetch.tv.ui.screens.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.fetch.tv.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Joins the non-blank [parts] of a player metadata line ("S1 E2 · Pilot", "AAC · 6 ch · 48 kHz")
 * through [R.string.player_meta_line], so every overlay uses one translatable separator and
 * word order instead of a literal built in code.
 */
internal fun Context.joinPlayerMeta(parts: List<String?>): String =
    parts.mapNotNull { part -> part?.takeIf { it.isNotBlank() } }
        .reduceOrNull { line, part -> getString(R.string.player_meta_line, line, part) }
        .orEmpty()

@Composable
internal fun joinPlayerMeta(vararg parts: String?): String =
    LocalContext.current.joinPlayerMeta(parts.toList())

/**
 * Sample rate in kHz for [R.string.audio_sample_rate_khz]: whole when it is a whole number of
 * kHz ("48"), otherwise to one decimal ("44.1"), so 44.1 kHz no longer reads as "44 kHz".
 */
internal fun formatSampleRateKhz(sampleRateHz: Int, locale: Locale = Locale.getDefault()): String {
    val tenthsKhz = (sampleRateHz / 100.0).roundToInt()
    return if (tenthsKhz % 10 == 0) {
        String.format(locale, "%d", tenthsKhz / 10)
    } else {
        String.format(locale, "%.1f", tenthsKhz / 10.0)
    }
}
