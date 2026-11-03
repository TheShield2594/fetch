package com.fetch.desktop.tmdb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.FinishedTitle
import com.fetch.desktop.data.TmdbSettings
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** "Because you watched <name>": TMDB's recommendations for a title you finished. */
data class BecauseYouWatchedRow(val seed: FinishedTitle, val items: List<MetaPreview>)

/** A studio's or network's page: its titles, a page at a time. */
data class EntityBrowse(
    val kind: TmdbEntityKind,
    val id: Int,
    val name: String,
    val logo: String? = null,
    val series: Boolean,
    val items: List<MetaPreview> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 1,
    val loading: Boolean = true,
    val error: String? = null
) {
    val canLoadMore: Boolean get() = !loading && page < totalPages
}

/**
 * What the TMDB client feeds the screens: a title page's extras (More like this, its collection,
 * TMDB's rating), person pages, studio and network pages, and Home's "Because you watched" rows.
 * All of it follows Settings › TMDB, as on TV.
 */
class TmdbState(
    private val scope: CoroutineScope,
    val client: TmdbClient,
    private val settings: () -> TmdbSettings
) {
    val active: Boolean get() = settings().enabled && client.isConfigured
    private val language: String get() = settings().language

    // --- Title page ---

    /** Which title the extras below belong to. */
    var extrasFor: String? by mutableStateOf(null)
        private set
    var tmdbRating: Float? by mutableStateOf(null)
        private set
    var moreLikeThis by mutableStateOf<List<MetaPreview>>(emptyList())
        private set
    var collection: TmdbCollection? by mutableStateOf(null)
        private set
    private var extrasJob: Job? = null

    /**
     * [meta] with TMDB's details merged in, and the extras loaded for it. [isSeries] picks TMDB's
     * movie or TV record. Returns [meta] unchanged when TMDB is off or doesn't know the title.
     */
    suspend fun enrich(meta: Meta, isSeries: Boolean): Meta {
        clearExtras(meta.id)
        if (!active) return meta
        val s = settings()
        return try {
            val id = client.tmdbId(meta.imdbId ?: meta.id, isSeries) ?: return meta
            val (title, episodes) = coroutineScope {
                val t = async { client.title(id, isSeries, s.language) }
                val e = async {
                    if (isSeries && s.useEpisodes) client.episodes(id, meta.videos.mapNotNull { it.season }, s.language) else emptyMap()
                }
                t.await() to e.await()
            }
            loadExtras(meta.id, id, isSeries, title)
            TmdbEnrich.apply(meta, title, episodes, s)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            meta
        }
    }

    /** A title page for a `tmdb:` id with no IMDb id: built from TMDB alone. */
    suspend fun metaFor(tmdbId: Int, isSeries: Boolean): Meta? {
        if (!client.isConfigured) return null
        val title = runCatching { client.title(tmdbId, isSeries, language) }.getOrNull() ?: return null
        val episodes = if (isSeries) runCatching { client.episodes(tmdbId, title.seasons.filter { it > 0 }, language) }.getOrDefault(emptyMap()) else emptyMap()
        val meta = TmdbEnrich.metaFor(title, episodes)
        clearExtras(meta.id)
        if (settings().enabled) loadExtras(meta.id, tmdbId, isSeries, title)
        return meta
    }

    private fun clearExtras(forId: String) {
        extrasJob?.cancel()
        extrasFor = forId
        tmdbRating = null
        moreLikeThis = emptyList()
        collection = null
    }

    private fun loadExtras(forId: String, tmdbId: Int, isSeries: Boolean, title: TmdbTitle?) {
        val s = settings()
        if (s.useBasicInfo) tmdbRating = title?.rating
        extrasJob = scope.launch {
            if (s.useMoreLikeThis) {
                val recs = runCatching { client.recommendations(tmdbId, isSeries, s.language) }.getOrDefault(emptyList())
                if (extrasFor == forId) moreLikeThis = recs
            }
            val collectionId = title?.collectionId
            if (s.useCollections && collectionId != null) {
                val c = runCatching { client.collection(collectionId, s.language) }.getOrNull()
                if (extrasFor == forId) collection = c
            }
        }
    }

    // --- People ---

    var person: TmdbPerson? by mutableStateOf(null)
        private set
    var personLoading by mutableStateOf(false)
        private set
    var personError: String? by mutableStateOf(null)
        private set

    fun loadPerson(id: Int) {
        if (person?.id == id) return
        person = null
        personError = null
        personLoading = true
        scope.launch {
            try {
                person = client.person(id, language)
                if (person == null) personError = "TMDB doesn't know this person."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                personError = "Couldn't reach TMDB (${e.message})"
            } finally {
                personLoading = false
            }
        }
    }

    // --- Studios and networks ---

    var entity: EntityBrowse? by mutableStateOf(null)
        private set
    private var entityJob: Job? = null

    /** Opens a studio's or network's page; [series] picks its shows (companies make both). */
    fun openEntity(kind: TmdbEntityKind, id: Int, name: String, series: Boolean = kind == TmdbEntityKind.NETWORK) {
        val current = entity
        if (current != null && current.kind == kind && current.id == id && current.series == series) return
        entityJob?.cancel()
        entity = EntityBrowse(kind, id, name, current?.takeIf { it.id == id && it.kind == kind }?.logo, series)
        if (current?.id != id || current.kind != kind) {
            scope.launch {
                runCatching { client.entity(kind, id) }.getOrNull()?.let { info ->
                    entity?.takeIf { it.id == id && it.kind == kind }?.let { entity = it.copy(name = info.name, logo = info.logo) }
                }
            }
        }
        loadMoreEntity()
    }

    fun loadMoreEntity() {
        val current = entity ?: return
        if (current.page >= current.totalPages && current.page > 0) return
        if (current.page > 0 && current.loading) return
        entity = current.copy(loading = true, error = null)
        entityJob = scope.launch {
            try {
                val page = client.discover(current.kind, current.id, current.series, current.page + 1, language)
                entity?.takeIf { it.id == current.id && it.kind == current.kind && it.series == current.series }?.let {
                    entity = it.copy(
                        items = (it.items + page.items).distinctBy { p -> p.id },
                        page = page.page,
                        totalPages = page.totalPages,
                        loading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                entity = entity?.copy(loading = false, error = "Couldn't reach TMDB (${e.message})")
            }
        }
    }

    // --- Home ---

    var becauseYouWatched by mutableStateOf<List<BecauseYouWatchedRow>>(emptyList())
        private set
    private var bywKey: List<String>? = null

    /** One row for each of the last [MAX_BYW_ROWS] titles finished, when More like this is on. */
    fun refreshBecauseYouWatched(finished: List<FinishedTitle>) {
        val s = settings()
        if (!active || !s.useMoreLikeThis) {
            becauseYouWatched = emptyList()
            bywKey = null
            return
        }
        val seeds = finished.take(MAX_BYW_ROWS)
        val key = seeds.map { it.contentId } + s.language
        if (key == bywKey) return
        bywKey = key
        scope.launch {
            val rows = coroutineScope {
                seeds.map { seed ->
                    async {
                        val series = seed.contentType == "series" || seed.contentType == "tv"
                        val id = runCatching { client.tmdbId(seed.contentId, series) }.getOrNull() ?: return@async null
                        val items = runCatching { client.recommendations(id, series, s.language) }.getOrDefault(emptyList())
                        items.takeIf { it.isNotEmpty() }?.let { BecauseYouWatchedRow(seed, it) }
                    }
                }.awaitAll().filterNotNull()
            }
            if (bywKey == key) becauseYouWatched = rows
        }
    }

    companion object {
        /** The TV shows two: the last title finished and the one before. */
        const val MAX_BYW_ROWS = 2
    }
}
