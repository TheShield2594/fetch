package com.fetch.desktop.tracking

import com.fetch.desktop.AppInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.round

/**
 * Trakt's REST API (v2), mirroring the TV app. Every call needs [TrackingCredentials.traktAvailable].
 * Calls that take [TrackingTokens] throw [TrackingAuthException] on 401 and [TrackingException] on
 * other errors; network failures surface as [IOException]. Items without an IMDb id are skipped.
 */
class TraktClient(
    private val http: OkHttpClient,
    private val credentials: TrackingCredentials,
    baseUrl: String = "https://api.trakt.tv/",
    /** Trakt serves OAuth from its auth host, as the TV app does. */
    authBaseUrl: String = "https://auth.trakt.tv/",
    private val appVersion: String = AppInfo.version,
) {
    private val base = baseUrl.withSlash()
    private val authBase = authBaseUrl.withSlash()

    /** Starts the device flow (POST oauth/device/code). */
    suspend fun startDeviceAuth(): DeviceCode {
        val body = buildJsonObject { put("client_id", credentials.traktClientId) }
        val dto = post(authBase + "oauth/device/code", body, null).requireOk(SERVICE, "sign-in").decode<TraktDeviceCodeDto>()
        return DeviceCode(dto.userCode, dto.verificationUrl, dto.deviceCode, dto.interval, dto.expiresIn)
    }

    /** Polls the device flow once (POST oauth/device/token). */
    suspend fun pollDeviceToken(deviceCode: String): TokenPoll {
        val body = buildJsonObject {
            put("code", deviceCode)
            put("client_id", credentials.traktClientId)
            put("client_secret", credentials.traktClientSecret)
        }
        val result = try {
            post(authBase + "oauth/device/token", body, null)
        } catch (e: IOException) {
            return TokenPoll.Failed("Couldn't reach Trakt")
        }
        return when (result.code) {
            in 200..299 -> TokenPoll.Success(result.decode<TraktTokenDto>().toTokens())
            400 -> TokenPoll.Pending
            404 -> TokenPoll.Failed("Invalid code")
            409 -> TokenPoll.Failed("This code was already used")
            410 -> TokenPoll.Expired
            418 -> TokenPoll.Denied
            429 -> TokenPoll.SlowDown
            else -> TokenPoll.Failed("Trakt sign-in failed (HTTP ${result.code})")
        }
    }

    /**
     * Exchanges the refresh token for new tokens (POST oauth/token). A rejected refresh token
     * (400/401/403) throws [TrackingAuthException]: sign the user out.
     */
    suspend fun refresh(tokens: TrackingTokens): TrackingTokens {
        val refreshToken = tokens.refreshToken ?: throw TrackingAuthException("No Trakt refresh token")
        val body = buildJsonObject {
            put("refresh_token", refreshToken)
            put("client_id", credentials.traktClientId)
            put("client_secret", credentials.traktClientSecret)
            put("redirect_uri", REDIRECT_URI)
            put("grant_type", "refresh_token")
        }
        val result = post(authBase + "oauth/token", body, null)
        if (result.code in setOf(400, 401, 403)) throw TrackingAuthException("Trakt refused the refresh token")
        return result.requireOk(SERVICE, "token refresh").decode<TraktTokenDto>().toTokens()
    }

    /** Revokes the access token (POST oauth/revoke). Best effort: failures are ignored. */
    suspend fun revoke(tokens: TrackingTokens) {
        val body = buildJsonObject {
            put("token", tokens.accessToken)
            put("client_id", credentials.traktClientId)
            put("client_secret", credentials.traktClientSecret)
        }
        runCatching { post(authBase + "oauth/revoke", body, tokens) }
    }

    /** The signed-in user's username, or display name (GET users/settings). */
    suspend fun userName(tokens: TrackingTokens): String {
        val dto = get("users/settings", tokens).requireOk(SERVICE, "account lookup").decode<TraktUserSettingsDto>()
        return dto.user?.username?.takeIf { it.isNotBlank() } ?: dto.user?.name.orEmpty()
    }

    /**
     * Reports playback (POST scrobble/start|pause|stop). A series item needs a season and episode.
     * A 409 (already scrobbled) counts as success.
     */
    suspend fun scrobble(tokens: TrackingTokens, action: ScrobbleAction, item: TrackedItem, progressPercent: Float) {
        val body = scrobbleBody(item, progressPercent) ?: return
        val path = "scrobble/" + action.name.lowercase()
        val result = post(base + path, body, tokens)
        if (result.code != 409) result.requireOk(SERVICE, "scrobble")
    }

    /** In-progress movies and episodes (GET sync/playback), newest first. */
    suspend fun playback(tokens: TrackingTokens): List<RemotePlayback> =
        get("sync/playback", tokens).requireOk(SERVICE, "playback").decode<List<TraktPlaybackDto>>()
            .mapNotNull { it.toRemote() }
            .sortedByDescending { it.pausedAtEpochMs }

    /** Every watched movie and episode (GET sync/watched/movies and sync/watched/shows). */
    suspend fun watched(tokens: TrackingTokens): RemoteWatched {
        val movies = get("sync/watched/movies", tokens).requireOk(SERVICE, "watched movies")
            .decode<List<TraktWatchedMovieDto>>()
            .mapNotNullTo(mutableSetOf()) { it.movie?.ids?.imdb.imdbOrNull() }
        val episodes = get("sync/watched/shows", tokens).requireOk(SERVICE, "watched shows")
            .decode<List<TraktWatchedShowDto>>()
            .mapNotNull { show ->
                val imdb = show.show?.ids?.imdb.imdbOrNull() ?: return@mapNotNull null
                imdb to show.seasons.orEmpty().flatMap { season ->
                    val number = season.number ?: return@flatMap emptyList()
                    season.episodes.orEmpty().mapNotNull { ep -> ep.number?.let { number to it } }
                }.toSet()
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, sets) -> sets.flatten().toSet() }
        return RemoteWatched(movies, episodes)
    }

    /** Marks a movie or one episode watched now (POST sync/history). A series without an episode is ignored. */
    suspend fun addToHistory(tokens: TrackingTokens, item: TrackedItem) = changeHistory(tokens, item, remove = false)

    /** Takes a movie or one episode out of the watched history (POST sync/history/remove). */
    suspend fun removeFromHistory(tokens: TrackingTokens, item: TrackedItem) = changeHistory(tokens, item, remove = true)

    private suspend fun changeHistory(tokens: TrackingTokens, item: TrackedItem, remove: Boolean) {
        val imdb = item.imdbId.imdbOrNull() ?: return
        val body = when {
            item.isMovie -> buildJsonObject { putJsonArray("movies") { add(idsObject(imdb)) } }
            item.isEpisode -> buildJsonObject {
                putJsonArray("shows") {
                    add(buildJsonObject {
                        put("ids", idsOnly(imdb))
                        putJsonArray("seasons") {
                            add(buildJsonObject {
                                put("number", item.season)
                                putJsonArray("episodes") { add(buildJsonObject { put("number", item.episode) }) }
                            })
                        }
                    })
                }
            }
            else -> return
        }
        post(base + if (remove) "sync/history/remove" else "sync/history", body, tokens).requireOk(SERVICE, "history update")
    }

    /** The watchlist's movies and shows (GET sync/watchlist/movies and sync/watchlist/shows). */
    suspend fun watchlist(tokens: TrackingTokens): List<RemoteListItem> =
        listOf("movies", "shows").flatMap { type ->
            get("sync/watchlist/$type?page=1&limit=1000", tokens).requireOk(SERVICE, "watchlist")
                .decode<List<TraktListItemDto>>()
                .mapNotNull { it.toRemote() }
        }.sortedByDescending { it.addedAtEpochMs ?: 0L }

    /** Adds the movie or series to the watchlist, or removes it (POST sync/watchlist[/remove]). */
    suspend fun setWatchlisted(tokens: TrackingTokens, item: TrackedItem, on: Boolean) {
        val imdb = item.imdbId.imdbOrNull() ?: return
        val body = buildJsonObject {
            putJsonArray(if (item.isMovie) "movies" else "shows") { add(idsObject(imdb)) }
        }
        val path = if (on) "sync/watchlist" else "sync/watchlist/remove"
        post(base + path, body, tokens).requireOk(SERVICE, "watchlist update")
    }

    private suspend fun get(path: String, tokens: TrackingTokens): HttpResult =
        http.send(request(base + path, tokens).get().build())

    private suspend fun post(url: String, body: JsonObject, tokens: TrackingTokens?): HttpResult =
        http.send(request(url, tokens).post(body.toJsonBody()).build())

    private fun request(url: String, tokens: TrackingTokens?): Request.Builder {
        check(credentials.traktAvailable) { "Trakt isn't configured in this build" }
        return Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", "Fetch/$appVersion")
            .header("trakt-api-key", credentials.traktClientId)
            .header("trakt-api-version", "2")
            .apply { tokens?.let { header("Authorization", "Bearer ${it.accessToken}") } }
    }

    internal companion object {
        const val SERVICE = "Trakt"
        const val REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"

        private fun String.withSlash() = if (endsWith("/")) this else "$this/"

        private fun idsOnly(imdb: String) = buildJsonObject { put("imdb", imdb) }
        private fun idsObject(imdb: String) = buildJsonObject { put("ids", idsOnly(imdb)) }

        /** The scrobble body, or null when the item can't be scrobbled. */
        fun scrobbleBody(item: TrackedItem, progressPercent: Float): JsonObject? {
            val imdb = item.imdbId.imdbOrNull() ?: return null
            val progress = round(progressPercent.coerceIn(0f, 100f) * 100f) / 100f
            return when {
                item.isMovie -> buildJsonObject {
                    put("movie", idsObject(imdb))
                    put("progress", progress)
                }
                item.isEpisode -> buildJsonObject {
                    put("show", idsObject(imdb))
                    putJsonObject("episode") {
                        put("season", item.season)
                        put("number", item.episode)
                    }
                    put("progress", progress)
                }
                else -> null
            }
        }
    }
}

