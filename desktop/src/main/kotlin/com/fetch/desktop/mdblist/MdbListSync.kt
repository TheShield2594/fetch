package com.fetch.desktop.mdblist

import com.fetch.desktop.data.PlaybackTarget
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.tracking.ScrobbleAction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * A title's ids on MDBList, as the TV's `MdbListIds`. MDBList matches on any of them, so titles
 * without an IMDb id (some anime, titles from TMDB or TVDB catalogs) are still tracked.
 */
@Serializable
data class MdbListIds(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
    val trakt: Long? = null,
    val mdblist: String? = null
) {
    val isEmpty: Boolean get() = imdb == null && tmdb == null && tvdb == null && trakt == null && mdblist == null

    /** The id Fetch uses for the title: the IMDb id, else "tmdb:…", "tvdb:…", "trakt:…" or "mdblist:…". */
    val contentId: String
        get() = imdb ?: tmdb?.let { "tmdb:$it" } ?: tvdb?.let { "tvdb:$it" } ?: trakt?.let { "trakt:$it" }
            ?: mdblist?.let { "mdblist:$it" } ?: ""

    fun aliases(): Set<String> = buildSet {
        imdb?.let { add(it) }
        tmdb?.let { add("tmdb:$it") }
        tvdb?.let { add("tvdb:$it") }
        trakt?.let { add("trakt:$it") }
        mdblist?.let { add("mdblist:$it") }
    }

    fun matches(other: MdbListIds): Boolean = aliases().any { it in other.aliases() }

    fun merged(other: MdbListIds) = MdbListIds(
        imdb ?: other.imdb, tmdb ?: other.tmdb, tvdb ?: other.tvdb, trakt ?: other.trakt, mdblist ?: other.mdblist
    )

    /** The `ids` object of a request body. */
    fun json(): JsonObject = buildJsonObject {
        imdb?.let { put("imdb", it) }
        tmdb?.let { put("tmdb", it) }
        tvdb?.let { put("tvdb", it) }
        trakt?.let { put("trakt", it) }
        mdblist?.let { put("mdblist", it) }
    }

    companion object {
        private val IMDB = Regex("tt[0-9]+")
        private val PREFIXED = Regex("(tmdb|tvdb|trakt|mdblist):([0-9A-Za-z_-]+)")

        /** A Fetch content id ("tt123", "tmdb:123", "tvdb:123", "trakt:123", "mdblist:abc"), or null for any other. */
        fun fromContentId(id: String): MdbListIds? {
            if (id.matches(IMDB)) return MdbListIds(imdb = id)
            val match = PREFIXED.matchEntire(id) ?: return null
            val (kind, value) = match.destructured
            val number = value.toLongOrNull()?.takeIf { it > 0 }
            return when (kind) {
                "tmdb" -> number?.let { MdbListIds(tmdb = it) }
                "tvdb" -> number?.let { MdbListIds(tvdb = it) }
                "trakt" -> number?.let { MdbListIds(trakt = it) }
                else -> MdbListIds(mdblist = value)
            }
        }

        /** The `ids` of a response object (or the object itself), or null when it names none. */
        internal fun decode(o: JsonObject): MdbListIds? {
            val ids = o["ids"] as? JsonObject ?: o
            return MdbListIds(
                imdb = ids.text("imdb", "imdbid")?.takeIf { it.matches(IMDB) },
                tmdb = ids.number("tmdb", "tmdbid")?.takeIf { it > 0 },
                tvdb = ids.number("tvdb", "tvdbid")?.takeIf { it > 0 },
                trakt = ids.number("trakt", "traktid")?.takeIf { it > 0 },
                mdblist = ids.text("mdblist")
            ).takeUnless { it.isEmpty }
        }
    }
}

/** A movie or one episode of a show, as MDBList's sync and scrobble calls name it. */
data class MdbListTarget(val ids: MdbListIds, val isMovie: Boolean, val season: Int? = null, val episode: Int? = null, val title: String? = null) {
    val isEpisode: Boolean get() = !isMovie && season != null && episode != null

