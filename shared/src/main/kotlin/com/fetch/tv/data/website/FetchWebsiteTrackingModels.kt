package com.fetch.tv.data.website

import kotlinx.serialization.Serializable

// The Fetch website's watch state and lists, as its API answers for one profile: shared by the
// TV and desktop apps, which both use the website as a tracking provider.

/** A resume point from `/playback`. Episodes carry their series id as [imdbId]. */
@Serializable
data class WebsitePlayback(
    val id: String,
    val type: String,
    val imdbId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val progress: Double = 0.0,
    val positionSeconds: Long? = null,
    val durationSeconds: Long? = null,
    val pausedAt: String,
    val name: String? = null,
    val poster: String? = null
)

@Serializable
data class WebsiteWatchedMovie(
    val imdbId: String,
    val watchedAt: String,
    val name: String? = null,
    val poster: String? = null
)

/** One watched episode from `/watch/state`, under its series id, with the series' name and poster. */
@Serializable
data class WebsiteWatchedEpisode(
    val imdbId: String,
    val season: Int,
    val episode: Int,
    val watchedAt: String,
    val name: String? = null,
    val poster: String? = null
)

/** Everything a profile has watched, once per title, and the shows it dropped. */
@Serializable
data class WebsiteWatchState(
    val movies: List<WebsiteWatchedMovie> = emptyList(),
    val episodes: List<WebsiteWatchedEpisode> = emptyList(),
    val dropped: List<String> = emptyList()
)

/** A list on the website: `watchlist` and `collection` are the profile's defaults, `custom` its own. */
@Serializable
data class WebsiteList(
    val id: String,
    val name: String,
    val kind: String = "custom"
)

@Serializable
data class WebsiteListItemMetadata(
    val name: String? = null,
    val poster: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null
)

@Serializable
data class WebsiteListItem(
    val type: String,
    val imdbId: String,
    val addedAt: String? = null,
    val title: String? = null,
    val metadata: WebsiteListItemMetadata? = null
)
