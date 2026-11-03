package com.fetch.desktop.mdblist

import com.fetch.desktop.data.PlaybackTarget
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.tracking.ScrobbleAction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** A movie or one episode of a show, as MDBList's sync and scrobble calls name it (by IMDb id). */
data class MdbListTarget(val imdb: String, val isMovie: Boolean, val season: Int? = null, val episode: Int? = null, val title: String? = null) {
    val isEpisode: Boolean get() = !isMovie && season != null && episode != null

    /** The scrobble body (the TV's `MdbListMutationTarget.scrobbleBody`): the movie, or the show with season and episode. */
    fun scrobbleBody(progress: Double?): JsonObject = buildJsonObject {
        put(if (isMovie) "movie" else "show", buildJsonObject {
            put("ids", buildJsonObject { put("imdb", imdb) })
            if (!isMovie) {
                put("season", season ?: 0)
                put("episode", episode ?: 0)
            }
        })
        progress?.let { put("progress", BigDecimal.valueOf(it.coerceIn(0.0, 100.0)).setScale(2, RoundingMode.DOWN).toDouble()) }
    }

    companion object {
        /** MDBList matches by IMDb id here; titles without one aren't sent, as on TV. */
        fun of(target: PlaybackTarget): MdbListTarget? {
            val imdb = target.contentId.takeIf { it.matches(Regex("tt[0-9]+")) } ?: return null
            val video = target.video
            return if (target.contentType == "movie" || video == null) MdbListTarget(imdb, isMovie = true, title = target.name)
            else MdbListTarget(imdb, isMovie = false, season = video.season, episode = video.episode, title = target.name)
                .takeIf { it.isEpisode }
        }
    }
}

/** A resume point on MDBList (`/sync/playback`). [progress] is 0–100. */
data class MdbListPlayback(
    val imdb: String,
    val isMovie: Boolean,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val progress: Float,
    val updatedAtMs: Long
)

/** Everything watched on MDBList (`/sync/watched`): movie ids, and (season, episode) pairs per show. */
data class MdbListWatched(val movies: Set<String>, val episodes: Map<String, Set<Pair<Int, Int>>>) {
    /** Cinemeta's ids: "tt123" for a movie, "tt123:2:5" for an episode. */
    fun videoIds(): Set<String> = movies + episodes.flatMap { (show, eps) -> eps.map { (s, e) -> "$show:$s:$e" } }
}

/**
 * MDBList's watched history, playback progress and scrobbling for a connected account, as the
 * TV's `MdbListHttpSyncRemote`, `MdbListScrobbleService` and `MdbListHistoryService` call them.
 */
class MdbListSyncClient(private val http: MdbListHttp) {

    suspend fun playback(auth: MdbListAuth): List<MdbListPlayback> {
        val root = http.get("/sync/playback", auth).json() as? JsonArray ?: invalid()
        return root.mapNotNull { element -> decodePlayback(element as? JsonObject ?: return@mapNotNull null) }
    }

