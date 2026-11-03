package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.PlaybackTarget
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.tracking.DeviceCode
import com.fetch.desktop.tracking.RemoteListItem
import com.fetch.desktop.tracking.RemotePlayback
import com.fetch.desktop.tracking.RemoteWatched
import com.fetch.desktop.tracking.ScrobbleAction
import com.fetch.desktop.tracking.SimklClient
import com.fetch.desktop.tracking.TokenPoll
import com.fetch.desktop.tracking.TrackedItem
import com.fetch.desktop.tracking.TrackingAccount
import com.fetch.desktop.tracking.TrackingAccounts
import com.fetch.desktop.tracking.TrackingAuthException
import com.fetch.desktop.tracking.TrackingCredentials
import com.fetch.desktop.tracking.TrackingStore
import com.fetch.desktop.tracking.TrackingTokens
import com.fetch.desktop.tracking.TraktClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class TrackingService(val label: String, val activateUrl: String, val watchlistName: String) {
    TRAKT("Trakt", "https://trakt.tv/activate", "Trakt watchlist"),
    SIMKL("Simkl", "https://simkl.com/pin", "Simkl Plan to Watch")
}

/** A sign-in waiting for the user to enter [code] on the service's site. */
data class PendingSignIn(val service: TrackingService, val code: DeviceCode, val status: String? = null)

/** What a sync brought back, for [AppState] to merge into Continue Watching and the Library. */
data class TrackingSnapshot(
    val playback: List<ProgressEntry>,
    val watchedIds: Set<String>,
    val watchlists: Map<TrackingService, List<LibraryEntry>>
)

/**
 * Trakt and Simkl for one profile: sign-in with a code (as on the TV app), scrobbling what mpv
 * plays, and pulling Continue Watching, watched history and the watchlist. Tokens are kept in
 * the profile's `tracking.json` and refreshed before they expire.
 */
