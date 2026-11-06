package com.fetch.tv.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.data.mediaserver.ServerCatalog
import com.fetch.tv.data.mediaserver.ServerException
import com.fetch.tv.data.mediaserver.ServerFailure
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.data.mediaserver.serverFailure
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.mergeCatalogPage
import com.fetch.tv.domain.model.nextCatalogSkip
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ServerCatalogViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverCatalog: ServerCatalog
) : ViewModel() {
    private val collection = ServerItemRef.parse(savedStateHandle.get<String>("itemId"))
    private val connectionId = savedStateHandle.get<String>("addonId")?.let(ServerCatalog::connectionIdFromAddonId)
    private val catalogId = savedStateHandle.get<String>("catalogId")
    private val _row = MutableStateFlow<CatalogRow?>(null)
    // Why the first load failed, kept so the screen can say so and offer Retry rather than
    // showing the catalog as empty.
    private val _failure = MutableStateFlow<ServerFailure?>(null)
    val row: StateFlow<CatalogRow?> = _row.asStateFlow()
    val failure: StateFlow<ServerFailure?> = _failure.asStateFlow()
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    /** When the last page request failed; further requests wait [LOAD_MORE_RETRY_DELAY_MS]. */
    private var loadMoreFailedAtMs: Long = 0L

    init {
        reload()
    }

    /** Runs the first-page load again, for Retry after a failure. */
    fun reload() {
        loadJob?.cancel()
        // A page request from before the reload would merge into the new first page.
        loadMoreJob?.cancel()
        loadMoreJob = null
        loadMoreFailedAtMs = 0L
        _failure.value = null
        loadJob = viewModelScope.launch {
            try {
                _row.value = when {
                    collection != null -> serverCatalog.collectionRow(collection)
                    connectionId != null && catalogId != null -> serverCatalog.row(connectionId, catalogId)
                    else -> throw ServerException(ServerFailure.NOT_FOUND)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _failure.value = error.serverFailure()
            }
        }
    }

    fun loadMore() {
        val current = _row.value ?: return
        if (current.isLoading || !current.hasMore) return
        // After a failed page, the grid's end keeps asking; space the retries out.
        if (android.os.SystemClock.elapsedRealtime() - loadMoreFailedAtMs < LOAD_MORE_RETRY_DELAY_MS) return
        _row.value = current.copy(isLoading = true)
        loadMoreJob = viewModelScope.launch {
            try {
                val page = serverCatalog.page(current, current.nextCatalogSkip())
                _row.update { row -> row?.mergeCatalogPage(page)?.copy(isLoading = false) }
            } catch (error: CancellationException) {
                _row.update { row -> row?.copy(isLoading = false) }
                throw error
            } catch (_: Exception) {
                // One failed page (a timeout, say) shouldn't end pagination for good: keep
                // hasMore so the next scroll to the end tries again.
                loadMoreFailedAtMs = android.os.SystemClock.elapsedRealtime()
                _row.update { row -> row?.copy(isLoading = false) }
            }
        }
    }

    private companion object {
        const val LOAD_MORE_RETRY_DELAY_MS = 3_000L
    }
}
