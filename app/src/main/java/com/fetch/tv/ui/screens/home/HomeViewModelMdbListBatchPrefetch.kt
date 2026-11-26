package com.fetch.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.fetch.tv.domain.model.MDBListRatings
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

private const val ROW_FOCUS_DEBOUNCE_MS = 250L
private const val MDB_BATCH_CONCURRENCY = 4
private const val PRESENTATION_WAIT_MS = 2_000L

/**
 * MDBList ratings are fetched only for the focused row and the next catalog row below it, at
 * most [MDB_BATCH_CONCURRENCY] requests at a time, and land in one state update per row
 * (TheShield2594/fetch#823). Rows away from focus are fetched when focus reaches them.
 */
internal fun HomeViewModel.onFocusedRowChangedForMdbBatch(rowKey: String?) {
    if (rowKey == null) return
    mdbBatchFocusedRowKey = rowKey
    if (!isMdbBatchPrefetchEnabled()) return

    val isFirst = !mdbBatchHasFired
    mdbBatchRowFocusJob?.cancel()
    mdbBatchRowFocusJob = viewModelScope.launch(Dispatchers.IO) {
        if (!isFirst) delay(ROW_FOCUS_DEBOUNCE_MS)
        mdbBatchHasFired = true
        batchFetchRatingsForRowAndNext(rowKey)
    }
}

/** [rowKey] is the catalog key ([catalogKey]), which presentation rows carry as [orderKey]. */
internal fun HomeViewModel.onCatalogRowItemsChanged(rowKey: String) {
    if (!isMdbBatchPrefetchEnabled()) return
    if (mdbBatchFocusedRowKey == null) return

    viewModelScope.launch(Dispatchers.IO) {
        // The presentation rebuild that adds a newly loaded row runs after this call.
        withTimeoutOrNull(PRESENTATION_WAIT_MS) {
            _modernHomePresentation.first { presentation ->
                presentation.rows.list.any { it.orderKey() == rowKey }
            }
        } ?: return@launch
        // A row that loads, refreshes or pages in is fetched only if it is one focus covers.
        val focusedRowKey = mdbBatchFocusedRowKey ?: return@launch
        val targetKeys = mdbBatchTargetRowKeys(focusedRowKey).toSet()
        val coveredByFocus = _modernHomePresentation.value.rows.list.any { row ->
            row.key in targetKeys && row.orderKey() == rowKey
        }
        if (!coveredByFocus) return@launch
        val row = readCatalogRow(rowKey) ?: return@launch
        val newItems = row.items.filter { it.mdbListRatings == null && !mdbBatchNegativeIds.contains(it.id) }
        if (newItems.isEmpty()) return@launch
        batchFetchRatingsForItems(newItems)
    }
}

/** The focused row and the next catalog row after it; Continue Watching style rows are skipped. */
internal fun mdbBatchTargetRowKeys(
    rowKeys: List<String>,
    focusedRowKey: String,
    isContinueWatchingStyleRow: (String) -> Boolean
): List<String> {
    if (isContinueWatchingStyleRow(focusedRowKey)) return emptyList()
    val focusedIdx = rowKeys.indexOf(focusedRowKey)
    if (focusedIdx < 0) return emptyList()
    val next = rowKeys.asSequence()
        .drop(focusedIdx + 1)
        .firstOrNull { !isContinueWatchingStyleRow(it) }
    return listOfNotNull(focusedRowKey, next)
}

private fun HomeViewModel.mdbBatchTargetRowKeys(focusedRowKey: String): List<String> =
    mdbBatchTargetRowKeys(
        rowKeys = _modernHomePresentation.value.rows.list.map { it.key },
        focusedRowKey = focusedRowKey,
        isContinueWatchingStyleRow = ::isContinueWatchingStyleRow
    )

private suspend fun HomeViewModel.batchFetchRatingsForRowAndNext(focusedRowKey: String) {
    val targetRowKeys = mdbBatchTargetRowKeys(focusedRowKey)
    if (targetRowKeys.isEmpty()) return
    val allRows = _modernHomePresentation.value.rows.list

    for (rk in targetRowKeys) {
        val carouselRow = allRows.firstOrNull { it.key == rk } ?: continue
        val itemsToFetch = carouselRow.items.list.mapNotNull { carouselItem ->
            val meta = carouselItem.metaPreview ?: return@mapNotNull null
            if (mdbBatchNegativeIds.contains(meta.id)) return@mapNotNull null
            val current = findCatalogItemById(meta.id)
            if (current?.mdbListRatings != null) return@mapNotNull null
            current ?: meta
        }
        // One row at a time, the focused row first, each applied as soon as it is done.
        batchFetchRatingsForItems(itemsToFetch)
    }
}

private suspend fun HomeViewModel.batchFetchRatingsForItems(items: List<MetaPreview>) {
    // Skip items another run is already fetching (a row change and a focus move can overlap).
    val claimed = items.distinctBy { it.id }.filter { mdbBatchInFlightIds.add(it.id) }
    if (claimed.isEmpty()) return

    val ratingsById = ConcurrentHashMap<String, MDBListRatings>()
    val limiter = Semaphore(MDB_BATCH_CONCURRENCY)
    try {
        coroutineScope {
            claimed.map { item ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        try {
                            val result = mdbListRepository.getRatingsForMeta(
                                meta = item.toMdbMeta(),
                                fallbackItemId = item.id,
                                fallbackItemType = item.apiType
                            )
                            if (result != null) {
                                ratingsById[item.id] = result.ratings
                            } else {
                                mdbBatchNegativeIds.recordMiss(item.id)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            mdbBatchNegativeIds.recordFailure(item.id)
                        }
                    }
                }
            }.awaitAll()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    } finally {
        mdbBatchInFlightIds.removeAll(claimed.map { it.id }.toSet())
        // Whatever arrived before a cancellation still lands, in one update.
        if (ratingsById.isNotEmpty()) updateCatalogItemsMdbListRatings(ratingsById)
    }
}

private fun HomeViewModel.isMdbBatchPrefetchEnabled(): Boolean {
    val settings = currentMdbListSettings
    return mdbListRepository.isAvailable(settings) && settings.showOnHero
}

private fun MetaPreview.toMdbMeta(): Meta = Meta(
    id = id,
    type = type,
    name = name,
    poster = poster,
    posterShape = posterShape,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    runtime = runtime,
    director = director,
    cast = emptyList(),
    videos = emptyList(),
    country = country,
    awards = null,
    language = language,
    links = links
)
