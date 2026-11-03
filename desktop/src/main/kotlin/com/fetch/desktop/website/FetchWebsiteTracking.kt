package com.fetch.desktop.website

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.LibraryList
import com.fetch.desktop.ListProvider
import com.fetch.desktop.data.CaughtUpShow
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.PlaybackTarget
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.ProgressFile
import com.fetch.desktop.data.WatchHistory
import com.fetch.desktop.tracking.ScrobbleAction
import com.fetch.tv.data.website.FETCH_WEBSITE_COMPLETION_PERCENT
import com.fetch.tv.data.website.FETCH_WEBSITE_PROVIDER_STORAGE_ID
import com.fetch.tv.data.website.FetchWebsiteLibraryProjection
import com.fetch.tv.data.website.FetchWebsiteProgressProjection
import com.fetch.tv.data.website.FetchWebsiteTrackingSnapshot
import com.fetch.tv.data.website.WebsiteWatchedEpisode
import com.fetch.tv.data.website.WebsiteWatchedMovie
import com.fetch.tv.data.website.fetchWebsiteItemType
import com.fetch.tv.data.website.fetchWebsiteListId
import com.fetch.tv.data.website.fetchWebsiteListKey
import com.fetch.tv.data.website.fetchWebsitePlaybackBody
import com.fetch.tv.data.website.playbackIdsFor
import com.fetch.tv.data.website.withListMembership
import com.fetch.tv.domain.model.LibraryEntryInput
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// The Fetch website as a tracking provider, next to Trakt, Simkl and MDBList, as the TV's
// FetchWebsiteTracking.kt. While this computer is paired, plays, scrobbles and watched marks
// reach the profile's website profile; choosing the website as the watch progress or Library
// source makes Continue Watching and the Library read from it. The website only knows titles by
// IMDb id; anything else stays on this computer. The projections that turn what the website
// holds into Continue Watching and Library tabs are the TV's own, in :shared.

/** A title as the website names it: a film by its IMDb id, an episode by its series' id and numbers. */
data class WebsiteTitle(val imdbId: String, val season: Int? = null, val episode: Int? = null) {
    val isEpisode: Boolean get() = season != null && episode != null

    companion object {
        /** [target] as the website knows it; null without an IMDb id, or for an episode without numbers. */
        fun of(target: PlaybackTarget): WebsiteTitle? {
            val imdb = target.contentId.substringBefore(':').takeIf { it.startsWith("tt") } ?: return null
            val video = target.video
            if (target.contentType == "movie" || video == null) return WebsiteTitle(imdb)
            val season = video.season ?: return null
            val episode = video.episode ?: return null
            return WebsiteTitle(imdb, season, episode)
        }
    }
}

/** The bodies the website's watch routes take, as the TV sends them. */
object FetchWebsiteBodies {
    /** The `type`, `imdbId` and episode fields every watch and scrobble body starts with. */
    private fun JsonObjectBuilder.describe(title: WebsiteTitle) {
        put("type", if (title.isEpisode) "episode" else "movie")
        put("imdbId", title.imdbId)
        if (title.season != null && title.episode != null) {
            put("seriesImdbId", title.imdbId)
            put("season", title.season)
            put("episode", title.episode)
        }
    }

    fun scrobble(title: WebsiteTitle, percent: Float): JsonObject = buildJsonObject {
        describe(title)
        put("progress", percent.toDouble().coerceIn(0.0, 100.0))
    }

    fun watch(title: WebsiteTitle, watchedAtMs: Long?): JsonObject = buildJsonObject {
        describe(title)
        watchedAtMs?.let { put("watchedAt", Instant.ofEpochMilli(it).toString()) }
    }

    /** A resume point for `PUT /playback`, in whole seconds (the TV's `fetchWebsitePlaybackBody`). */
    fun playback(target: PlaybackTarget, positionMs: Long, durationMs: Long): JsonObject? {
        val title = WebsiteTitle.of(target) ?: return null
        return fetchWebsitePlaybackBody(
            WatchProgress(
                contentId = title.imdbId,
                contentType = if (title.isEpisode) "series" else "movie",
                name = target.name,
                poster = target.poster,
                backdrop = null,
                logo = null,
                videoId = target.videoId,
                season = title.season,
                episode = title.episode,
                episodeTitle = null,
                position = positionMs,
                duration = durationMs,
                lastWatched = 0L
            )
        )
    }
}

/**
 * The website's watch state in the desktop's Continue Watching shapes, from the TV's
 * [FetchWebsiteProgressProjection], and the merge that adds it to what is held here.
 */