    /** The scrobble body (the TV's `MdbListMutationTarget.scrobbleBody`): the movie, or the show with season and episode. */
    fun scrobbleBody(progress: Double?): JsonObject = buildJsonObject {
        put(if (isMovie) "movie" else "show", buildJsonObject {
            put("ids", ids.json())
            if (!isMovie) {
                put("season", season ?: 0)
                put("episode", episode ?: 0)
            }
        })
        progress?.let { put("progress", BigDecimal.valueOf(it.coerceIn(0.0, 100.0)).setScale(2, RoundingMode.DOWN).toDouble()) }
    }

    companion object {
        /** By IMDb id, or by TMDB, TVDB, Trakt or MDBList id for titles from catalogs that use those. */
        fun of(target: PlaybackTarget): MdbListTarget? {
            val ids = MdbListIds.fromContentId(target.contentId) ?: return null
            val video = target.video
            return if (target.contentType == "movie" || video == null) MdbListTarget(ids, isMovie = true, title = target.name)
            else MdbListTarget(ids, isMovie = false, season = video.season, episode = video.episode, title = target.name)
                .takeIf { it.isEpisode }
        }
    }
}

/** A resume point on MDBList (`/sync/playback`). [progress] is 0–100. */
@Serializable
data class MdbListPlayback(
    val ids: MdbListIds,
    val isMovie: Boolean,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val progress: Float,
    val updatedAtMs: Long,
    val poster: String? = null,
    val backdrop: String? = null
)

/** One watched movie or episode in MDBList's history (`/sync/watched`, `/sync/journal`). */
@Serializable
data class MdbListWatchedItem(val ids: MdbListIds, val isMovie: Boolean, val season: Int? = null, val episode: Int? = null) {
    /** Fetch's video id: "tt123" for a movie, "tt123:2:5" for an episode (or "tmdb:…" without an IMDb id). */
    val videoId: String get() = if (isMovie) ids.contentId else "${ids.contentId}:$season:$episode"
}

/** A show (or one of its seasons, when [season] is set) the user dropped on MDBList. */
@Serializable
data class MdbListDropped(val ids: MdbListIds, val season: Int? = null)

/** Everything watched on MDBList. */
data class MdbListWatched(val items: List<MdbListWatchedItem>) {
    fun videoIds(): Set<String> = items.mapTo(linkedSetOf()) { it.videoId }
}

/** `/sync/last_activities`: when each kind of change last happened, and the server's clock. */
@Serializable
data class MdbListActivities(val values: Map<String, String?>, val serverTime: String) {
    fun watchedChanged(previous: MdbListActivities) = changed(previous) { it.contains("watched") || it == "journal_at" }
    fun playbackChanged(previous: MdbListActivities) = changed(previous) { it.contains("paused") }
    fun droppedChanged(previous: MdbListActivities) = changed(previous) { it.contains("dropped") }

    private fun changed(previous: MdbListActivities, includes: (String) -> Boolean): Boolean =
        (values.keys + previous.values.keys).filter(includes).any { values[it] != previous.values[it] }
}

/** One watched-history change since a time (`/sync/journal`). */
data class MdbListJournalEntry(
    val kind: Kind,
    val ids: MdbListIds,
    val removed: Boolean,
    val actionAtMs: Long,
    val season: Int? = null,
    val episode: Int? = null
) {
    enum class Kind { MOVIE, SHOW, SEASON, EPISODE }
}

/** MDBList's journal can't answer from that time: fetch the whole history instead. */
class MdbListJournalExpired : Exception("MDBList asked for a full sync")

/**
 * MDBList's watched history, playback progress and scrobbling for a connected account, as the
 * TV's `MdbListHttpSyncRemote`, `MdbListScrobbleService` and `MdbListHistoryService` call them.
 */
class MdbListSyncClient(private val http: MdbListHttp) {

    suspend fun playback(auth: MdbListAuth): List<MdbListPlayback> {
        val root = http.get("/sync/playback", auth).json() as? JsonArray ?: invalid()
        return root.mapNotNull { element -> decodePlayback(element as? JsonObject ?: return@mapNotNull null) }
    }