    /** Every page of `/sync/watched`, following its cursor or offset. */
    suspend fun watched(auth: MdbListAuth): MdbListWatched {
        val movies = linkedSetOf<String>()
        val episodes = linkedMapOf<String, MutableSet<Pair<Int, Int>>>()
        var query = mapOf("limit" to "1000")
        val seen = mutableSetOf<Map<String, String>>()
        repeat(MAX_PAGES) {
            if (!seen.add(query)) return@repeat
            val payload = http.get("/sync/watched", auth, query).json().obj()
            (payload["movies"] as? JsonArray).orEmpty().forEach { row ->
                val o = row as? JsonObject ?: return@forEach
                imdbOf(o["movie"] as? JsonObject ?: o)?.let(movies::add)
            }
            (payload["episodes"] as? JsonArray).orEmpty().forEach { row ->
                val o = row as? JsonObject ?: return@forEach
                val episode = o["episode"] as? JsonObject ?: return@forEach
                val show = (o["show"] as? JsonObject) ?: (episode["show"] as? JsonObject) ?: return@forEach
                val imdb = imdbOf(show) ?: return@forEach
                val s = episode.number("season")?.toInt() ?: return@forEach
                val e = episode.number("number", "episode")?.toInt() ?: return@forEach
                episodes.getOrPut(imdb) { linkedSetOf() } += s to e
            }
            (payload["shows"] as? JsonArray).orEmpty().forEach { row ->
                val o = row as? JsonObject ?: return@forEach
                val imdb = imdbOf(o["show"] as? JsonObject ?: return@forEach) ?: return@forEach
                (o["seasons"] as? JsonArray).orEmpty().forEach seasons@{ seasonEl ->
                    val season = seasonEl as? JsonObject ?: return@seasons
                    val s = season.number("number", "season")?.toInt() ?: return@seasons
                    (season["episodes"] as? JsonArray).orEmpty().forEach { epEl ->
                        val ep = epEl as? JsonObject ?: return@forEach
                        val e = ep.number("number", "episode")?.toInt() ?: return@forEach
                        episodes.getOrPut(imdb) { linkedSetOf() } += s to e
                    }
                }
            }
            val pagination = payload["pagination"] as? JsonObject ?: payload
            val cursor = pagination.text("next_cursor")
            val offset = pagination.number("offset")
            val limit = pagination.number("limit")
            val total = pagination.number("total")
            val hasMore = pagination.flag("has_more") ?: (offset != null && limit != null && total != null && offset + limit < total)
            query = when {
                cursor != null -> mapOf("limit" to "1000", "cursor" to cursor)
                hasMore && offset != null && limit != null -> mapOf("limit" to "1000", "offset" to (offset + limit).toString())
                else -> return MdbListWatched(movies, episodes)
            }
        }
        return MdbListWatched(movies, episodes)
    }

    /** POST /scrobble/{start|pause|stop}, as the TV scrobbles playback to MDBList. */
    suspend fun scrobble(auth: MdbListAuth, action: ScrobbleAction, target: MdbListTarget, progress: Double) {
        if (!progress.isFinite()) return
        val path = when (action) {
            ScrobbleAction.START -> "/scrobble/start"
            ScrobbleAction.PAUSE -> "/scrobble/pause"
            ScrobbleAction.STOP -> "/scrobble/stop"
        }
        http.post(path, target.scrobbleBody(progress), auth)
    }

    /** Adds [targets] to the watched history (POST /sync/watched) or removes them (…/remove). */
    suspend fun setWatched(auth: MdbListAuth, targets: List<MdbListTarget>, watched: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (targets.isEmpty()) return
        val body = historyPayload(targets, if (watched) Instant.ofEpochMilli(nowMs).toString() else null)
        if (watched) http.post("/sync/watched", body, auth, mapOf("report_added" to "true"))
        else http.post("/sync/watched/remove", body, auth)
    }

    /** Drops a resume point (POST /scrobble/clear), when it leaves Continue Watching. */
    suspend fun clearPlayback(auth: MdbListAuth, target: MdbListTarget) {
        try {
            http.post("/scrobble/clear", target.scrobbleBody(null), auth)
        } catch (e: MdbListException) {
            if (e.status != 404) throw e
        }
    }