class TrackingState(
    private val scope: CoroutineScope,
    private val store: TrackingStore,
    val credentials: TrackingCredentials = TrackingCredentials.load(),
    http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val trakt: TraktClient = TraktClient(http, credentials),
    private val simkl: SimklClient = SimklClient(http, credentials),
    private val onMessage: (String) -> Unit = {}
) {
    var accounts: TrackingAccounts by mutableStateOf(store.load())
        private set
    var pending: PendingSignIn? by mutableStateOf(null)
        private set
    var syncing by mutableStateOf(false)
        private set
    var lastSyncedAt: Long? by mutableStateOf(null)
        private set
    private var signInJob: Job? = null

    fun available(service: TrackingService): Boolean = when (service) {
        TrackingService.TRAKT -> credentials.traktAvailable
        TrackingService.SIMKL -> credentials.simklAvailable
    }

    fun account(service: TrackingService): TrackingAccount? = when (service) {
        TrackingService.TRAKT -> accounts.trakt
        TrackingService.SIMKL -> accounts.simkl
    }

    val connected: List<TrackingService> get() = TrackingService.entries.filter { account(it) != null }

    // --- Sign-in ---

    /** Asks the service for a code, shows it, and polls until the user enters it. */
    fun signIn(service: TrackingService, onSignedIn: () -> Unit) {
        signInJob?.cancel()
        signInJob = scope.launch {
            try {
                val code = when (service) {
                    TrackingService.TRAKT -> trakt.startDeviceAuth()
                    TrackingService.SIMKL -> simkl.startPinAuth()
                }
                pending = PendingSignIn(service, code)
                var interval = code.intervalSec.coerceAtLeast(1) * 1000L
                val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval)
                    val poll = when (service) {
                        TrackingService.TRAKT -> trakt.pollDeviceToken(code.deviceCode)
                        TrackingService.SIMKL -> simkl.pollPin(code.userCode)
                    }
                    when (poll) {
                        TokenPoll.Pending -> Unit
                        TokenPoll.SlowDown -> interval += 1000
                        TokenPoll.Expired -> return@launch fail("The code expired. Try again.")
                        TokenPoll.Denied -> return@launch fail("Sign-in was declined.")
                        is TokenPoll.Failed -> return@launch fail(poll.message)
                        is TokenPoll.Success -> {
                            val name = runCatching { userName(service, poll.tokens) }.getOrNull()
                            setAccount(service, TrackingAccount(poll.tokens, name))
                            pending = null
                            onMessage("Connected to ${service.label}${name?.let { " as $it" } ?: ""}")
                            onSignedIn()
                            return@launch
                        }
                    }
                }
                fail("The code expired. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Couldn't reach ${service.label}: ${e.message}")
            }
        }
    }

    private fun fail(message: String) {
        pending = null
        onMessage(message)
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        pending = null
    }

    fun signOut(service: TrackingService) {
        val tokens = account(service)?.tokens
        setAccount(service, null)
        if (service == TrackingService.TRAKT && tokens != null) scope.launch { runCatching { trakt.revoke(tokens) } }
        onMessage("Disconnected ${service.label}")
    }

    private suspend fun userName(service: TrackingService, tokens: TrackingTokens): String = when (service) {
        TrackingService.TRAKT -> trakt.userName(tokens)
        TrackingService.SIMKL -> simkl.userName(tokens)
    }

    private fun setAccount(service: TrackingService, account: TrackingAccount?) {
        accounts = when (service) {
            TrackingService.TRAKT -> accounts.copy(trakt = account)
            TrackingService.SIMKL -> accounts.copy(simkl = account)
        }
        runCatching { store.save(accounts) }
    }

    /**
     * Runs [block] with valid tokens: refreshes a Trakt token that expires soon, and once more on
     * a 401. If the service still refuses, the account is signed out. Null when not connected.
     */
    private suspend fun <T> withTokens(service: TrackingService, block: suspend (TrackingTokens) -> T): T? {
        val account = account(service) ?: return null
        var tokens = account.tokens
        if (service == TrackingService.TRAKT && tokens.expiresSoon() && tokens.refreshToken != null) {
            tokens = refreshTrakt(account) ?: return null
        }
        return try {
            block(tokens)
        } catch (e: TrackingAuthException) {
            val refreshed = if (service == TrackingService.TRAKT && tokens.refreshToken != null) refreshTrakt(account) else null
            if (refreshed == null) {
                setAccount(service, null)
                onMessage("${service.label} signed you out. Connect it again in Settings › Tracking.")
                null
            } else {
                block(refreshed)
            }
        }
    }

    /** Runs [block] with Trakt and a fresh token; null when Trakt isn't connected (or signed out). */
    suspend fun <T> withTrakt(block: suspend TraktClient.(TrackingTokens) -> T): T? = withTokens(TrackingService.TRAKT) { trakt.block(it) }

    /** Runs [block] with Simkl and its token; null when Simkl isn't connected. */
    suspend fun <T> withSimkl(block: suspend SimklClient.(TrackingTokens) -> T): T? = withTokens(TrackingService.SIMKL) { simkl.block(it) }

    private suspend fun refreshTrakt(account: TrackingAccount): TrackingTokens? = try {
        trakt.refresh(account.tokens).also { setAccount(TrackingService.TRAKT, account.copy(tokens = it)) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // --- Scrobbling ---

    /** Tells every connected service what is playing and how far along it is (0–100). */
    fun scrobble(target: PlaybackTarget, action: ScrobbleAction, percent: Float) {
        val item = trackedItem(target) ?: return
        connected.forEach { service ->
            scope.launch {
                runCatching {
                    withTokens(service) { tokens ->
                        when (service) {
                            TrackingService.TRAKT -> trakt.scrobble(tokens, action, item, percent)
                            TrackingService.SIMKL -> simkl.scrobble(tokens, action, item, percent)
                        }
                    }
                }
            }
        }
    }

    // --- Watched history ---

    /** Marks [target] (a movie or one episode) watched or unwatched on every connected service. */
    fun setWatched(target: PlaybackTarget, watched: Boolean) {
        val item = trackedItem(target) ?: return
        connected.forEach { service ->
            scope.launch {
                runCatching {
                    withTokens(service) { tokens ->
                        when (service) {
                            TrackingService.TRAKT -> if (watched) trakt.addToHistory(tokens, item) else trakt.removeFromHistory(tokens, item)
                            TrackingService.SIMKL -> if (watched) simkl.addToHistory(tokens, item) else simkl.removeFromHistory(tokens, item)
                        }
                    }
                }
            }
        }
    }

    // --- Watchlist ---

    /**
     * Adds to (or removes from) each connected watchlist. Simkl only ever gets additions: its
     * "remove" also wipes the title's watch history there, so Fetch leaves that to Simkl's site.
     */
    fun setWatchlisted(preview: com.fetch.tv.domain.model.MetaPreview, on: Boolean) {
        val imdb = preview.imdbId ?: preview.id.takeIf { it.startsWith("tt") } ?: return
        val item = TrackedItem(imdb, if (preview.apiType == "movie") TrackedItem.TYPE_MOVIE else TrackedItem.TYPE_SERIES, title = preview.name)
        connected.filter { on || it == TrackingService.TRAKT }.forEach { service ->
            scope.launch {
                runCatching {
                    withTokens(service) { tokens ->
                        when (service) {
                            TrackingService.TRAKT -> trakt.setWatchlisted(tokens, item, on)
                            TrackingService.SIMKL -> simkl.setWatchlisted(tokens, item, on)
                        }
                    }
                }
            }
        }
    }

    // --- Sync ---

    /** Pulls Continue Watching, watched history and watchlists from every connected service. */
    suspend fun sync(): TrackingSnapshot? {
        if (connected.isEmpty() || syncing) return null
        syncing = true
        try {
            val playback = mutableListOf<ProgressEntry>()
            val watched = mutableSetOf<String>()
            val lists = mutableMapOf<TrackingService, List<LibraryEntry>>()
            for (service in connected) {
                try {
                    withTokens(service) { tokens ->
                        val remotePlayback = when (service) {
                            TrackingService.TRAKT -> trakt.playback(tokens)
                            TrackingService.SIMKL -> simkl.playback(tokens)
                        }
                        playback += remotePlayback.mapNotNull { it.toProgressEntry(service) }
                        val remoteWatched = when (service) {
                            TrackingService.TRAKT -> trakt.watched(tokens)
                            TrackingService.SIMKL -> simkl.watched(tokens)
                        }
                        watched += remoteWatched.videoIds()
                        val list = when (service) {
                            TrackingService.TRAKT -> trakt.watchlist(tokens)
                            TrackingService.SIMKL -> simkl.watchlist(tokens)
                        }
                        lists[service] = list.map { it.toLibraryEntry() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onMessage("Couldn't sync ${service.label}: ${e.message}")
                }
            }
            lastSyncedAt = System.currentTimeMillis()
            // The newest entry per title wins when both services have one.
            val newest = playback.groupBy { it.contentId }.map { (_, entries) -> entries.maxBy { it.lastWatched } }
            return TrackingSnapshot(newest, watched, lists)
        } finally {
            syncing = false
        }
    }

    companion object {
        /** IMDb-keyed titles only: that's how Trakt and Simkl match. */
        fun trackedItem(target: PlaybackTarget): TrackedItem? {
            val imdb = target.contentId.takeIf { it.startsWith("tt") } ?: return null
            val video = target.video
            return if (target.contentType == "movie" || video == null) {
                TrackedItem(imdb, TrackedItem.TYPE_MOVIE, title = target.name)
            } else {
                TrackedItem(imdb, TrackedItem.TYPE_SERIES, season = video.season, episode = video.episode, title = target.name)
            }
        }

        /** Cinemeta's ids: "tt123" for a movie, "tt123:2:5" for an episode. */
        fun RemoteWatched.videoIds(): Set<String> =
            movies + episodes.flatMap { (show, eps) -> eps.map { (s, e) -> "$show:$s:$e" } }

        fun RemotePlayback.toProgressEntry(service: TrackingService): ProgressEntry? {
            val imdb = item.imdbId.takeIf { it.startsWith("tt") } ?: return null
            val isEpisode = item.isEpisode
            return ProgressEntry(
                contentId = imdb,
                contentType = if (isEpisode || item.type == TrackedItem.TYPE_SERIES) "series" else "movie",
                name = showTitle ?: item.title ?: imdb,
                poster = "https://images.metahub.space/poster/small/$imdb/img",
                backdrop = "https://images.metahub.space/background/medium/$imdb/img",
                logo = "https://images.metahub.space/logo/medium/$imdb/img",
                videoId = if (isEpisode) "$imdb:${item.season}:${item.episode}" else imdb,
                season = item.season.takeIf { isEpisode },
                episode = item.episode.takeIf { isEpisode },
                episodeTitle = episodeTitle,
                positionMs = 0,
                durationMs = 0,
                lastWatched = pausedAtEpochMs,
                remotePercent = progressPercent,
                source = service.name.lowercase()
            )
        }

        fun RemoteListItem.toLibraryEntry() = LibraryEntry(
            id = imdbId,
            type = if (type == TrackedItem.TYPE_MOVIE) "movie" else "series",
            name = title,
            poster = "https://images.metahub.space/poster/small/$imdbId/img",
            background = "https://images.metahub.space/background/medium/$imdbId/img",
            releaseInfo = year?.toString(),
            addedAt = addedAtEpochMs ?: 0L
        )
    }
}
