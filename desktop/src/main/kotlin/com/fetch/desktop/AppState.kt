package com.fetch.desktop

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AddonClient
import com.fetch.desktop.data.AddonStore
import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.addonsDir
import com.fetch.desktop.data.profileDir
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.LibraryFile
import com.fetch.desktop.data.PlaybackTarget
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.ProgressFile
import com.fetch.desktop.data.WatchHistory
import com.fetch.desktop.data.episodeTag
import com.fetch.desktop.data.libraryFile
import com.fetch.desktop.data.progressFile
import com.fetch.desktop.data.settingsFile
import com.fetch.desktop.data.toPreview
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.desktop.data.SubtitleMode
import com.fetch.desktop.player.MpvArgs
import com.fetch.desktop.player.MpvProgress
import com.fetch.desktop.tracking.ScrobbleAction
import com.fetch.desktop.tracking.TrackingStore
import com.fetch.tv.core.addon.AddonUrls
import com.fetch.tv.core.addon.needsConfiguration
import com.fetch.tv.core.streams.ranking.RankedStream
import com.fetch.tv.core.streams.ranking.StreamRanker
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.Video
import com.fetch.tv.domain.model.supportsExtra
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The Settings pages, in rail order under their group headings (the TV app's groups). */
enum class SettingsPage(val label: String, val group: String, val parent: SettingsPage? = null) {
    PROFILES("Profiles", "People"),
    PLAYER("Player", "Playback"),
    STREAM_SELECTION("Stream Selection", "Playback"),
    UP_NEXT("Up Next", "Playback"),
    AUDIO("Audio", "Playback"),
    SUBTITLES("Subtitles", "Playback"),
    CONTENT_DISCOVERY("Content & Discovery", "Sources"),
    ADDONS("Addons", "Sources", parent = CONTENT_DISCOVERY),
    HOME_CATALOGS("Home catalogs", "Sources", parent = CONTENT_DISCOVERY),
    APPEARANCE("Appearance", "Home"),
    LAYOUT("Layout", "Home"),
    TRACKING("Tracking", "Accounts"),
    ABOUT("About", "System");

    /** The rail entry this page belongs under. */
    val railPage: SettingsPage get() = parent ?: this
}

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Discover : Screen
    data object Library : Screen
    data class Settings(val page: SettingsPage = SettingsPage.PLAYER) : Screen
    /**
     * [autoPlay] starts the best match as soon as the sources are in (Play on the home hero or a
     * Continue Watching card). [videoId] picks the episode, for Continue Watching.
     */
    data class Detail(val preview: MetaPreview, val autoPlay: Boolean = false, val videoId: String? = null) : Screen
}

/** A home row: one addon catalog and what it returned. */
data class CatalogRow(
    val addon: Addon,
    val catalog: CatalogDescriptor,
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
) {
    val key: String get() = "${addon.baseUrl}|${catalog.apiType}|${catalog.id}"

    /** The row's heading, as the TV app writes it: "Popular - Movies", or just "Popular". */
    fun title(showType: Boolean): String {
        val name = catalog.name.replaceFirstChar { it.uppercase() }
        return if (showType) "$name - ${typeLabel(catalog.apiType)}" else name
    }

    val title: String get() = title(showType = true)
}

fun typeLabel(type: String): String = when (type.lowercase()) {
    "movie" -> "Movies"
    "series" -> "Series"
    "channel" -> "Channels"
    "tv" -> "TV"
    else -> type.replaceFirstChar { it.uppercase() }
}

/** The sources for one title or episode, ranked by the TV app's StreamRanker. */
data class SourcesState(
    val preview: MetaPreview,
    val video: Video?,
    val videoId: String,
    val label: String,
    /** "S2 E6" for an episode, null for a movie. */
    val episodeTag: String? = null,
    val loading: Boolean = true,
    /** Every stream the addons sent, kept so a change to the rules can rank them again. */
    val streams: List<Stream> = emptyList(),
    val ranked: List<RankedStream> = emptyList(),
    val failedAddons: List<String> = emptyList()
) {
    /** What auto-play would pick: the best source the rules didn't rule out. */
    val best: RankedStream? get() = ranked.firstOrNull { it.isEligible }
}

/**
 * The desktop app's state. Network work runs on [Dispatchers.IO]; state is only written from
 * [scope], which is the window's composition scope.
 */