@Serializable
private data class TraktDeviceCodeDto(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = 5,
)

@Serializable
private data class TraktTokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
) {
    fun toTokens(): TrackingTokens {
        val createdAt = createdAt ?: (System.currentTimeMillis() / 1000)
        return TrackingTokens(accessToken, refreshToken, expiresIn?.let { createdAt + it })
    }
}

@Serializable
private data class TraktUserSettingsDto(val user: TraktUserDto? = null)

@Serializable
private data class TraktUserDto(val username: String? = null, val name: String? = null)

@Serializable
private data class TraktIdsDto(val imdb: String? = null)

@Serializable
private data class TraktMediaDto(val title: String? = null, val year: Int? = null, val ids: TraktIdsDto? = null)

@Serializable
private data class TraktEpisodeDto(val season: Int? = null, val number: Int? = null, val title: String? = null)

@Serializable
private data class TraktPlaybackDto(
    val progress: Float? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    val type: String? = null,
    val movie: TraktMediaDto? = null,
    val show: TraktMediaDto? = null,
    val episode: TraktEpisodeDto? = null,
) {
    fun toRemote(): RemotePlayback? {
        val pausedAt = parseIsoEpochMs(pausedAt) ?: 0L
        val progress = progress ?: 0f
        return if (movie != null) {
            val imdb = movie.ids?.imdb.imdbOrNull() ?: return null
            RemotePlayback(
                TrackedItem(imdb, TrackedItem.TYPE_MOVIE, title = movie.title, year = movie.year),
                progress, pausedAt, showTitle = null, episodeTitle = null,
            )
        } else {
            val show = show ?: return null
            val episode = episode ?: return null
            val imdb = show.ids?.imdb.imdbOrNull() ?: return null
            val season = episode.season ?: return null
            val number = episode.number ?: return null
            RemotePlayback(
                TrackedItem(imdb, TrackedItem.TYPE_SERIES, season, number, show.title, show.year),
                progress, pausedAt, showTitle = show.title, episodeTitle = episode.title,
            )
        }
    }
}

@Serializable
private data class TraktWatchedMovieDto(val movie: TraktMediaDto? = null)

@Serializable
private data class TraktWatchedShowDto(val show: TraktMediaDto? = null, val seasons: List<TraktWatchedSeasonDto>? = null)

@Serializable
private data class TraktWatchedSeasonDto(val number: Int? = null, val episodes: List<TraktEpisodeDto>? = null)

@Serializable
private data class TraktListItemDto(
    @SerialName("listed_at") val listedAt: String? = null,
    val type: String? = null,
    val movie: TraktMediaDto? = null,
    val show: TraktMediaDto? = null,
) {
    fun toRemote(): RemoteListItem? {
        val media = movie ?: show ?: return null
        val imdb = media.ids?.imdb.imdbOrNull() ?: return null
        val type = if (movie != null) TrackedItem.TYPE_MOVIE else TrackedItem.TYPE_SERIES
        return RemoteListItem(imdb, type, media.title.orEmpty(), media.year, parseIsoEpochMs(listedAt))
    }
}