object FetchWebsiteProgress {
    /** The website's resume points as Continue Watching entries (by the second when it has them). */
    fun entries(projection: FetchWebsiteProgressProjection): List<ProgressEntry> = projection.progress.map { p ->
        val exact = p.duration > 0L && p.position > 0L
        ProgressEntry(
            contentId = p.contentId,
            contentType = p.contentType,
            name = p.name,
            poster = p.poster ?: metahub("poster/small", p.contentId),
            backdrop = metahub("background/medium", p.contentId),
            logo = metahub("logo/medium", p.contentId),
            videoId = p.videoId,
            season = p.season,
            episode = p.episode,
            positionMs = if (exact) p.position else 0L,
            durationMs = if (exact) p.duration else 0L,
            lastWatched = p.lastWatched,
            remotePercent = if (exact) null else p.progressPercent,
            source = FETCH_WEBSITE_PROVIDER_STORAGE_ID
        )
    }

    /** Everything watched there, as Cinemeta ids: "tt123" for a film, "tt123:2:5" for an episode. */
    fun watchedIds(projection: FetchWebsiteProgressProjection): Set<String> =
        projection.watchedMovieIds + projection.watchedShowEpisodes.flatMap { (show, eps) -> eps.map { (s, e) -> "$show:$s:$e" } }

    /**
     * Next Up from the website: each show's latest watched episode (the TV's Next Up seeds), as a
     * caught-up show that the episode-list check turns into Next Up for the episode after it.
     */
    fun nextUp(projection: FetchWebsiteProgressProjection, preferFurthest: Boolean): List<CaughtUpShow> =
        projection.nextUp(preferFurthest).map { seed ->
            CaughtUpShow(
                contentId = seed.contentId,
                contentType = "series",
                name = seed.name,
                poster = seed.poster ?: metahub("poster/small", seed.contentId),
                backdrop = metahub("background/medium", seed.contentId),
                logo = metahub("logo/medium", seed.contentId),
                lastVideoId = seed.videoId,
                at = seed.lastWatched
            )
        }

    /**
     * Adds the website's [remote] resume points, [watchedIds] and [nextUp] shows to [file]: newer
     * activity there wins over an entry here, as for Trakt and Simkl, keeping the artwork held
     * here. A show watched there since its entry here becomes caught up, then Next Up.
     */
    fun merge(file: ProgressFile, remote: List<ProgressEntry>, watchedIds: Set<String>, nextUp: List<CaughtUpShow>): ProgressFile {
        val local = file.entries.associateBy { it.contentId }
        val dressed = remote.map { incoming ->
            val held = local[incoming.contentId] ?: return@map incoming
            incoming.copy(
                poster = held.poster ?: incoming.poster,
                backdrop = held.backdrop ?: incoming.backdrop,
                logo = held.logo ?: incoming.logo,
                episodeTitle = held.episodeTitle.takeIf { held.videoId == incoming.videoId } ?: incoming.episodeTitle
            )
        }
        var merged = WatchHistory.mergeRemote(file, dressed, watchedIds)
        nextUp.forEach { show ->
            val entry = merged.entries.firstOrNull { it.contentId == show.contentId }
            val caught = merged.caughtUp.firstOrNull { it.contentId == show.contentId }
            if (entry != null && entry.lastWatched >= show.at) return@forEach
            if (caught != null && caught.at >= show.at) return@forEach
            merged = merged.copy(
                entries = merged.entries.filter { it.contentId != show.contentId },
                caughtUp = merged.caughtUp.filter { it.contentId != show.contentId } + show
            )
        }
        return merged
    }

    private fun metahub(kind: String, imdb: String) = "https://images.metahub.space/$kind/$imdb/img"
}

/** The website's lists as the Library's tabs, from the TV's [FetchWebsiteLibraryProjection]. */
object FetchWebsiteLibrary {
    fun lists(snapshot: FetchWebsiteTrackingSnapshot?): List<LibraryList> {
        val projection = FetchWebsiteLibraryProjection(snapshot)
        val kinds = snapshot?.lists.orEmpty().associate { fetchWebsiteListKey(it.id) to it.kind }
        return projection.tabs.map { tab ->
            LibraryList(
                key = tab.key,
                provider = ListProvider.FETCH_WEBSITE,
                title = tab.title,
                items = projection.entries
                    .filter { tab.key in it.listKeys }
                    .sortedBy { it.listRanks[tab.key] ?: Int.MAX_VALUE }
                    .map(::toEntry),
                // The profile's watchlist and collection stay; its own lists are renamed and deleted here.
                editable = kinds[tab.key] == "custom",
                types = setOf("movie", "series")
            )
        }
    }

