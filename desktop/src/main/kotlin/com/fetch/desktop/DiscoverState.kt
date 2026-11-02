package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AddonClient
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.skipStep
import com.fetch.tv.domain.model.supportsExtra
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A catalog Discover can browse, with the genres its addon offers for it. */
data class DiscoverCatalog(val addon: Addon, val catalog: CatalogDescriptor) {
    val key: String get() = "${addon.baseUrl}|${catalog.apiType}|${catalog.id}"
    val genres: List<String> =
        catalog.extra.firstOrNull { it.name.equals("genre", ignoreCase = true) }?.options.orEmpty()
    /** Some catalogs only answer with a genre picked. */
    val genreRequired: Boolean =
        catalog.extra.any { it.name.equals("genre", ignoreCase = true) && it.isRequired } ||
            catalog.extraRequired.any { it.equals("genre", ignoreCase = true) }
}

/**
 * Discover, as on the TV app: pick a type, one of that type's catalogs and optionally a genre,
 * and browse the results as a grid that loads the next page as you near the end.
 */
class DiscoverState(
    private val scope: CoroutineScope,
    private val client: AddonClient,
    private val onCatalogChosen: (String) -> Unit
) {
    var catalogs: List<DiscoverCatalog> by mutableStateOf(emptyList())
        private set
    var type: String? by mutableStateOf(null)
        private set
    var selected: DiscoverCatalog? by mutableStateOf(null)
        private set
    var genre: String? by mutableStateOf(null)
        private set

    val items = mutableStateListOf<MetaPreview>()
    var loading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var skip = 0
    private var job: Job? = null
    private var currentRequest: Any? = null

    val types: List<String> get() = catalogs.map { it.catalog.apiType }.distinct()
    val catalogsOfType: List<DiscoverCatalog> get() = catalogs.filter { it.catalog.apiType == type }

    /** Called when the installed addons change. [restoreKey] is the catalog shown last time. */
    fun setAddons(addons: List<Addon>, restoreKey: String?) {
        catalogs = addons.filter { it.enabled }.flatMap { addon ->
            addon.catalogs
                // Search-only catalogs need a query; everything else can be browsed.
                .filter { catalog -> !catalog.isSearchOnlyCatalog() && catalog.extraRequired.none { it.equals("search", true) } }
                .map { DiscoverCatalog(addon, it) }
        }
        val keep = catalogs.firstOrNull { it.key == (selected?.key ?: restoreKey) } ?: catalogs.firstOrNull()
        if (keep == null) {
            type = null
            selected = null
            items.clear()
            return
        }
        if (keep.key != selected?.key || items.isEmpty()) {
            type = keep.catalog.apiType
            select(keep, remember = false)
        }
    }

    fun selectType(newType: String) {
        if (newType == type) return
        type = newType
        catalogsOfType.firstOrNull()?.let { select(it) }
    }

    fun select(catalog: DiscoverCatalog, remember: Boolean = true) {
        selected = catalog
        type = catalog.catalog.apiType
        genre = if (catalog.genreRequired) catalog.genres.firstOrNull() else null
        if (remember) onCatalogChosen(catalog.key)
        reload()
    }

    fun selectGenre(newGenre: String?) {
        if (newGenre == genre) return
        genre = newGenre
        reload()
    }

    private fun reload() {
        job?.cancel()
        items.clear()
        skip = 0
        endReached = false
        error = null
        loading = false
        loadMore()
    }

    /** Fetches the next page, unless one is already on its way or the catalog is exhausted. */
    fun loadMore() {
        val current = selected ?: return
        if (loading || endReached) return
        loading = true
        val pageSkip = skip
        val extra = genre?.let { mapOf("genre" to it) }.orEmpty()
        val request = Any()
        currentRequest = request
        job = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { client.catalog(current.addon, current.catalog, pageSkip, extra) }
                val seen = items.mapTo(HashSet()) { "${it.apiType}:${it.id}" }
                val fresh = page.filter { seen.add("${it.apiType}:${it.id}") }
                items.addAll(fresh)
                // A page with nothing new, or a catalog that can't page, ends the list.
                endReached = fresh.isEmpty() || !current.catalog.supportsExtra("skip")
                skip = pageSkip + current.catalog.skipStep()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load"
                endReached = true
            } finally {
                // A cancelled, older request mustn't clear the flag for the one replacing it.
                if (currentRequest === request) loading = false
            }
        }
    }
}
