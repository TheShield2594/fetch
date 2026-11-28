package com.fetch.tv.ui.components

import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.repository.MetaRepository
import com.fetch.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The browse screens' focus prefetch (Library, Discover): when a card takes focus, its full meta
 * and episode progress are fetched in the background, so the detail page opens at once and the
 * hero band can fill in. Debounced, so a fast scroll doesn't flood the addons.
 */
class FocusMetaPrefetcher(
    private val scope: CoroutineScope,
    private val metaRepository: MetaRepository,
    private val watchProgressRepository: WatchProgressRepository
) {
    private val prefetchedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var job: Job? = null
    private val _version = MutableStateFlow(0)

    /** Goes up each time a prefetch lands, so the hero band can fill in what it now has. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun prefetch(id: String, type: String) {
        if (id.isBlank() || id in prefetchedIds) return
        job?.cancel()
        job = scope.launch {
            delay(PREFETCH_DEBOUNCE_MS)
            if (id in prefetchedIds) return@launch
            metaRepository.getMetaFromAllAddons(type = type, id = id).first { it !is NetworkResult.Loading }
            watchProgressRepository.getAllEpisodeProgress(id.substringBefore(":")).first()
            // Marked only once done, so a prefetch cancelled by the next focus is retried.
            prefetchedIds.add(id)
            // The hero band re-reads the cache now it has the title's full meta (#852).
            _version.update { it + 1 }
        }
    }

    /** The full meta of a title, when a prefetch or the detail page has cached it. */
    fun cachedMeta(id: String, type: String): Meta? = metaRepository.getCachedMeta(type, id)

    fun cachedBackdrop(id: String, type: String): String? = cachedMeta(id, type)?.backdropUrl

    private companion object {
        const val PREFETCH_DEBOUNCE_MS = 150L
    }
}
