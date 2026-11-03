package com.fetch.desktop.tracking

import com.fetch.desktop.AppInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.round

/**
 * Simkl's API, mirroring the TV app. Every request carries `client_id`, `app-name` and
 * `app-version` query parameters plus the `simkl-api-key` header. Calls that take
 * [TrackingTokens] throw [TrackingAuthException] on 401 and [TrackingException] on other errors.
 * Items without an IMDb id are skipped.
 */
class SimklClient(
    private val http: OkHttpClient,
    private val credentials: TrackingCredentials,
    baseUrl: String = "https://api.simkl.com/",
    private val appVersion: String = AppInfo.version,
) {
    private val base = baseUrl.toHttpUrl()

    /** Starts the PIN flow (GET oauth/pin). */
    suspend fun startPinAuth(): DeviceCode {
        val dto = get("oauth/pin", null).requireOk(SERVICE, "sign-in").decode<SimklPinDto>()
        val userCode = dto.userCode?.takeIf { it.isNotBlank() }
            ?: throw TrackingException("Simkl didn't return a code")
        return DeviceCode(
            userCode = userCode,
            verificationUrl = dto.verificationUrl ?: dto.verificationUri ?: "https://simkl.com/pin",
            deviceCode = userCode,
            intervalSec = (dto.interval ?: 5).coerceAtLeast(1),
            expiresInSec = (dto.expiresIn ?: 900).toInt(),
        )
    }

    /** Polls the PIN flow once (GET oauth/pin/{user_code}). Simkl tokens neither refresh nor expire. */
    suspend fun pollPin(userCode: String): TokenPoll {
        val result = try {
            get("oauth/pin/$userCode", null)
        } catch (e: IOException) {
            return TokenPoll.Failed("Couldn't reach Simkl")
        }
        if (result.code == 404 || result.code == 410) return TokenPoll.Expired
        if (!result.ok) return TokenPoll.Failed("Simkl sign-in failed (HTTP ${result.code})")
        val dto = runCatching { result.decode<SimklPinDto>() }.getOrNull()
            ?: return TokenPoll.Failed("Unexpected answer from Simkl")
        val token = dto.accessToken?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            dto.result == "OK" && token != null -> TokenPoll.Success(TrackingTokens(token, null, null))
            dto.result == "KO" -> TokenPoll.Pending
            else -> TokenPoll.Failed(dto.message ?: "Unexpected answer from Simkl")
        }
    }

    /** The signed-in user's name (POST users/settings). */
    suspend fun userName(tokens: TrackingTokens): String =
        post("users/settings", JsonObject(emptyMap()), tokens).requireOk(SERVICE, "account lookup")
            .decode<SimklUserSettingsDto>().user?.name.orEmpty()

    /**
     * Reports playback (POST scrobble/start|pause|stop). A series item needs a season and episode.
     * A 409 on stop (already recorded) counts as success, as on the TV app.
     */
    suspend fun scrobble(tokens: TrackingTokens, action: ScrobbleAction, item: TrackedItem, progressPercent: Float) {
        val body = scrobbleBody(item, progressPercent) ?: return
        val result = post("scrobble/" + action.name.lowercase(), body, tokens)
        if (result.code == 409 && action == ScrobbleAction.STOP) return
        result.requireOk(SERVICE, "scrobble")
    }

    /** In-progress movies and episodes (GET sync/playback), newest first. */
    suspend fun playback(tokens: TrackingTokens): List<RemotePlayback> {
        val result = get("sync/playback", tokens).requireOk(SERVICE, "playback")
        val element = trackingJson.parseToJsonElement(result.body.ifBlank { "[]" })
        if (element !is JsonArray) return emptyList()
        return trackingJson.decodeFromJsonElement<List<SimklPlaybackDto>>(element)
            .mapNotNull { it.toRemote() }
            .sortedByDescending { it.pausedAtEpochMs }
    }

    /**
     * Everything watched, from `sync/all-items` for movies, shows and anime: movies that are
     * completed or have a last-watched time, and every episode with a watched time.
     */
    suspend fun watched(tokens: TrackingTokens): RemoteWatched {
        val movies = mutableSetOf<String>()
        val episodes = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()
        for (type in MEDIA_TYPES) {
            for (entry in allItems(tokens, type, status = null)) {
                val imdb = entry.media?.imdb() ?: continue
                if (entry.isMovie(type)) {
                    if (entry.lastWatchedAt != null || entry.status == "completed") movies += imdb
                    continue
                }
                for (season in entry.seasons.orEmpty()) for (ep in season.episodes.orEmpty()) {
                    if (ep.watchedAt == null) continue
                    val s = ep.tvdb?.season ?: season.number ?: continue
                    val e = ep.tvdb?.episode ?: ep.number ?: continue
                    episodes.getOrPut(imdb) { mutableSetOf() } += s to e
                }
            }
        }
        return RemoteWatched(movies, episodes)
    }

    /** Marks a movie or one episode watched now (POST sync/history). A series without an episode is ignored. */
    suspend fun addToHistory(tokens: TrackingTokens, item: TrackedItem) = changeHistory(tokens, item, remove = false)

    /** Takes a movie or one episode out of the watched history (POST sync/history/remove). */
    suspend fun removeFromHistory(tokens: TrackingTokens, item: TrackedItem) = changeHistory(tokens, item, remove = true)

    private suspend fun changeHistory(tokens: TrackingTokens, item: TrackedItem, remove: Boolean) {
        val media = mediaObject(item) ?: return
        val body = when {
            item.isMovie -> buildJsonObject { putJsonArray("movies") { add(media) } }
            item.isEpisode -> {
                val season = buildJsonObject {
                    put("number", item.season)
                    putJsonArray("episodes") { add(buildJsonObject { put("number", item.episode) }) }
                }
                val show = JsonObject(media + ("seasons" to JsonArray(listOf(season))))
                buildJsonObject { putJsonArray("shows") { add(show) } }
            }
            else -> return
        }
        post(if (remove) "sync/history/remove" else "sync/history", body, tokens).requireOk(SERVICE, "history update")
    }

    /** The "Plan to Watch" list (GET sync/all-items/{type}/plantowatch). */
    suspend fun watchlist(tokens: TrackingTokens): List<RemoteListItem> =
        MEDIA_TYPES.flatMap { type ->
            allItems(tokens, type, status = PLAN_TO_WATCH).mapNotNull { entry ->
                val media = entry.media ?: return@mapNotNull null
                val imdb = media.imdb() ?: return@mapNotNull null
                RemoteListItem(
                    imdbId = imdb,
                    type = if (entry.isMovie(type)) TrackedItem.TYPE_MOVIE else TrackedItem.TYPE_SERIES,
                    title = media.title.orEmpty(),
                    year = media.year,
                    addedAtEpochMs = parseIsoEpochMs(entry.addedToWatchlistAt),
                )
            }
        }.sortedByDescending { it.addedAtEpochMs ?: 0L }

    /**
     * Every title on your Simkl lists, by status (`watching`, `plantowatch`, `hold`,
     * `completed`, `dropped`), newest first, as the TV's Simkl library tabs show them.
     */
    suspend fun statusLists(tokens: TrackingTokens): Map<String, List<RemoteListItem>> {
        val byStatus = mutableMapOf<String, MutableList<RemoteListItem>>()
        MEDIA_TYPES.forEach { type ->
            allItems(tokens, type, status = null).forEach { entry ->
                val status = entry.status ?: return@forEach
                val media = entry.media ?: return@forEach
                val imdb = media.imdb() ?: return@forEach
                byStatus.getOrPut(status) { mutableListOf() } += RemoteListItem(
                    imdbId = imdb,
                    type = if (entry.isMovie(type)) TrackedItem.TYPE_MOVIE else TrackedItem.TYPE_SERIES,
                    title = media.title.orEmpty(),
                    year = media.year,
                    addedAtEpochMs = parseIsoEpochMs(entry.addedToWatchlistAt ?: entry.lastWatchedAt),
                )
            }
        }
        return byStatus.mapValues { (_, items) -> items.distinctBy { it.imdbId }.sortedByDescending { it.addedAtEpochMs ?: 0L } }
    }

    /** Moves the item to a Simkl list ([status]: `watching`, `plantowatch`, `hold`, `completed`, `dropped`). */
    suspend fun setStatus(tokens: TrackingTokens, item: TrackedItem, status: String) {
        val media = mediaObject(item.copy(season = null, episode = null)) ?: return
        val key = if (item.isMovie) "movies" else "shows"
        val body = buildJsonObject { putJsonArray(key) { add(JsonObject(mapOf("to" to JsonPrimitive(status)) + media)) } }
        post("sync/add-to-list", body, tokens).requireOk(SERVICE, "list update")
    }

    /**
     * Moves the item to "Plan to Watch" (POST sync/add-to-list), or takes it off Simkl's lists
     * (POST sync/history/remove), which is how the TV app removes from a list.
     */
    suspend fun setWatchlisted(tokens: TrackingTokens, item: TrackedItem, on: Boolean) {
        val media = mediaObject(item.copy(season = null, episode = null)) ?: return
        val key = if (item.isMovie) "movies" else "shows"
        val body = if (on) {
            buildJsonObject { putJsonArray(key) { add(JsonObject(mapOf("to" to JsonPrimitive(PLAN_TO_WATCH)) + media)) } }
        } else {
            buildJsonObject { putJsonArray(key) { add(media) } }
        }
        val path = if (on) "sync/add-to-list" else "sync/history/remove"
        post(path, body, tokens).requireOk(SERVICE, "watchlist update")
    }

    private suspend fun allItems(tokens: TrackingTokens, type: String, status: String?): List<SimklLibraryEntryDto> {
        val path = listOfNotNull("sync/all-items", type, status).joinToString("/")
        val result = get(path, tokens, ALL_ITEMS_QUERY).requireOk(SERVICE, "library")
        val element = trackingJson.parseToJsonElement(result.body.ifBlank { "null" })
        if (element is JsonNull || element !is JsonObject) return emptyList()
        return (element[type] as? JsonArray)?.let { trackingJson.decodeFromJsonElement<List<SimklLibraryEntryDto>>(it) }
            .orEmpty()
    }

    private suspend fun get(path: String, tokens: TrackingTokens?, query: Map<String, String> = emptyMap()) =
        http.send(request(path, tokens, query).get().build())

    private suspend fun post(path: String, body: JsonObject, tokens: TrackingTokens) =
        http.send(request(path, tokens).post(body.toJsonBody()).build())

    private fun request(path: String, tokens: TrackingTokens?, query: Map<String, String> = emptyMap()): Request.Builder {
        check(credentials.simklAvailable) { "Simkl isn't configured in this build" }
        val url: HttpUrl = base.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("client_id", credentials.simklClientId)
            addQueryParameter("app-name", credentials.simklAppName)
            addQueryParameter("app-version", appVersion)
        }.build()
        return Request.Builder().url(url)
            .header("User-Agent", "Fetch/$appVersion")
            .header("Accept", "application/json")
            .header("simkl-api-key", credentials.simklClientId)
            .apply { tokens?.let { header("Authorization", "Bearer ${it.accessToken}") } }
    }

    internal companion object {
        const val SERVICE = "Simkl"
        const val PLAN_TO_WATCH = "plantowatch"
        val MEDIA_TYPES = listOf("movies", "shows", "anime")

        /** The TV app's bootstrap query, which lists every episode with its watched time. */
        val ALL_ITEMS_QUERY = mapOf(
            "extended" to "full_anime_seasons",
            "episode_watched_at" to "yes",
            "episode_tvdb_id" to "yes",
            "include_all_episodes" to "yes",
            "language" to "en",
        )

        /** `{"title", "year", "ids": {"imdb"}}` for the movie or series, or null without an IMDb id. */
        fun mediaObject(item: TrackedItem): JsonObject? {
            val imdb = item.imdbId.imdbOrNull() ?: return null
            return buildJsonObject {
                item.title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
                item.year?.let { put("year", it) }
                putJsonObject("ids") { put("imdb", imdb) }
            }
        }

        /** The TV app's scrobble body, or null when the item can't be scrobbled. */
        fun scrobbleBody(item: TrackedItem, progressPercent: Float): JsonObject? {
            val media = mediaObject(item) ?: return null
            if (!item.isMovie && !item.isEpisode) return null
            val progress = round(progressPercent.toDouble().coerceIn(0.0, 100.0) * 100.0) / 100.0
            return buildJsonObject {
                put("progress", progress)
                put(if (item.isMovie) "movie" else "show", media)
                if (item.isEpisode) putJsonObject("episode") {
                    put("season", item.season)
                    put("number", item.episode)
                }
            }
        }
    }
}

