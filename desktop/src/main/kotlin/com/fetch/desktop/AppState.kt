package com.fetch.desktop

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
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Addons : Screen
    data class Detail(val preview: MetaPreview) : Screen
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
}

/** The sources for one title or episode, ranked by the TV app's StreamRanker. */
data class SourcesState(
    val videoId: String,
    val label: String,
    val loading: Boolean = true,
    val ranked: List<RankedStream> = emptyList(),
    val failedAddons: List<String> = emptyList()
)

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

    var detailMeta: Meta? by mutableStateOf(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var sources: SourcesState? by mutableStateOf(null)
        private set
    private var sourcesJob: Job? = null

    /** A one-line message for the snackbar, cleared once shown. */
    var message: String? by mutableStateOf(null)

    fun start() {
        scope.launch { reloadAddons() }
    }

    fun navigate(to: Screen) {
        if (to == screen) return
        backStack.addLast(screen)
        show(to)
    }

    fun back() {
        backStack.removeLastOrNull()?.let(::show)
    }

    val canGoBack: Boolean get() = backStack.isNotEmpty()

    fun openSection(section: Screen) {
        backStack.clear()
        show(section)
    }

    private fun show(to: Screen) {
        screen = to
        if (to is Screen.Detail) loadDetail(to.preview)
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
            }
        }
    }

    fun loadSources(preview: MetaPreview, video: Video? = null) {
        val videoId = video?.id ?: preview.id
        val label = video?.let { v ->
            val number = if (v.season != null && v.episode != null) "S${v.season} E${v.episode} · " else ""
            "$number${v.title}"
        } ?: preview.name
        sourcesJob?.cancel()
        sources = SourcesState(videoId = videoId, label = label)
        sourcesJob = scope.launch {
            val results = withContext(Dispatchers.IO) {
                client.streams(addons.toList(), preview.rawType, videoId)
            }
            val ranked = withContext(Dispatchers.Default) {
                StreamRanker.rank(results.flatMap { it.streams }, isPlayable = ExternalPlayer::canPlay)
            }
            sources = SourcesState(
                videoId = videoId,
                label = label,
                loading = false,
                ranked = ranked,
                failedAddons = results.filter { it.error != null }.map { "${it.addon.displayName}: ${it.error}" }
            )
        }
    }

    fun play(ranked: RankedStream, title: String) {
        when (val result = ExternalPlayer.play(ranked.stream, title)) {
            is ExternalPlayer.Result.Started -> message = "Opening in ${result.player}"
            is ExternalPlayer.Result.Failed -> message = result.message
        }
    }
}