    /** `/sync/last_activities`, or null when MDBList's answer doesn't say what changed. */
    suspend fun activities(auth: MdbListAuth): MdbListActivities? {
        val payload = http.get("/sync/last_activities", auth).json() as? JsonObject ?: return null
        val serverTime = payload.text("server_time") ?: return null
        if (listOf("watched_at", "episode_watched_at", "journal_at").none(payload::containsKey)) return null
        return MdbListActivities(payload.keys.filter { it.endsWith("_at") }.associateWith { payload.text(it) }, serverTime)
    }

    /** Every page of `/sync/watched`, following its cursor or offset. */
    suspend fun watched(auth: MdbListAuth): MdbListWatched {
        val items = linkedSetOf<MdbListWatchedItem>()
        pages(auth, "/sync/watched") { payload ->
            (payload["movies"] as? JsonArray).orEmpty().forEach { row ->
                val o = row as? JsonObject ?: return@forEach
                MdbListIds.decode(o["movie"] as? JsonObject ?: o)?.let { items += MdbListWatchedItem(it, isMovie = true) }
            }
            (payload["episodes"] as? JsonArray).orEmpty().forEach { row ->
                val o = row as? JsonObject ?: return@forEach
                val episode = o["episode"] as? JsonObject ?: return@forEach
                val show = (o["show"] as? JsonObject) ?: (episode["show"] as? JsonObject) ?: return@forEach
                val ids = MdbListIds.decode(show) ?: return@forEach
                val s = episode.number("season")?.toInt() ?: return@forEach
                val e = episode.number("number", "episode")?.toInt() ?: return@forEach
                items += MdbListWatchedItem(ids, isMovie = false, season = s, episode = e)
            }
            (payload["shows"] as? JsonArray).orEmpty().forEach { row ->
                val o = row as? JsonObject ?: return@forEach
                val ids = MdbListIds.decode(o["show"] as? JsonObject ?: return@forEach) ?: return@forEach
                (o["seasons"] as? JsonArray).orEmpty().forEach seasons@{ seasonEl ->
                    val season = seasonEl as? JsonObject ?: return@seasons
                    val s = season.number("number", "season")?.toInt() ?: return@seasons
                    (season["episodes"] as? JsonArray).orEmpty().forEach { epEl ->
                        val ep = epEl as? JsonObject ?: return@forEach
                        val e = ep.number("number", "episode")?.toInt() ?: return@forEach
                        items += MdbListWatchedItem(ids, isMovie = false, season = s, episode = e)
                    }
                }
            }
        }
        return MdbListWatched(items.toList())
    }

    /**
     * What changed in the watched history since [since] (`/sync/journal`). Throws
     * [MdbListJournalExpired] when MDBList can't answer from then, or answers with something
     * this doesn't understand, so the caller fetches the whole history instead.
     */
    suspend fun journal(auth: MdbListAuth, since: String): List<MdbListJournalEntry> {
        val entries = mutableListOf<MdbListJournalEntry>()
        try {
            pages(auth, "/sync/journal", mapOf("since" to since), offsets = false) { payload ->
                if (payload.flag("requires_full_sync") == true) throw MdbListJournalExpired()
                val rows = payload["journal"] as? JsonArray ?: throw MdbListJournalExpired()
                rows.forEach { element -> decodeJournal(element as? JsonObject ?: throw MdbListJournalExpired())?.let(entries::add) }
            }
        } catch (e: MdbListException) {
            if (e.status == 409 || e.code == "invalid_response") throw MdbListJournalExpired()
            throw e
        }
        return entries
    }

    /** Shows and seasons the user dropped (`/sync/dropped`, `/sync/seasons/dropped`). */
    suspend fun dropped(auth: MdbListAuth): List<MdbListDropped> {
        val dropped = mutableListOf<MdbListDropped>()
        pages(auth, "/sync/dropped") { payload ->
            (payload["shows"] as? JsonArray).orEmpty().forEach { row ->
                val o = row as? JsonObject ?: return@forEach
                MdbListIds.decode(o["show"] as? JsonObject ?: o)?.let { dropped += MdbListDropped(it) }
            }
        }
        pages(auth, "/sync/seasons/dropped") { payload ->
            (payload["seasons"] as? JsonArray).orEmpty().forEach { row ->
                val o = row as? JsonObject ?: return@forEach
                val season = o["season"] as? JsonObject ?: return@forEach
                val show = (o["show"] as? JsonObject) ?: (season["show"] as? JsonObject) ?: return@forEach
                val ids = MdbListIds.decode(show) ?: return@forEach
                val number = season.number("number", "season")?.toInt()?.takeIf { it >= 0 } ?: return@forEach
                dropped += MdbListDropped(ids, number)
            }
        }
        return dropped
    }

