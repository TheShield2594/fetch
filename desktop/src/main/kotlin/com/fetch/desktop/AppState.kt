package com.fetch.desktop

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AddonClient
import com.fetch.desktop.data.AddonStore
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.tv.core.addon.AddonUrls
import com.fetch.tv.core.addon.needsConfiguration
import com.fetch.tv.core.streams.ranking.RankedStream
import com.fetch.tv.core.streams.ranking.StreamRanker
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
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

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Addons : Screen
    /** [autoPlay] starts the best match as soon as the sources are in (the home hero's Play). */
    data class Detail(val preview: MetaPreview, val autoPlay: Boolean = false) : Screen
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

    /** The row's heading, as the TV app writes it: "Popular - Movies". */
    val title: String
        get() = "${catalog.name.replaceFirstChar { it.uppercase() }} - ${typeLabel(catalog.apiType)}"
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
    val videoId: String,
    val label: String,
    /** "S2 E6" for an episode, null for a movie. */
    val episodeTag: String? = null,
    val loading: Boolean = true,
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
    private val client: AddonClient = AddonClient.create(),
    private val store: AddonStore = AddonStore()
) {
    var screen: Screen by mutableStateOf(Screen.Home)
        private set
    private val backStack = ArrayDeque<Screen>()

    val addons = mutableStateListOf<Addon>()
    var addonsLoading by mutableStateOf(true)
        private set
    /** Saved addon URLs that didn't answer at startup, with why. They stay saved. */
    val unreachableAddons = mutableStateListOf<Pair<String, String>>()

    val rows = mutableStateListOf<CatalogRow>()
    /** Home's scroll position, kept here so coming back from a title lands where you left. */
    val homeListState = LazyListState()

    var detailMeta: Meta? by mutableStateOf(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var sources: SourcesState? by mutableStateOf(null)
        private set
    private var sourcesJob: Job? = null
    private var autoPlayPending = false

    var searchQuery by mutableStateOf("")
        private set
    val searchRows = mutableStateListOf<CatalogRow>()
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    /** A one-line message for the snackbar, cleared once shown. */
    var message: String? by mutableStateOf(null)

    fun start() {
        scope.launch { reloadAddons() }
    }

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
        backStack.removeLastOrNull()?.let(::show)
    }

    val canGoBack: Boolean get() = sourcesPanelOpen || backStack.isNotEmpty()

    /** The sources list sliding in over the detail page. */
    var sourcesPanelOpen by mutableStateOf(false)

    fun openSection(section: Screen) {
        backStack.clear()
        show(section)
    }

    private fun show(to: Screen) {
        screen = to
        sourcesPanelOpen = false
        if (to is Screen.Detail) {
            autoPlayPending = to.autoPlay
            loadDetail(to.preview)
        }
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
        reloadRows()
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
            reloadRows()
        }
    }

    fun removeAddon(addon: Addon) {
        addons.remove(addon)
        persist()
        rows.removeAll { it.addon.baseUrl == addon.baseUrl }
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
        reloadRows()
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

    // --- Home ---

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

    private fun loadDetail(preview: MetaPreview) {
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
                // Look up sources straight away: a movie's, or a series' first episode, so the
                // Play button can name what it will start.
                val firstEpisode = meta?.videos?.let(::firstEpisode)
                val isSeries = preview.type == ContentType.SERIES || firstEpisode != null
                when {
                    !isSeries -> loadSources(preview)
                    firstEpisode != null -> loadSources(preview, firstEpisode)
                }
            }
        }
    }

    fun loadSources(preview: MetaPreview, video: Video? = null) {
        val videoId = video?.id ?: preview.id
        val episodeTag = video?.let { v ->
            if (v.season != null && v.episode != null) "S${v.season} E${v.episode}" else null
        }
        val label = video?.let { v -> listOfNotNull(episodeTag, v.title.takeIf { it.isNotBlank() }).joinToString(" · ") }
            ?.let { "${preview.name} · $it" }
            ?: preview.name
        sourcesJob?.cancel()
        sources = SourcesState(videoId = videoId, label = label, episodeTag = episodeTag)
        sourcesJob = scope.launch {
            val results = withContext(Dispatchers.IO) {
                client.streams(addons.toList(), preview.rawType, videoId)
            }
            val ranked = withContext(Dispatchers.Default) {
                StreamRanker.rank(results.flatMap { it.streams }, isPlayable = ExternalPlayer::canPlay)
            }
            val loaded = SourcesState(
                videoId = videoId,
                label = label,
                episodeTag = episodeTag,
                loading = false,
                ranked = ranked,
                failedAddons = results.filter { it.error != null }.map { "${it.addon.displayName}: ${it.error}" }
            )
            sources = loaded
            if (autoPlayPending) {
                autoPlayPending = false
                val best = loaded.best
                if (best != null) play(best, label) else message = "No source to play automatically. Pick one below."
            }
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
        if (best != null) play(best, current.label) else sourcesPanelOpen = true
    }

    fun playTrailer(ytId: String, title: String) {
        when (val result = ExternalPlayer.playUrl("https://www.youtube.com/watch?v=$ytId", "$title (trailer)")) {
            is ExternalPlayer.Result.Started -> message = "Opening the trailer in ${result.player}"
            is ExternalPlayer.Result.Failed -> message = result.message
        }
    }

    fun play(ranked: RankedStream, title: String) {
        autoPlayPending = false
        when (val result = ExternalPlayer.play(ranked.stream, title)) {
            is ExternalPlayer.Result.Started -> message = "Opening in ${result.player}"
            is ExternalPlayer.Result.Failed -> message = result.message
        }
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
