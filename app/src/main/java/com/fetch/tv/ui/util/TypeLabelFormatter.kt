package com.fetch.tv.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.fetch.tv.R

fun formatAddonTypeLabel(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}

fun localizedContentType(context: Context, contentType: String?): String = when (contentType?.lowercase()?.trim()) {
    "movie" -> context.getString(R.string.type_movie)
    "series" -> context.getString(R.string.type_series)
    "tv" -> "TV"
    else -> contentType?.let { formatAddonTypeLabel(it) } ?: ""
}

@Composable
fun localizedContentType(contentType: String?): String {
    val context = LocalContext.current
    return localizedContentType(context, contentType)
}

/**
 * Whether a title of [contentType] is a series: its watched tick, the Watched / Unwatched filter
 * and the bulk actions all read this one check, so a title can't show a tick and still be listed
 * under Unwatched (#849). Anime counts as a series, as the bulk actions always treated it.
 */
fun isSeriesContentType(contentType: String?): Boolean = when (contentType?.trim()?.lowercase()) {
    "series", "tv", "anime" -> true
    else -> false
}

/**
 * Whether the title [id] of [contentType] is watched: looked up in [watchedSeriesIds] for a series
 * ([isSeriesContentType]), in [watchedMovieIds] otherwise. Watched entries can be stored under the
 * IMDb id, so [imdbId] is checked too. The one lookup Library, Discover, Search and See all share.
 */
fun isTitleWatched(
    id: String,
    contentType: String?,
    watchedMovieIds: Set<String>,
    watchedSeriesIds: Set<String>,
    imdbId: String? = null
): Boolean {
    val ids = if (isSeriesContentType(contentType)) watchedSeriesIds else watchedMovieIds
    return id in ids || imdbId?.let { it in ids } == true
}