    companion object {
        private const val MAX_PAGES = 200

        /** The TV's `mdbListHistoryPayload` by IMDb id: movies, and shows with their season's episode. */
        fun historyPayload(targets: List<MdbListTarget>, watchedAt: String?): JsonObject = buildJsonObject {
            val movies = targets.filter { it.isMovie }.distinctBy { it.imdb }
            val episodes = targets.filter { it.isEpisode }
            if (movies.isNotEmpty()) put("movies", JsonArray(movies.map { m ->
                buildJsonObject {
                    put("ids", buildJsonObject { put("imdb", m.imdb) })
                    watchedAt?.let { put("watched_at", it) }
                }
            }))
            if (episodes.isNotEmpty()) put("shows", JsonArray(episodes.groupBy { it.imdb }.map { (imdb, eps) ->
                buildJsonObject {
                    put("ids", buildJsonObject { put("imdb", imdb) })
                    put("seasons", JsonArray(eps.groupBy { it.season!! }.map { (season, inSeason) ->
                        buildJsonObject {
                            put("number", season)
                            put("episodes", JsonArray(inSeason.distinctBy { it.episode }.map { ep ->
                                buildJsonObject {
                                    put("number", ep.episode!!)
                                    watchedAt?.let { put("watched_at", it) }
                                }
                            }))
                        }
                    }))
                }
            }))
        }

        private fun imdbOf(o: JsonObject): String? {
            val ids = o["ids"] as? JsonObject ?: o
            return ids.text("imdb", "imdbid")?.takeIf { it.matches(Regex("tt[0-9]+")) }
        }

        internal fun decodePlayback(row: JsonObject): MdbListPlayback? {
            val isMovie = when (row.text("type")) {
                "movie" -> true
                "episode" -> false
                else -> return null
            }
            val episode = row["episode"] as? JsonObject
            val parent = (row[if (isMovie) "movie" else "show"] as? JsonObject) ?: (episode?.get("show") as? JsonObject) ?: return null
            val imdb = imdbOf(parent) ?: return null
            val progress = row.text("progress")?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f } ?: return null
            val updated = (row.text("paused_at") ?: row.text("updated_at"))?.let(MdbListAccountClient::timestamp) ?: return null
            val season = episode?.number("season")?.toInt()
            val number = episode?.number("number", "episode")?.toInt()
            if (!isMovie && (season == null || season < 0 || number == null || number < 1)) return null
            return MdbListPlayback(imdb, isMovie, parent.text("title", "name"), season, number, episode?.text("title", "name"), progress, updated)
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/** At or past this percent a session counts as watched, as on TV. */
const val MDBLIST_COMPLETION_PERCENT = 80f

/** A resume point as a Continue Watching entry (resumed by percentage, as Trakt's are). */
fun MdbListPlayback.toProgressEntry(): ProgressEntry? {
    if (progress >= MDBLIST_COMPLETION_PERCENT) return null
    return ProgressEntry(
        contentId = imdb,
        contentType = if (isMovie) "movie" else "series",
        name = title ?: imdb,
        poster = "https://images.metahub.space/poster/small/$imdb/img",
        backdrop = "https://images.metahub.space/background/medium/$imdb/img",
        logo = "https://images.metahub.space/logo/medium/$imdb/img",
        videoId = if (isMovie) imdb else "$imdb:$season:$episode",
        season = season.takeIf { !isMovie },
        episode = episode.takeIf { !isMovie },
        episodeTitle = episodeTitle,
        positionMs = 0,
        durationMs = 0,
        lastWatched = updatedAtMs,
        remotePercent = progress,
        source = "mdblist"
    )
}

/**
 * MDBList as a tracking service for the profile's connected account: what you play is
 * scrobbled, Mark watched goes to its history, and its resume points and history come back
 * with each tracking sync. Nothing happens while no account is connected.
 */
class MdbListTracker(private val account: MdbListAccountState, private val client: MdbListSyncClient) {
    val connected: Boolean get() = account.connected

    suspend fun scrobble(target: PlaybackTarget, action: ScrobbleAction, percent: Float) {
        val item = MdbListTarget.of(target) ?: return
        quietly { account.withAuth { auth -> client.scrobble(auth, action, item, percent.toDouble()) } }
    }

    suspend fun setWatched(target: PlaybackTarget, watched: Boolean) {
        val item = MdbListTarget.of(target) ?: return
        quietly { account.withAuth { auth -> client.setWatched(auth, listOf(item), watched) } }
    }

    /** Drops [entry]'s resume point on MDBList (Remove from Continue Watching). */
    suspend fun clear(entry: ProgressEntry) {
        val imdb = entry.contentId.takeIf { it.startsWith("tt") } ?: return
        val target = if (entry.season != null && entry.episode != null) MdbListTarget(imdb, false, entry.season, entry.episode)
        else MdbListTarget(imdb, isMovie = entry.contentType == "movie")
        if (!target.isMovie && !target.isEpisode) return
        quietly { account.withAuth { auth -> client.clearPlayback(auth, target) } }
    }

    /** Resume points and watched history, or null when no account is connected. */
    suspend fun pull(): Pair<List<ProgressEntry>, Set<String>>? {
        if (!connected) return null
        return account.withAuth { auth ->
            val playback = client.playback(auth).mapNotNull { it.toProgressEntry() }
            val watched = client.watched(auth).videoIds()
            playback to watched
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed scrobble or history change isn't worth interrupting playback for, as on TV.
        }
    }
}
