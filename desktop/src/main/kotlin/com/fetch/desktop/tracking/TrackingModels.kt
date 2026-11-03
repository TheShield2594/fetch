package com.fetch.desktop.tracking

import kotlinx.serialization.Serializable

/** OAuth tokens for one service. Simkl tokens have no refresh token and never expire. */
@Serializable
data class TrackingTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochSec: Long? = null,
) {
    /** True when the token expires within [marginSec] of [nowEpochSec]. */
    fun expiresSoon(nowEpochSec: Long = System.currentTimeMillis() / 1000, marginSec: Long = 24 * 3600): Boolean =
        expiresAtEpochSec != null && expiresAtEpochSec - nowEpochSec <= marginSec
}

/** A device (Trakt) or PIN (Simkl) sign-in: show [userCode] and [verificationUrl], then poll every [intervalSec]. */
data class DeviceCode(
    val userCode: String,
    val verificationUrl: String,
    val deviceCode: String,
    val intervalSec: Int,
    val expiresInSec: Int,
)

/** The outcome of one poll of a pending device or PIN sign-in. */
sealed interface TokenPoll {
    /** The user has not entered the code yet; poll again after the interval. */
    data object Pending : TokenPoll

    /** Polling too fast; lengthen the interval before polling again. */
    data object SlowDown : TokenPoll

    /** The code expired; start over. */
    data object Expired : TokenPoll

    /** The user declined. */
    data object Denied : TokenPoll

    data class Success(val tokens: TrackingTokens) : TokenPoll

    /** The code is invalid or already used, or the service gave an unexpected answer; start over. */
    data class Failed(val message: String) : TokenPoll
}

/**
 * A movie, or a series (optionally one episode of it), identified by IMDb id. For an episode,
 * [imdbId] is the series' id.
 */
data class TrackedItem(
    val imdbId: String,
    val type: String,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val year: Int? = null,
) {
    val isMovie: Boolean get() = type == TYPE_MOVIE
    val isEpisode: Boolean get() = !isMovie && season != null && episode != null

    companion object {
        const val TYPE_MOVIE = "movie"
        const val TYPE_SERIES = "series"
    }
}

/** Something started and not finished on a service (Continue Watching). */
data class RemotePlayback(
    val item: TrackedItem,
    val progressPercent: Float,
    val pausedAtEpochMs: Long,
    val showTitle: String?,
    val episodeTitle: String?,
)

/** Everything a service has marked watched: movie IMDb ids, and (season, episode) pairs per series IMDb id. */
data class RemoteWatched(
    val movies: Set<String>,
    val episodes: Map<String, Set<Pair<Int, Int>>>,
)

/** One watchlist entry. [type] is `movie` or `series`. */
data class RemoteListItem(
    val imdbId: String,
    val type: String,
    val title: String,
    val year: Int?,
    val addedAtEpochMs: Long?,
)

/** A Trakt personal list. [privacy] is `private`, `link`, `friends` or `public`. */
data class RemoteList(
    val id: Long,
    val slug: String?,
    val name: String,
    val description: String?,
    val privacy: String,
    val itemCount: Int?,
)

enum class ScrobbleAction { START, PAUSE, STOP }

/** A service answered with an error status. */
open class TrackingException(message: String, val status: Int? = null) : Exception(message)

/** The service rejected the token (HTTP 401): refresh it, or sign the user out. */
class TrackingAuthException(message: String = "Not signed in") : TrackingException(message, 401)