    /** Reads every page of [path], following `next_cursor` (or the offset, when [offsets] allows it). */
    private suspend fun pages(
        auth: MdbListAuth,
        path: String,
        initial: Map<String, String> = emptyMap(),
        offsets: Boolean = true,
        read: (JsonObject) -> Unit
    ) {
        var query = initial + ("limit" to "1000")
        val seen = mutableSetOf<Map<String, String>>()
        repeat(MAX_PAGES) {
            if (!seen.add(query)) return
            val payload = http.get(path, auth, query).json().obj()
            read(payload)
            val pagination = payload["pagination"] as? JsonObject ?: payload
            val cursor = pagination.text("next_cursor")
            val offset = pagination.number("offset")
            val limit = pagination.number("limit")
            val total = pagination.number("total")
            val hasMore = pagination.flag("has_more") ?: (offset != null && limit != null && total != null && offset + limit < total)
            query = when {
                // As on TV, the cursor replaces `since`: it already carries where the journal resumes.
                cursor != null -> initial.minus("since") + mapOf("limit" to "1000", "cursor" to cursor)
                hasMore && offsets && offset != null && limit != null -> initial + mapOf("limit" to "1000", "offset" to (offset + limit).toString())
                hasMore && !offsets -> invalid()
                else -> return
            }
        }
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

        /** The TV's `mdbListHistoryPayload`: movies, and shows with their season's episode. */
        fun historyPayload(targets: List<MdbListTarget>, watchedAt: String?): JsonObject = buildJsonObject {
            val movies = targets.filter { it.isMovie }.distinctBy { it.ids }
            val episodes = targets.filter { it.isEpisode }
            if (movies.isNotEmpty()) put("movies", JsonArray(movies.map { m ->
                buildJsonObject {
                    put("ids", m.ids.json())
                    watchedAt?.let { put("watched_at", it) }
                }
            }))
            if (episodes.isNotEmpty()) put("shows", JsonArray(episodes.groupBy { it.ids }.map { (ids, eps) ->
                buildJsonObject {
                    put("ids", ids.json())
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

        internal fun decodePlayback(row: JsonObject): MdbListPlayback? {
            val isMovie = when (row.text("type")) {
                "movie" -> true
                "episode" -> false
                else -> return null
            }
            val episode = row["episode"] as? JsonObject
            val parent = (row[if (isMovie) "movie" else "show"] as? JsonObject) ?: (episode?.get("show") as? JsonObject) ?: return null
            val ids = MdbListIds.decode(parent) ?: return null
            val progress = row.text("progress")?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f } ?: return null
            val updated = (row.text("paused_at") ?: row.text("updated_at"))?.let(MdbListAccountClient::timestamp) ?: return null
            val season = episode?.number("season")?.toInt()
            val number = episode?.number("number", "episode")?.toInt()
            if (!isMovie && (season == null || season < 0 || number == null || number < 1)) return null
            return MdbListPlayback(
                ids, isMovie, parent.text("title", "name"), season, number, episode?.text("title", "name"), progress, updated,
                poster = imageUrl(parent.text("poster"), "w500"),
                backdrop = imageUrl(parent.text("backdrop", "background"), "w1280")
            )
        }

        /** A journal row, or null for one that isn't about watched history (ratings). */
        internal fun decodeJournal(row: JsonObject): MdbListJournalEntry? {
            when (row.text("category")) {
                "watched" -> Unit
                "rated" -> return null
                else -> throw MdbListJournalExpired()
            }
            val kind = MdbListJournalEntry.Kind.entries.firstOrNull { it.name.equals(row.text("item_type"), ignoreCase = true) }
                ?: throw MdbListJournalExpired()
            val removed = when (row.text("status")) {
                "added" -> false
                "removed" -> true
                else -> throw MdbListJournalExpired()
            }
            val ids = MdbListIds.decode(row["ids"] as? JsonObject ?: throw MdbListJournalExpired()) ?: throw MdbListJournalExpired()
            val actionAt = row.text("action_at")?.let(MdbListAccountClient::timestamp) ?: throw MdbListJournalExpired()
            val season = row.number("season")?.toInt()
            val episode = row.number("episode")?.toInt()
            if (kind in setOf(MdbListJournalEntry.Kind.SEASON, MdbListJournalEntry.Kind.EPISODE) && (season == null || season < 0)) throw MdbListJournalExpired()
            if (kind == MdbListJournalEntry.Kind.EPISODE && (episode == null || episode < 1)) throw MdbListJournalExpired()
            return MdbListJournalEntry(kind, ids, removed, actionAt, season, episode)
        }

        /** The TV's `mdbListImageUrl`: a full URL, or a TMDB path. */
        internal fun imageUrl(value: String?, size: String): String? = when {
            value == null -> null
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith('/') && !value.startsWith("//") -> "https://image.tmdb.org/t/p/$size$value"
            else -> null
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/** At or past this percent a session counts as watched, as on TV. */
const val MDBLIST_COMPLETION_PERCENT = 80f

/** A resume point as a Continue Watching entry (resumed by percentage, as Trakt's are). */
fun MdbListPlayback.toProgressEntry(): ProgressEntry? {
    if (progress >= MDBLIST_COMPLETION_PERCENT) return null
    val id = ids.contentId
    val imdb = ids.imdb
    return ProgressEntry(
        contentId = id,
        contentType = if (isMovie) "movie" else "series",
        name = title ?: id,
        poster = imdb?.let { "https://images.metahub.space/poster/small/$it/img" } ?: poster,
        backdrop = imdb?.let { "https://images.metahub.space/background/medium/$it/img" } ?: backdrop,
        logo = imdb?.let { "https://images.metahub.space/logo/medium/$it/img" },
        videoId = if (isMovie) id else "$id:$season:$episode",
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
 *
 * Syncs are incremental, as on TV: [MdbListSyncEngine] asks what changed since the last one and
 * keeps the result in [cache] (when given), so only what changed is fetched again.
 */
class MdbListTracker(
    private val account: MdbListAccountState,
    private val client: MdbListSyncClient,
    private val cache: MdbListSyncCacheStore? = null,
    private val engine: MdbListSyncEngine = MdbListSyncEngine(client)
) {
    val connected: Boolean get() = account.connected

    suspend fun scrobble(target: PlaybackTarget, action: ScrobbleAction, percent: Float) {
        val item = MdbListTarget.of(target) ?: return
        quietly { account.withAuth { auth -> client.scrobble(auth, action, item, percent.toDouble()) } }
    }

    suspend fun setWatched(target: PlaybackTarget, watched: Boolean, atMs: Long? = null) {
        val item = MdbListTarget.of(target) ?: return
        quietly { account.withAuth { auth -> client.setWatched(auth, listOf(item), watched, atMs ?: System.currentTimeMillis()) } }
    }

    /** Drops [entry]'s resume point on MDBList (Remove from Continue Watching). */
    suspend fun clear(entry: ProgressEntry) {
        val ids = MdbListIds.fromContentId(entry.contentId) ?: return
        val target = if (entry.season != null && entry.episode != null) MdbListTarget(ids, false, entry.season, entry.episode)
        else MdbListTarget(ids, isMovie = entry.contentType == "movie")
        if (!target.isMovie && !target.isEpisode) return
        quietly { account.withAuth { auth -> client.clearPlayback(auth, target) } }
    }

    /**
     * Resume points and watched history, or null when no account is connected. Resume points
     * in shows (or seasons) dropped on MDBList stay out of Continue Watching, as on TV.
     */
    suspend fun pull(): Pair<List<ProgressEntry>, Set<String>>? {
        if (!connected) return null
        val accountId = account.account?.userId ?: 0L
        return account.withAuth { auth ->
            val previous = cache?.load()?.takeIf { it.accountId == accountId }
            val state = engine.synchronize(auth, accountId, previous)
            cache?.save(state)
            state.continueWatching() to state.watchedIds()
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
