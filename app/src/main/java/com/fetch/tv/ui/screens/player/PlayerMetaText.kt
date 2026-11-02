package com.fetch.tv.ui.screens.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.fetch.tv.R

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
