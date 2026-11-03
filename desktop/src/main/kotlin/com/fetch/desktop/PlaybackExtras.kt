package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.PlaybackTarget
import com.fetch.desktop.data.episodeTag
import com.fetch.desktop.playback.ParentalGuideClient
import com.fetch.desktop.playback.ParentalWarning
import com.fetch.desktop.playback.PlaybackQueue
import com.fetch.desktop.playback.PostPlayRecommendation
import com.fetch.desktop.playback.PostPlayTiming
import com.fetch.desktop.playback.QueueItem
import com.fetch.desktop.tmdb.TmdbClient
import com.fetch.tv.core.streams.ranking.PreferNextTime
import com.fetch.tv.core.streams.ranking.PreferenceSuggestion
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The TV player's extras the desktop adds around playback: the playback queue, "Prefer this
 * next time", the content warnings at the start, the recommendation at the end of a movie, and
 * what the Up Next card says while it looks for the next episode. [AppState] drives it; the UI
 * is in `ui/PlaybackExtrasUi.kt`.
 */
class PlaybackExtras(private val scope: CoroutineScope, private val host: Host) {

    interface Host {
        val settings: DesktopSettings
        fun updateSettings(change: (DesktopSettings) -> DesktopSettings)
        fun message(text: String)
        /** TMDB when Settings › TMDB is on and More like this is allowed; null otherwise. */
        val tmdb: TmdbClient?
        val tmdbLanguage: String
        fun allowed(preview: MetaPreview): Boolean
        fun isWatched(id: String): Boolean
        /** The certificate the desktop resolved for [target] ("R", "TV-14"), if any. */
        fun rating(target: PlaybackTarget): String?
    }

    // --- Playback queue (the TV's EpisodeQueueStore) ---

    val queue: List<QueueItem> get() = host.settings.episodeQueue

    /** The player's Queue panel. */
    var queuePanelOpen by mutableStateOf(false)

    /** The last item removed from the panel, and where it was, for Undo. */
    var lastRemoved: Pair<QueueItem, Int>? by mutableStateOf(null)
        private set

    fun isQueued(videoId: String): Boolean = queue.any { it.videoId == videoId }

    fun upcoming(currentVideoId: String?): List<QueueItem> = PlaybackQueue.upcoming(queue, currentVideoId)

    private fun updateQueue(change: (List<QueueItem>) -> List<QueueItem>) =
        host.updateSettings { s -> change(s.episodeQueue).let { if (it == s.episodeQueue) s else s.copy(episodeQueue = it) } }

    fun add(item: QueueItem) {
        val already = isQueued(item.videoId)
        updateQueue { PlaybackQueue.added(it, item.stamped()) }
        host.message(if (already) "${item.label} is already in your queue" else "Added ${item.label} to your queue")
    }

    fun remove(videoId: String, undoable: Boolean = false) {
        val index = queue.indexOfFirst { it.videoId == videoId }
        if (index < 0) return
        if (undoable) lastRemoved = queue[index] to index
        updateQueue { PlaybackQueue.removed(it, videoId) }
    }

    fun undoRemove() {
        val (item, index) = lastRemoved ?: return
        lastRemoved = null
        updateQueue { PlaybackQueue.restored(it, item, index) }
    }

    fun move(videoId: String, up: Boolean) = updateQueue { PlaybackQueue.moved(it, videoId, up) }

    /** Next-episode autoplay puts the episode it would play at the front, as on TV. */
    fun seedNextEpisode(target: PlaybackTarget, next: Video) {
        if (target.contentType.equals("cloud", ignoreCase = true)) return
        updateQueue { PlaybackQueue.seeded(it, target.queueItem(next).stamped()) }
    }

    /** A queued video started, so it's no longer waiting. */
    fun markStarted(videoId: String) {
        if (isQueued(videoId)) updateQueue { PlaybackQueue.removed(it, videoId) }
    }

    private fun QueueItem.stamped() = if (addedAtMs > 0) this else copy(addedAtMs = System.currentTimeMillis())

    // --- Fetching panel: auto-play waiting for the addons ---

    /** Play was pressed and auto-play waits for the addons; the fetching panel shows. */
    var autoPlayWaiting by mutableStateOf(false)
    /** When auto-play picks from what's in (System.currentTimeMillis), for "Starting in 4 seconds"; null when it waits for every addon. */
    var autoPlayDeadlineMs: Long? by mutableStateOf(null)

    // --- Up Next card ---

    /** What the Up Next card says while autoplay finds and starts the next episode ("Finding a source…", "Starting in 3"). */
    var upNextStatus: String? by mutableStateOf(null)

    // --- Prefer this next time ---

    /** Offered a few seconds into playback after picking a source over Best match's. */
    var preferSuggestions: List<PreferenceSuggestion>? by mutableStateOf(null)
        private set
    private var preferJob: Job? = null

