package com.fetch.tv.ui.util

import android.content.Context
import com.fetch.tv.R

private val EPISODE_PATTERN = Regex("^Episode (\\d+)$", RegexOption.IGNORE_CASE)

fun String.localizeEpisodeTitle(appContext: Context): String {
    val match = EPISODE_PATTERN.matchEntire(this.trim()) ?: return this
    val number = match.groupValues[1]
    return "${appContext.getString(R.string.episodes_episode)} $number"
}

/** True for addon filler titles like "Episode 5", which only repeat the episode number. */
fun String.isPlaceholderEpisodeTitle(): Boolean = EPISODE_PATTERN.matches(trim())