class AppState(
    private val scope: CoroutineScope,
    val profiles: ProfilesState = ProfilesState(),
    /** The profile this state belongs to; switching profiles makes a new [AppState]. */
    val profile: DesktopProfile = profiles.active ?: profiles.profiles.first(),
    private val client: AddonClient = AddonClient.create(),
    private val store: AddonStore = AddonStore(File(addonsDir(profile), "addons.json")),
    private val settingsStore: JsonFile<DesktopSettings> = settingsFile(profileDir(profile)),
    private val libraryStore: JsonFile<LibraryFile> = libraryFile(profileDir(profile)),
    private val progressStore: JsonFile<ProgressFile> = progressFile(profileDir(profile))
) {
    var screen: Screen by mutableStateOf(Screen.Home)
        private set
    private val backStack = ArrayDeque<Screen>()

    var settings: DesktopSettings by mutableStateOf(settingsStore.load())
        private set

    val addons = mutableStateListOf<Addon>()
    var addonsLoading by mutableStateOf(true)
        private set
    /** Saved addon URLs that didn't answer at startup, with why. They stay saved. */
    val unreachableAddons = mutableStateListOf<Pair<String, String>>()

    /** Every home-able catalog, in addon order; [homeRows] applies the pins and hidden ones. */
    val rows = mutableStateListOf<CatalogRow>()
    /** Home's scroll position, kept here so coming back from a title lands where you left. */
    val homeListState = LazyListState()

    val library = mutableStateListOf<LibraryEntry>().apply { addAll(libraryStore.load().items) }
    var progress: ProgressFile by mutableStateOf(progressStore.load())
        private set

    var detailMeta: Meta? by mutableStateOf(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var sources: SourcesState? by mutableStateOf(null)
        private set
    private var sourcesJob: Job? = null
    private var autoPlayPending = false

    /** The sources list sliding in over the detail page. */
    var sourcesPanelOpen by mutableStateOf(false)

    var searchQuery by mutableStateOf("")
        private set
    val searchRows = mutableStateListOf<CatalogRow>()
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    val discover = DiscoverState(scope, client) { key -> updateSettings { it.copy(discoverCatalog = key) } }

    /** A one-line message for the snackbar, cleared once shown. */
    var message: String? by mutableStateOf(null)

    /** Trakt and Simkl for this profile. */
    val tracking = TrackingState(scope, TrackingStore(File(profileDir(profile), "tracking.json")), onMessage = { message = it })

    /** Watchlists from the connected accounts, shown as Library sources. */
    var remoteLists: Map<TrackingService, List<LibraryEntry>> by mutableStateOf(emptyMap())
        private set

    fun start() {
        scope.launch { reloadAddons() }
        scope.launch { syncTracking() }
    }

    /** Pulls Continue Watching, watched history and watchlists from Trakt and Simkl. */
    fun syncTracking() {
        scope.launch {
            val snapshot = tracking.sync() ?: return@launch
            saveProgress(WatchHistory.mergeRemote(progress, snapshot.playback, snapshot.watchedIds))
            remoteLists = snapshot.watchlists
        }
    }

    // --- Navigation ---

    fun navigate(to: Screen) {
        if (to == screen) return
        // Coming back to a page never re-triggers its auto-play.
        backStack.addLast(screen.let { if (it is Screen.Detail) it.copy(autoPlay = false) else it })
        show(to)
    }

    fun back() {
        if (sourcesPanelOpen) {
            sourcesPanelOpen = false
            return
        }
        val current = screen
        if (backStack.isEmpty() && current is Screen.Settings && current.page.parent != null) {
            show(Screen.Settings(current.page.parent))
            return
        }
        backStack.removeLastOrNull()?.let(::show)
    }

    val canGoBack: Boolean
        get() = sourcesPanelOpen || backStack.isNotEmpty() ||
            (screen as? Screen.Settings)?.page?.parent != null

    fun openSection(section: Screen) {
        backStack.clear()
        show(section)
    }

    private fun show(to: Screen) {
        screen = to
        sourcesPanelOpen = false
        if (to is Screen.Detail) {
            autoPlayPending = to.autoPlay
            loadDetail(to.preview, to.videoId)
        }
    }

    // --- Settings ---

    fun updateSettings(change: (DesktopSettings) -> DesktopSettings) {
        val before = settings
        val after = change(before)
        if (after == before) return
        settings = after
        try {
            settingsStore.save(after)
        } catch (e: Exception) {
            message = "Couldn't save settings: ${e.message}"
        }
        if (after.rankingRules != before.rankingRules) rerankSources()
    }

    // --- Addons ---

    private suspend fun reloadAddons() {
        addonsLoading = true
        val urls = store.load()
        val results = withContext(Dispatchers.IO) {
            urls.map { url ->
                async {
                    url to try {
                        Result.success(client.fetchAddon(url))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        addons.clear()
        unreachableAddons.clear()
        results.forEach { (url, result) ->
            result.fold(
                onSuccess = { addons.add(it) },
                onFailure = { unreachableAddons.add(url to (it.message ?: "Unreachable")) }
            )
        }
        addonsLoading = false
        addonsChanged()
    }

    fun refreshAddons() {
        scope.launch { reloadAddons() }
    }

    fun installAddon(input: String) {
        scope.launch {
            val addon = try {
                withContext(Dispatchers.IO) { client.fetchAddon(input) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Couldn't install that addon"
                return@launch
            }
            if (addon.needsConfiguration) {
                message = "${addon.displayName} must be configured on its own page first, then install the link it gives you."
                return@launch
            }
            val key = AddonUrls.split(addon.baseUrl).let { (p, q) -> p + q }
            if (addons.any { AddonUrls.split(it.baseUrl).let { (p, q) -> p + q } == key }) {
                message = "${addon.displayName} is already installed"
                return@launch
            }
            addons.add(addon)
            persist()
            message = "Installed ${addon.displayName}"
            addonsChanged()
        }
    }

    fun removeAddon(addon: Addon) {
        addons.remove(addon)
        persist()
        rows.removeAll { it.addon.baseUrl == addon.baseUrl }
        discover.setAddons(addons.toList(), settings.discoverCatalog)
    }

    fun forgetUnreachable(url: String) {
        unreachableAddons.removeAll { it.first == url }
        persist()
    }

    fun moveAddon(addon: Addon, delta: Int) {
        val from = addons.indexOf(addon)
        val to = from + delta
        if (from < 0 || to !in addons.indices) return
        addons.removeAt(from)
        addons.add(to, addon)
        persist()
        addonsChanged()
    }

    private fun persist() {
        // Unreachable addons stay saved so a network blip at startup doesn't uninstall them.
        val urls = addons.map { it.baseUrl } + unreachableAddons.map { it.first }
        try {
            store.save(urls)
        } catch (e: Exception) {
            message = "Couldn't save the addon list: ${e.message}"
        }
    }

    private fun addonsChanged() {
        reloadRows()
        discover.setAddons(addons.toList(), settings.discoverCatalog)
    }

    // --- Home ---

    /** The rows Home shows: pinned catalogs first in pin order, then the rest, minus disabled ones. */
    val homeRows: List<CatalogRow>
        get() {
            val pinned = settings.pinnedCatalogs
            val disabled = settings.disabledCatalogs.toSet()
            val visible = rows.filter { it.key !in disabled }
            return visible.filter { it.key in pinned }.sortedBy { pinned.indexOf(it.key) } +
                visible.filter { it.key !in pinned }
        }

    fun setCatalogPinned(key: String, pinned: Boolean) = updateSettings { s ->
        s.copy(pinnedCatalogs = if (pinned) (s.pinnedCatalogs - key) + key else s.pinnedCatalogs - key)
    }

    fun setCatalogEnabled(key: String, enabled: Boolean) = updateSettings { s ->
        s.copy(disabledCatalogs = if (enabled) s.disabledCatalogs - key else (s.disabledCatalogs - key) + key)
    }

    private fun reloadRows() {
        val wanted = addons.flatMap { addon ->
            addon.catalogs
                // Catalogs that need an extra (search, genre) can't be shown as a plain row.
                .filter { catalog -> catalog.extra.none { it.isRequired } && catalog.extraRequired.isEmpty() }
                .map { CatalogRow(addon, it) }
        }
        rows.clear()
        rows.addAll(wanted)
        wanted.forEach { row ->
            scope.launch {
                val updated = try {
                    row.copy(items = withContext(Dispatchers.IO) { client.catalog(row.addon, row.catalog) }, loading = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    row.copy(loading = false, error = e.message ?: "Couldn't load")
                }
                val index = rows.indexOfFirst { it.key == row.key }
                if (index >= 0) rows[index] = updated
            }
        }
    }

    // --- Continue Watching ---

    val continueWatching: List<ProgressEntry> get() = WatchHistory.continueWatching(progress)

    /** The saved position for this title, when it is in progress or has a next episode waiting. */
    fun progressFor(contentId: String): ProgressEntry? =
        progress.entries.firstOrNull { it.contentId == contentId && !it.hidden }

    fun resumeEntry(videoId: String): ProgressEntry? =
        progress.entries.firstOrNull { it.videoId == videoId && !it.upNext && WatchHistory.resumeStart(progress, videoId) != null }

    fun openContinueWatching(entry: ProgressEntry, play: Boolean) {
        navigate(Screen.Detail(entry.toPreview(), autoPlay = play, videoId = entry.videoId))
    }

    fun removeFromContinueWatching(entry: ProgressEntry) {
        saveProgress(WatchHistory.hide(progress, entry.contentId))
        message = "Removed ${entry.name} from Continue Watching"
    }

    fun startFromBeginning(entry: ProgressEntry) {
        saveProgress(WatchHistory.clearPosition(progress, entry.contentId))
        navigate(Screen.Detail(entry.toPreview(), autoPlay = true, videoId = entry.videoId))
    }

    private fun saveProgress(file: ProgressFile) {
        progress = file
        try {
            progressStore.save(file)
        } catch (_: Exception) {
            // Losing one position update isn't worth interrupting playback for.
        }
    }

    // --- Library ---

    fun isInLibrary(id: String): Boolean = library.any { it.id == id }

    fun toggleLibrary(preview: MetaPreview, meta: Meta? = null) {
        if (isInLibrary(preview.id)) {
            library.removeAll { it.id == preview.id }
            tracking.setWatchlisted(preview, on = false)
            message = "Removed from library"
        } else {
            val enriched = if (meta == null) preview else preview.copy(
                name = meta.name,
                poster = meta.poster ?: preview.poster,
                background = meta.background ?: preview.background,
                logo = meta.logo ?: preview.logo,
                description = meta.description ?: preview.description,
                releaseInfo = meta.releaseInfo ?: preview.releaseInfo,
                imdbRating = meta.imdbRating ?: preview.imdbRating,
                genres = meta.genres.ifEmpty { preview.genres }
            )
            library.add(LibraryEntry.from(enriched, addedAt = System.currentTimeMillis()))
            tracking.setWatchlisted(enriched, on = true)
            message = if (tracking.connected.isEmpty()) "Added to library"
            else "Added to library and ${tracking.connected.joinToString(" and ") { it.watchlistName }}"
        }
        try {
            libraryStore.save(LibraryFile(library.toList()))
        } catch (e: Exception) {
            message = "Couldn't save the library: ${e.message}"
        }
    }

    // --- Search ---

    /** Catalogs that can answer a search: they take a "search" extra and need nothing else. */
    private fun searchableRows(): List<CatalogRow> = addons.flatMap { addon ->
        addon.catalogs
            .filter { catalog ->
                catalog.supportsExtra("search") &&
                    catalog.extra.none { it.isRequired && !it.name.equals("search", ignoreCase = true) } &&
                    catalog.extraRequired.none { !it.equals("search", ignoreCase = true) }
            }
            .map { CatalogRow(addon, it) }
    }

    /** Searches every searchable catalog once typing pauses for [SEARCH_DEBOUNCE_MS]. */
    fun search(query: String) {
        searchQuery = query
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            searchRows.clear()
            searching = false
            return
        }
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val wanted = searchableRows()
            searchRows.clear()
            searchRows.addAll(wanted)
            searching = true
            coroutineScope {
                wanted.forEach { row ->
                    launch {
                        val updated = try {
                            row.copy(
                                items = withContext(Dispatchers.IO) { client.search(row.addon, row.catalog, trimmed) },
                                loading = false
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            row.copy(loading = false, error = e.message ?: "Couldn't search")
                        }
                        val index = searchRows.indexOfFirst { it.key == row.key }
                        if (index >= 0) searchRows[index] = updated
                    }
                }
            }
            searching = false
        }
    }

    val hasSearchableCatalogs: Boolean get() = searchableRows().isNotEmpty()

    // --- Detail and sources ---

    private fun loadDetail(preview: MetaPreview, videoId: String?) {
        detailMeta = null
        sources = null
        sourcesJob?.cancel()
        detailLoading = true
        scope.launch {
            val meta = withContext(Dispatchers.IO) {
                client.meta(addons.toList(), preview.rawType, preview.id)
            }
            val current = screen
            if (current is Screen.Detail && current.preview.id == preview.id) {
                detailMeta = meta
                detailLoading = false
                // Look up sources straight away so the Play button can name what it will start:
                // the movie, or for a series the episode asked for, the one in Continue Watching,
                // or the first.
                val videos = meta?.videos.orEmpty()
                val first = firstEpisode(videos)
                val isSeries = preview.type == ContentType.SERIES || first != null
                val wantedId = videoId ?: progressFor(preview.id)?.videoId
                val episode = videos.firstOrNull { it.id == wantedId } ?: first
                when {
                    !isSeries -> loadSources(preview)
                    episode != null -> loadSources(preview, episode)
                }
            }
        }
    }

    fun loadSources(preview: MetaPreview, video: Video? = null) {
        val videoId = video?.id ?: preview.id
        val tag = video?.let { episodeTag(it.season, it.episode) }
        val label = video?.let { v -> listOfNotNull(tag, v.title.takeIf { it.isNotBlank() }).joinToString(" · ") }
            ?.let { "${preview.name} · $it" }
            ?: preview.name
        sourcesJob?.cancel()
        sources = SourcesState(preview = preview, video = video, videoId = videoId, label = label, episodeTag = tag)
        sourcesJob = scope.launch {
            val results = withContext(Dispatchers.IO) {
                client.streams(addons.toList(), preview.rawType, videoId)
            }
            val streams = results.flatMap { it.streams }
            val loaded = SourcesState(
                preview = preview,
                video = video,
                videoId = videoId,
                label = label,
                episodeTag = tag,
                loading = false,
                streams = streams,
                ranked = rank(streams),
                failedAddons = results.filter { it.error != null }.map { "${it.addon.displayName}: ${it.error}" }
            )
            sources = loaded
            if (autoPlayPending) {
                autoPlayPending = false
                val best = loaded.best
                if (best != null) play(best) else message = "No source to play automatically. Pick one below."
            }
        }
    }

    private suspend fun rank(streams: List<Stream>): List<RankedStream> = withContext(Dispatchers.Default) {
        StreamRanker.rank(streams, rules = settings.rankingRules, isPlayable = ExternalPlayer::canPlay)
    }

    private fun rerankSources() {
        val current = sources ?: return
        if (current.loading) return
        scope.launch {
            val ranked = rank(current.streams)
            if (sources?.videoId == current.videoId) sources = current.copy(ranked = ranked)
        }
    }

    /** Plays the best match now, or as soon as the sources are in. */
    fun playBest() {
        val current = sources ?: return
        if (current.loading) {
            autoPlayPending = true
            message = "Starting the best match as soon as your addons answer…"
            return
        }
        val best = current.best
        if (best != null) play(best) else sourcesPanelOpen = true
    }

    fun playTrailer(ytId: String, title: String) {
        when (val result = ExternalPlayer.playUrl("https://www.youtube.com/watch?v=$ytId", "$title (trailer)")) {
            is ExternalPlayer.Result.Started -> message = "Opening the trailer in ${result.player}"
            is ExternalPlayer.Result.Failed -> message = result.message
        }
    }

    /** Plays [ranked] for the title or episode the sources are for, resuming where it was left. */
    fun play(ranked: RankedStream) {
        autoPlayPending = false
        val context = sources ?: return
        val meta = detailMeta?.takeIf { it.id == context.preview.id }
        val target = PlaybackTarget(
            contentId = context.preview.id,
            contentType = context.preview.apiType,
            name = meta?.name ?: context.preview.name,
            poster = meta?.poster ?: context.preview.poster,
            backdrop = meta?.background ?: context.preview.background,
            logo = meta?.logo ?: context.preview.logo,
            video = context.video,
            episodes = meta?.videos.orEmpty()
        )
        launchPlayback(ranked.stream, context.label, target)
    }

    /**
     * Starts mpv with the player settings and any addon subtitles, resuming from the saved
     * position, then follows it for Continue Watching and Up Next.
     */
    private fun launchPlayback(stream: Stream, label: String, target: PlaybackTarget) {
        scope.launch {
            val player = settings.player
            val resume = WatchHistory.resumeStart(progress, target.videoId)
            val subtitles = if (player.forwardAddonSubtitles && player.subtitleMode != SubtitleMode.OFF) {
                addonSubtitles(target, player.subtitleLanguage ?: Locale.getDefault().language)
            } else {
                emptyList()
            }
            val options = MpvArgs.build(player, subtitles)
            when (val result = ExternalPlayer.play(stream, label, start = resume, options = options)) {
                is ExternalPlayer.Result.Started -> {
                    message = if (resume != null) "Resuming in ${result.player}" else "Opening in ${result.player}"
                    followProgress(result, target)
                }
                is ExternalPlayer.Result.Failed -> message = result.message
            }
        }
    }

    /** Up to three addon subtitles in [language], best first as the addons sent them. */
    private suspend fun addonSubtitles(target: PlaybackTarget, language: String): List<String> {
        val wanted = MpvArgs.languageCodes(language).split(',').toSet()
        return withContext(Dispatchers.IO) {
            client.subtitles(addons.toList(), target.contentType, target.videoId, timeoutMs = 4_000)
        }.filter { (lang, _) -> lang in wanted }.map { it.second }.distinct().take(3)
    }

    /** Saves mpv's position as it plays, for Continue Watching and resume, then handles Up Next. */
    private fun followProgress(result: ExternalPlayer.Result.Started, target: PlaybackTarget) {
        val process = result.process ?: return
        val socket = result.ipcSocket ?: return
        scope.launch {
            var scrobbled = false
            val ending = withContext(Dispatchers.IO) {
                MpvProgress.followToEnd(socket, process) { sample ->
                    scope.launch {
                        recordProgress(target, sample)
                        // Trakt and Simkl hear about it once it has really started.
                        if (!scrobbled) {
                            scrobbled = true
                            tracking.scrobble(target, ScrobbleAction.START, sample.percent())
                        }
                    }
                }
            }
            // Stop at 80% or more marks it watched on both services.
            ending.last?.let { if (scrobbled) tracking.scrobble(target, ScrobbleAction.STOP, it.percent()) }
            playbackEnded(target, ending)
        }
    }

    private fun MpvProgress.Sample.percent(): Float =
        if (durationSec > 0) (positionSec / durationSec * 100).toFloat().coerceIn(0f, 100f) else 0f

    private fun recordProgress(target: PlaybackTarget, sample: MpvProgress.Sample) {
        saveProgress(
            WatchHistory.record(
                progress,
                target,
                positionMs = (sample.positionSec * 1000).toLong(),
                durationMs = (sample.durationSec * 1000).toLong(),
                now = System.currentTimeMillis()
            )
        )
    }

    /** Up Next: an episode that played to its end starts the next one's best match. */
    private suspend fun playbackEnded(target: PlaybackTarget, ending: MpvProgress.Ending) {
        val video = target.video ?: return
        if (!ending.reachedEnd || !settings.player.autoplayNextEpisode) return
        val next = WatchHistory.nextEpisode(target.episodes, video, progress.watched.toSet()) ?: return
        val tag = episodeTag(next.season, next.episode)
        message = "Up Next: ${listOfNotNull(tag, next.title.takeIf { it.isNotBlank() }).joinToString(" · ")}"
        val streams = withContext(Dispatchers.IO) { client.streams(addons.toList(), target.contentType, next.id) }
            .flatMap { it.streams }
        val best = rank(streams).firstOrNull { it.isEligible }
        if (best == null) {
            message = "No source to play ${tag ?: "the next episode"} automatically"
            return
        }
        val label = listOfNotNull(target.name, tag, next.title.takeIf { it.isNotBlank() }).joinToString(" · ")
        launchPlayback(best.stream, label, target.copy(video = next))
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}

/** The first episode of the first real season (season 0 holds specials), or null for a movie. */
fun firstEpisode(videos: List<Video>): Video? {
    val episodes = videos.filter { it.season != null }
    if (episodes.isEmpty()) return null
    val season = episodes.mapNotNull { it.season }.filter { it > 0 }.minOrNull() ?: episodes.mapNotNull { it.season }.min()
    return episodes.filter { it.season == season }.minByOrNull { it.episode ?: Int.MAX_VALUE }
}