    /** Shows the waiting suggestions, if any, [PREFER_DELAY_MS] into playback, for [PREFER_VISIBLE_MS]. */
    fun offerPreferNextTime() {
        preferJob?.cancel()
        preferJob = scope.launch {
            delay(PREFER_DELAY_MS)
            val pending = PreferNextTime.take(PREFER_MAX_AGE_MS) ?: return@launch
            preferSuggestions = pending.suggestions
            delay(PREFER_VISIBLE_MS)
            preferSuggestions = null
        }
    }

    fun prefer(suggestion: PreferenceSuggestion) {
        preferSuggestions = null
        host.updateSettings { it.copy(rankingRules = suggestion.applyTo(it.rankingRules)) }
        host.message("Best match will prefer: ${suggestion.label}")
    }

    fun dismissPrefer() {
        preferSuggestions = null
        preferJob?.cancel()
    }

    // --- Content warnings at the start ---

    private val guideClient = ParentalGuideClient(okhttp3.OkHttpClient())

    /** The lines shown at the top left as playback starts; empty when there's nothing to show. */
    var guideLines by mutableStateOf<List<ParentalWarning>>(emptyList())
        private set
    var guideVisible by mutableStateOf(false)
        private set
    private var guideFor: String? = null
    private var guideJob: Job? = null

    /** Looks up and shows [target]'s rating and parental guide once per title or episode. */
    fun startGuide(target: PlaybackTarget) {
        if (!host.settings.autoPlay.parentalGuide || guideFor == target.videoId) return
        guideFor = target.videoId
        guideJob?.cancel()
        guideVisible = false
        guideJob = scope.launch {
            val imdb = target.contentId.substringBefore(':').takeIf { it.startsWith("tt") }
            val warnings = imdb?.let { guideClient.warnings(it) }.orEmpty()
            val lines = ParentalGuideClient.lines(host.rating(target), warnings)
            if (lines.isEmpty()) return@launch
            guideLines = lines
            // A moment for the picture to appear, as the TV waits for playback to start.
            delay(GUIDE_DELAY_MS)
            guideVisible = true
            delay(GUIDE_HOLD_MS)
            guideVisible = false
        }
    }

    fun hideGuide() {
        guideVisible = false
    }

    // --- Recommendation at the end of a movie ---

    /** The recommendation card's state for one movie. */
    data class PostPlay(
        val forVideoId: String,
        val movieTitle: String,
        val items: List<PostPlayRecommendation> = emptyList(),
        val index: Int = 0,
        val loading: Boolean = true,
        val visible: Boolean = false,
        /** "Back to the movie", or anything else that ended it. */
        val dismissed: Boolean = false,
        /** The movie ended in the built-in player (not mpv's window). */
        val inPlayer: Boolean = true,
        /** The countdown to the trailer after the movie ended. */
        val endCountdown: Int? = null,
        val trailerPlaying: Boolean = false,
        val trailerPlayed: Boolean = false
    ) {
        val current: PostPlayRecommendation? get() = items.getOrNull(index)
    }

    var postPlay: PostPlay? by mutableStateOf(null)
        private set
    private var postPlayJob: Job? = null
    private var countdownJob: Job? = null

    /** Looks the recommendations up ahead of time, near the end of [target] (a movie). */
    fun prefetchPostPlay(target: PlaybackTarget) {
        if (target.video != null || !host.settings.autoPlay.postPlayRecommendations) return
        if (postPlay?.forVideoId == target.videoId) return
        val tmdb = host.tmdb ?: return
        postPlay = PostPlay(forVideoId = target.videoId, movieTitle = target.name)
        postPlayJob?.cancel()
        postPlayJob = scope.launch {
            val language = host.tmdbLanguage
            val recommendations = runCatching {
                val id = tmdb.tmdbId(target.contentId, isSeries = false) ?: return@runCatching emptyList()
                tmdb.recommendations(id, isSeries = false, language = language)
            }.getOrDefault(emptyList())
            val candidates = PostPlayTiming.candidates(
                recommendations,
                currentIds = setOf(target.contentId),
                watched = { host.isWatched(it.id) || it.imdbId?.let(host::isWatched) == true },
                allowed = host::allowed,
                hideUnreleased = host.settings.hideUnreleased
            )
            val items = candidates.map { candidate -> recommendationFor(tmdb, candidate, language) }
            postPlay = postPlay?.takeIf { it.forVideoId == target.videoId }?.copy(items = items, loading = false)
        }
    }