    private fun toEntry(entry: com.fetch.tv.domain.model.LibraryEntry) = LibraryEntry(
        id = entry.id,
        type = entry.type,
        name = entry.name,
        poster = entry.poster ?: "https://images.metahub.space/poster/small/${entry.id}/img",
        background = "https://images.metahub.space/background/medium/${entry.id}/img",
        releaseInfo = entry.releaseInfo,
        imdbRating = entry.imdbRating,
        genres = entry.genres,
        addedAt = entry.listedAt
    )
}

/** The edits the desktop makes to the held snapshot after a watched mark, which the next read confirms. */
internal fun FetchWebsiteTrackingSnapshot.withWatched(title: WebsiteTitle, name: String?, poster: String?, atMs: Long): FetchWebsiteTrackingSnapshot {
    val at = Instant.ofEpochMilli(atMs).toString()
    val state = if (title.season == null || title.episode == null) {
        watchState.copy(movies = watchState.movies.filterNot { it.imdbId == title.imdbId } + WebsiteWatchedMovie(title.imdbId, at, name, poster))
    } else {
        val previous = watchState.episodes.firstOrNull { it.imdbId == title.imdbId }
        watchState.copy(episodes = watchState.episodes.filterNot {
            it.imdbId == title.imdbId && it.season == title.season && it.episode == title.episode
        } + WebsiteWatchedEpisode(title.imdbId, title.season, title.episode, at, previous?.name ?: name, previous?.poster ?: poster))
    }
    // Watching a title clears its resume point, as on the website.
    return copy(watchState = state, playback = playback.filterNot { it.imdbId == title.imdbId && it.season == title.season && it.episode == title.episode })
}

internal fun FetchWebsiteTrackingSnapshot.withoutWatched(title: WebsiteTitle): FetchWebsiteTrackingSnapshot = copy(
    watchState = if (title.season == null || title.episode == null) {
        watchState.copy(movies = watchState.movies.filterNot { it.imdbId == title.imdbId })
    } else {
        watchState.copy(episodes = watchState.episodes.filterNot {
            it.imdbId == title.imdbId && it.season == title.season && it.episode == title.episode
        })
    }
)

/**
 * The website's watch state and lists for one profile ([slot]), as the TV's
 * `FetchWebsiteTrackingRepository` keeps them, and what this profile sends it.
 *
 * Read whole (a handful of requests) and kept in memory: on start, when the window gets focus and
 * every [POLL_INTERVAL_MS] while it has it, so a change made on the website arrives within a
 * minute. Writes from here land in the held snapshot straight away and are confirmed by the next
 * read; a read that raced a write is thrown away and tried again shortly. Nothing happens while
 * this computer isn't paired, or the slot has no website profile.
 */