@Serializable
private data class SimklPinDto(
    val result: String? = null,
    val message: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_url") val verificationUrl: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int? = null,
    @SerialName("access_token") val accessToken: String? = null,
)

@Serializable
private data class SimklUserSettingsDto(val user: SimklUserDto? = null)

@Serializable
private data class SimklUserDto(val name: String? = null)

@Serializable
private data class SimklMediaDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: Map<String, JsonElement> = emptyMap(),
) {
    fun imdb(): String? = (ids["imdb"] as? JsonPrimitive)?.content.imdbOrNull()
}

@Serializable
private data class SimklPlaybackEpisodeDto(
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null,
    @SerialName("tvdb_season") val tvdbSeason: Int? = null,
    @SerialName("tvdb_number") val tvdbNumber: Int? = null,
)

@Serializable
private data class SimklPlaybackDto(
    val progress: Double = 0.0,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    val episode: SimklPlaybackEpisodeDto? = null,
    val movie: SimklMediaDto? = null,
    val show: SimklMediaDto? = null,
    val anime: SimklMediaDto? = null,
) {
    fun toRemote(): RemotePlayback? {
        val pausedAt = parseIsoEpochMs(pausedAt) ?: parseIsoEpochMs(watchedAt) ?: 0L
        if (movie != null) {
            val imdb = movie.imdb() ?: return null
            return RemotePlayback(
                TrackedItem(imdb, TrackedItem.TYPE_MOVIE, title = movie.title, year = movie.year),
                progress.toFloat(), pausedAt, showTitle = null, episodeTitle = null,
            )
        }
        val show = show ?: anime ?: return null
        val imdb = show.imdb() ?: return null
        // Same preference as the TV app: TVDB numbering first, which anime entries carry.
        val episode = episode ?: return null
        val season = episode.tvdbSeason ?: episode.season ?: return null
        val number = episode.tvdbNumber ?: episode.number ?: return null
        return RemotePlayback(
            TrackedItem(imdb, TrackedItem.TYPE_SERIES, season, number, show.title, show.year),
            progress.toFloat(), pausedAt, showTitle = show.title, episodeTitle = episode.title,
        )
    }
}

@Serializable
private data class SimklEpisodeMappingDto(val season: Int? = null, val episode: Int? = null)

@Serializable
private data class SimklLibraryEpisodeDto(
    val number: Int? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    val tvdb: SimklEpisodeMappingDto? = null,
)

@Serializable
private data class SimklLibrarySeasonDto(val number: Int? = null, val episodes: List<SimklLibraryEpisodeDto>? = null)

@Serializable
private data class SimklLibraryEntryDto(
    @SerialName("added_to_watchlist_at") val addedToWatchlistAt: String? = null,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val status: String? = null,
    val show: SimklMediaDto? = null,
    val movie: SimklMediaDto? = null,
    @SerialName("anime_type") val animeType: String? = null,
    val seasons: List<SimklLibrarySeasonDto>? = null,
) {
    val media: SimklMediaDto? get() = movie ?: show

    /** A movie, or an anime movie. */
    fun isMovie(type: String): Boolean = type == "movies" || (type == "anime" && animeType == "movie")
}