    private suspend fun recommendationFor(tmdb: TmdbClient, preview: MetaPreview, language: String): PostPlayRecommendation {
        val series = preview.type == ContentType.SERIES
        val details = runCatching { tmdb.tmdbId(preview.id, series)?.let { tmdb.title(it, series, language) } }.getOrNull()
        val runtime = details?.runtimeMinutes?.takeIf { it > 0 }?.let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
        return PostPlayRecommendation(
            preview = preview,
            title = details?.title ?: preview.name,
            backdrop = details?.backdrop ?: preview.background,
            logo = details?.logo ?: preview.logo,
            description = details?.overview ?: preview.description,
            facts = listOfNotNull(
                details?.releaseInfo ?: preview.releaseInfo,
                runtime,
                (details?.genres ?: preview.genres).take(3).joinToString(", ").ifBlank { null }
            ),
            rating = details?.rating ?: preview.imdbRating,
            trailerYtId = details?.trailers?.firstOrNull() ?: preview.trailerYtIds.firstOrNull()
        )
    }

    /** The card shows (near the end, or after the movie ended in mpv's window). */
    fun showPostPlay(videoId: String, inPlayer: Boolean): Boolean {
        val current = postPlay?.takeIf { it.forVideoId == videoId && !it.dismissed && !it.loading && it.items.isNotEmpty() } ?: return false
        if (!current.visible || current.inPlayer != inPlayer) postPlay = current.copy(visible = true, inPlayer = inPlayer)
        return true
    }

    fun pagePostPlay(delta: Int) {
        val current = postPlay ?: return
        val index = (current.index + delta).coerceIn(0, (current.items.size - 1).coerceAtLeast(0))
        if (index != current.index) postPlay = current.copy(index = index, endCountdown = null)
    }

    /**
     * The movie ended. With the card up and a trailer, it plays after a five-second countdown,
     * as on TV ([playTrailer] starts it). True when the card holds the player open.
     */
    fun onMovieEnded(videoId: String, inPlayer: Boolean, playTrailer: (PostPlayRecommendation) -> Unit): Boolean {
        if (!showPostPlay(videoId, inPlayer)) return false
        val current = postPlay ?: return false
        val recommendation = current.current ?: return true
        if (!inPlayer || recommendation.trailerYtId == null || current.trailerPlayed) return true
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (seconds in PostPlayTiming.TRAILER_COUNTDOWN_SECONDS downTo 1) {
                postPlay = postPlay?.copy(endCountdown = seconds) ?: return@launch
                delay(1_000)
            }
            val now = postPlay ?: return@launch
            if (!now.visible || now.dismissed) return@launch
            postPlay = now.copy(endCountdown = null, trailerPlaying = true, trailerPlayed = true)
            now.current?.let(playTrailer)
        }
        return true
    }

    fun trailerStarted() {
        countdownJob?.cancel()
        postPlay = postPlay?.copy(endCountdown = null, trailerPlaying = true, trailerPlayed = true)
    }

    fun trailerEnded() {
        postPlay = postPlay?.copy(trailerPlaying = false)
    }

    /** "Back" on the card: the movie plays on, and the card won't come back for it. */
    fun dismissPostPlay() {
        countdownJob?.cancel()
        postPlay = postPlay?.copy(visible = false, dismissed = true, endCountdown = null)
    }

    /** The built-in player closed: its panels and cards go with it. */
    fun playerClosed() {
        queuePanelOpen = false
        guideVisible = false
        guideFor = null
        upNextStatus = null
        if (postPlay?.inPlayer == true) clearPostPlay()
    }

    /** Forgets the card (another title started, or the player closed). */
    fun clearPostPlay(exceptVideoId: String? = null) {
        if (exceptVideoId != null && postPlay?.forVideoId == exceptVideoId) return
        postPlayJob?.cancel()
        countdownJob?.cancel()
        postPlay = null
    }

    companion object {
        const val PREFER_DELAY_MS = 4_000L
        const val PREFER_VISIBLE_MS = 12_000L
        const val PREFER_MAX_AGE_MS = 3 * 60_000L
        const val GUIDE_DELAY_MS = 1_500L
        /** The TV's START_OVERLAY_HOLD_MS. */
        const val GUIDE_HOLD_MS = 5_000L
    }
}

/** "Group FLUX", "German audio", "1080p", "Files up to 8 GB", as the TV labels them. */
val PreferenceSuggestion.label: String
    get() = when (this) {
        is PreferenceSuggestion.ReleaseGroup -> "Group $group"
        is PreferenceSuggestion.Language -> "${language.label} audio"
        is PreferenceSuggestion.Resolution -> resolution.label
        is PreferenceSuggestion.SizeUpTo -> "Files up to ${gb.toInt()} GB"
    }

/** "Breaking Bad S2 E5" or the movie's name. */
val QueueItem.label: String get() = listOfNotNull(name, episodeTag(season, episode)).joinToString(" ")

/** [next] of this show as a queue item. */
fun PlaybackTarget.queueItem(next: Video? = video): QueueItem = QueueItem(
    contentId = contentId,
    contentType = contentType,
    videoId = next?.id ?: contentId,
    name = name,
    season = next?.season,
    episode = next?.episode,
    episodeTitle = next?.title?.takeIf { it.isNotBlank() },
    poster = next?.thumbnail ?: poster,
    backdrop = backdrop,
    logo = logo
)