class FetchWebsiteTracker(
    private val website: FetchWebsiteState?,
    private val slot: Int,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val conflictRetryMs: Long = CONFLICT_RETRY_MS
) {
    private var held: FetchWebsiteTrackingSnapshot? by mutableStateOf(null)

    /** A read is running. */
    var refreshing by mutableStateOf(false)
        private set

    private val refreshMutex = Mutex()
    private val mutationGeneration = AtomicLong()

    @Volatile
    private var lastFailureAtMs = 0L

    /** When each title's resume point was last sent, so a save a second doesn't become a request a second. */
    private val positionSentAt = ConcurrentHashMap<String, Long>()

    val paired: Boolean get() = website?.isPaired == true

    /** This profile's snapshot; null when not paired or not read yet. */
    val snapshot: FetchWebsiteTrackingSnapshot? get() = held?.takeIf { paired && it.slot == slot }

    private var progressCache: Pair<FetchWebsiteTrackingSnapshot?, FetchWebsiteProgressProjection> = null to FetchWebsiteProgressProjection.Empty
    private var libraryCache: Pair<FetchWebsiteTrackingSnapshot?, FetchWebsiteLibraryProjection> = null to FetchWebsiteLibraryProjection.Empty

    /** Continue Watching, watched titles and Next Up as the website has them. */
    val progress: FetchWebsiteProgressProjection
        get() {
            val current = snapshot
            if (progressCache.first !== current) progressCache = current to FetchWebsiteProgressProjection(current)
            return progressCache.second
        }

    val library: FetchWebsiteLibraryProjection
        get() {
            val current = snapshot
            if (libraryCache.first !== current) libraryCache = current to FetchWebsiteLibraryProjection(current)
            return libraryCache.second
        }

    /** The website's lists as Library tabs. */
    fun libraryLists(): List<LibraryList> = if (!paired) emptyList() else FetchWebsiteLibrary.lists(snapshot)

    /**
     * Reads the website again; true when a new snapshot came in. Without [force] the read is
     * skipped while the last one is younger than [POLL_INTERVAL_MS], and for a while after a failure.
     */
    suspend fun refresh(force: Boolean = false): Boolean {
        val web = website
        if (web == null || !web.isPaired) {
            held = null
            return false
        }
        return refreshMutex.withLock {
            val started = now()
            if (!force) {
                val current = snapshot
                if (current != null && started - current.fetchedAtMs in 0 until POLL_INTERVAL_MS) return@withLock false
                if (started - lastFailureAtMs in 0 until ERROR_RETRY_MS) return@withLock false
            }
            refreshing = true
            try {
                val generation = mutationGeneration.get()
                val read = read(web)
                if (mutationGeneration.get() != generation) {
                    // A write from here landed while reading; what was read may predate it.
                    held = held?.copy(fetchedAtMs = 0L)
                    scope.launch {
                        delay(conflictRetryMs)
                        refresh()
                    }
                    return@withLock false
                }
                held = read
                lastFailureAtMs = 0L
                read != null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // What was read last stays until the website answers again.
                lastFailureAtMs = started
                false
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun read(web: FetchWebsiteState): FetchWebsiteTrackingSnapshot? {
        val profile = web.profileFor(slot) ?: return null
        val token = web.profileToken(profile.id)
        val client = web.client
        return try {
            coroutineScope {
                val playback = async { client.playback(profile.id, token) }
                val watchState = async { client.watchState(profile.id, token) }
                val lists = client.lists(profile.id, token)
                val items = lists.map { list -> async { list.id to client.listItems(profile.id, token, list.id) } }
                FetchWebsiteTrackingSnapshot(
                    slot = slot,
                    websiteProfileId = profile.id,
                    playback = playback.await(),
                    watchState = watchState.await(),
                    lists = lists,
                    listItems = items.awaitAll().toMap(),
                    fetchedAtMs = now()
                )
            }
        } catch (e: FetchWebsiteException) {
            // A PIN-protected profile nobody has unlocked here: there is nothing to show.
            if (!e.isProfileLocked) throw e
            FetchWebsiteTrackingSnapshot(slot, profile.id, fetchedAtMs = now(), locked = true)
        }
    }

    /** After a write: what is held is changed by [transform] now and read back on the next refresh. */
    private fun mutate(transform: (FetchWebsiteTrackingSnapshot) -> FetchWebsiteTrackingSnapshot) {
        mutationGeneration.incrementAndGet()
        held = held?.let(transform)?.copy(fetchedAtMs = 0L)
    }

    /** Something changed on the website because of this computer; read it again now. */
    private fun invalidate() {
        mutate { it }
        scope.launch { refresh() }
    }

    /** Runs [block] as this profile's website profile; null when there is none to reach. */
    private suspend fun <T> asProfile(block: suspend FetchWebsiteClient.(profileId: String, profileToken: String?) -> T): T? {
        val web = website?.takeIf { it.isPaired } ?: return null
        val profile = web.profileFor(slot) ?: return null
        return web.client.block(profile.id, web.profileToken(profile.id))
    }

    // --- Watch state ---

    /** Tells the website what is playing; a pause or stop moves its resume point or records the watch. */
    suspend fun scrobble(target: PlaybackTarget, action: ScrobbleAction, percent: Float) {
        val title = WebsiteTitle.of(target) ?: return
        quietly { asProfile { id, token -> scrobble(id, token, action.name.lowercase(), FetchWebsiteBodies.scrobble(title, percent)) } }
        if (action != ScrobbleAction.START) invalidate()
    }

    /** Marks [target] (a film or one episode) watched or unwatched on the website. */
    suspend fun setWatched(target: PlaybackTarget, watched: Boolean) {
        val title = WebsiteTitle.of(target) ?: return
        if (watched) {
            val at = now()
            quietly { asProfile { id, token -> recordWatch(id, token, FetchWebsiteBodies.watch(title, at)) } } ?: return
            mutate { it.withWatched(title, target.name, target.poster, at) }
        } else {
            // The website unmarks episodes of numbered seasons only.
            if (title.season == 0) return
            quietly {
                asProfile { id, token ->
                    if (title.season != null && title.episode != null) unwatchEpisode(id, token, title.imdbId, title.season, title.episode)
                    else unwatchMovie(id, token, title.imdbId)
                }
            } ?: return
            mutate { it.withoutWatched(title) }
        }
    }

    /**
     * Moves the website's resume point for [target] to [positionMs], at most every
     * [POSITION_INTERVAL_MS] per title, so a computer switched off mid-film resumes where it was.
     * A title that isn't started, or is nearly done, is left to the scrobbles.
     */
    fun savePosition(target: PlaybackTarget, positionMs: Long, durationMs: Long) {
        if (!paired || durationMs <= 0L) return
        val fraction = positionMs.toFloat() / durationMs
        if (fraction < WatchProgress.STARTED_THRESHOLD || fraction * 100 >= FETCH_WEBSITE_COMPLETION_PERCENT) return
        val body = FetchWebsiteBodies.playback(target, positionMs, durationMs) ?: return
        val at = now()
        val last = positionSentAt[target.videoId]
        if (last != null && at - last in 0 until POSITION_INTERVAL_MS) return
        positionSentAt[target.videoId] = at
        scope.launch { quietly { asProfile { id, token -> putPlayback(id, token, body) } } }
    }

    /** Drops the website's resume points for [contentId] (every episode's without [season] and [episode]). */
    suspend fun forgetPosition(contentId: String, season: Int?, episode: Int?) {
        if (!paired || !contentId.startsWith("tt")) return
        // A point saved since the last read has no id here yet: read first.
        refresh(force = true)
        val ids = snapshot?.playbackIdsFor(contentId, season, episode).orEmpty()
        if (ids.isEmpty()) return
        quietly { asProfile { id, token -> ids.forEach { deletePlayback(id, token, it) } } } ?: return
        mutate { s -> s.copy(playback = s.playback.filterNot { it.id in ids }) }
    }

    // --- Lists ---

    /** Puts [preview] on or off the website list [key]. Throws when the website can't take it. */
    suspend fun setMember(key: String, preview: MetaPreview, on: Boolean) {
        val listId = fetchWebsiteListId(key) ?: return
        val imdb = (preview.imdbId ?: preview.id).substringBefore(':').takeIf { it.startsWith("tt") }
            ?: throw IllegalStateException("The Fetch website keeps lists of titles with an IMDb id only")
        val type = fetchWebsiteItemType(preview.apiType)
        if ((key in library.membership(imdb, type)) == on) return
        asProfile { id, token ->
            if (on) addListItem(id, token, listId, type, imdb, preview.name) else removeListItem(id, token, listId, type, imdb)
        } ?: throw IllegalStateException(SIGN_IN_FIRST)
        val input = LibraryEntryInput(itemId = imdb, itemType = type, title = preview.name, year = yearOf(preview.releaseInfo), imdbId = imdb, poster = preview.poster)
        mutate { it.withListMembership(listOfNotNull(listId.takeIf { on }), listOfNotNull(listId.takeIf { !on }), type, imdb, input) }
        scope.launch { refresh() }
    }

    /** Makes a list of the profile's own on the website. */
    suspend fun createList(name: String) {
        val created = asProfile { id, token -> createList(id, token, name.trim()) } ?: throw IllegalStateException(SIGN_IN_FIRST)
        mutate { it.copy(lists = it.lists + created, listItems = it.listItems + (created.id to emptyList())) }
        refresh(force = true)
    }

    suspend fun renameList(key: String, name: String) {
        val listId = fetchWebsiteListId(key) ?: throw IllegalArgumentException("Not a Fetch website list")
        asProfile { id, token -> renameList(id, token, listId, name.trim()) } ?: throw IllegalStateException(SIGN_IN_FIRST)
        mutate { s -> s.copy(lists = s.lists.map { if (it.id == listId) it.copy(name = name.trim()) else it }) }
        refresh(force = true)
    }

    suspend fun deleteList(key: String) {
        val listId = fetchWebsiteListId(key) ?: throw IllegalArgumentException("Not a Fetch website list")
        try {
            asProfile { id, token -> deleteList(id, token, listId) } ?: throw IllegalStateException(SIGN_IN_FIRST)
        } catch (e: FetchWebsiteException) {
            // The profile's watchlist and collection can't be deleted; the website says why.
            throw IllegalStateException(e.message, e)
        }
        mutate { s -> s.copy(lists = s.lists.filterNot { it.id == listId }, listItems = s.listItems - listId) }
        refresh(force = true)
    }

    /** A failed scrobble or watched mark isn't worth interrupting anything for, as on TV; null when it failed. */
    private suspend fun <T> quietly(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val POLL_INTERVAL_MS = 60_000L
        private const val ERROR_RETRY_MS = 30_000L
        private const val CONFLICT_RETRY_MS = 2_000L
        const val POSITION_INTERVAL_MS = 30_000L
        private const val SIGN_IN_FIRST = "Sign in to the Fetch website first"

        private fun yearOf(releaseInfo: String?): Int? = releaseInfo?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
    }
}
