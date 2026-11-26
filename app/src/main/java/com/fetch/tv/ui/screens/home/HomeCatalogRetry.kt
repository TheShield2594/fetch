package com.fetch.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CatalogRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Recovery for Home rows that fail to load (#799).
 *
 * A failed catalog used to drop its placeholder and vanish for the rest of the session: the
 * resume refresh only covered rows that had loaded, and the lazy-load guard blocked a second
 * request. A failed row is now remembered and retried on its own with backoff, and again every
 * time Home comes back to the foreground.
 */
internal data class FailedHomeCatalog(
    val addon: Addon,
    val catalog: CatalogDescriptor,
    /** Failures in a row for this catalog, 1 after the first. */
    val failures: Int
)

/** The page 1 a refresh fetched for the focused row, held until focus leaves that row. */
internal data class DeferredHomeRowRefresh(
    val catalogKey: String,
    val fresh: CatalogRow
)

/** Delays before each automatic retry of a failed Home row; none after the last. */
internal val HOME_CATALOG_RETRY_BACKOFF_MS = longArrayOf(15_000L, 60_000L, 4L * 60_000L)

/** Delay before the automatic retry that follows the [failures]th failure, or null to stop. */
internal fun homeCatalogRetryDelayMs(failures: Int): Long? =
    HOME_CATALOG_RETRY_BACKOFF_MS.getOrNull(failures - 1)

/**
 * Called when a row's load fails. A row already on screen keeps what it has; one that never
 * loaded is remembered and retried after a backoff.
 */
internal fun HomeViewModel.onHomeCatalogLoadFailed(
    key: String,
    addon: Addon,
    catalog: CatalogDescriptor,
    generation: Long
) {
    // A refresh that fails leaves the loaded row in place, so there is nothing to recover.
    retryingHomeCatalogKeys.remove(key)
    if (readCatalogRow(key) != null) return
    // The lazy-load guard would otherwise block the row from ever being requested again.
    lazyLoadRequestedKeys.remove(key)
    val failures = (failedHomeCatalogs[key]?.failures ?: 0) + 1
    failedHomeCatalogs[key] = FailedHomeCatalog(addon, catalog, failures)
    failedCatalogRetryJobs.remove(key)?.cancel()
    val delayMs = homeCatalogRetryDelayMs(failures) ?: run {
        Log.w(HomeViewModel.TAG, "Home catalog $key failed $failures times; next try on resume")
        return
    }
    failedCatalogRetryJobs[key] = viewModelScope.launch {
        delay(delayMs)
        if (generation != catalogLoadGeneration) return@launch
        failedCatalogRetryJobs.remove(key)
        retryFailedHomeCatalog(key)
    }
}

internal fun HomeViewModel.onHomeCatalogLoaded(key: String) {
    retryingHomeCatalogKeys.remove(key)
    failedHomeCatalogs.remove(key)
    failedCatalogRetryJobs.remove(key)?.cancel()
}

private fun HomeViewModel.retryFailedHomeCatalog(key: String) {
    val failed = failedHomeCatalogs[key] ?: return
    // A retry already in flight (the timer and a resume can coincide) answers for both.
    if (!retryingHomeCatalogKeys.add(key)) return
    Log.d(HomeViewModel.TAG, "Retrying failed home catalog $key (failures=${failed.failures})")
    pendingCatalogLoads += 1
    loadCatalogPipeline(failed.addon, failed.catalog, catalogLoadGeneration)
}

/**
 * Retries every row that is still failed, whatever its backoff. Runs when Home comes back to the
 * foreground, so a row lost to one timeout at launch does not stay missing for the session.
 */
internal fun HomeViewModel.retryFailedHomeCatalogsPipeline() {
    val keys = failedHomeCatalogs.keys.toList()
    if (keys.isEmpty()) return
    keys.forEach { key ->
        failedCatalogRetryJobs.remove(key)?.cancel()
        retryFailedHomeCatalog(key)
    }
}

/** Forgets every failed row and its pending retry, for a full reload. */
internal fun HomeViewModel.clearFailedHomeCatalogs() {
    failedCatalogRetryJobs.values.forEach { it.cancel() }
    failedCatalogRetryJobs.clear()
    failedHomeCatalogs.clear()
    retryingHomeCatalogKeys.clear()
    deferredFocusedRowRefreshes.clear()
}

/**
 * A refresh that restructured the focused row is held back so the cards under the focus ring are
 * not rebuilt. It used to be dropped and picked up only after another full TTL; it is now applied
 * as soon as focus leaves that row.
 */
internal fun HomeViewModel.applyDeferredRowRefreshes(focusedRowKey: String?) {
    if (deferredFocusedRowRefreshes.isEmpty()) return
    val ready = deferredFocusedRowRefreshes.keys.filter { it != focusedRowKey }
    if (ready.isEmpty()) return
    var applied = false
    ready.forEach { stableKey ->
        val deferred = deferredFocusedRowRefreshes.remove(stableKey) ?: return@forEach
        // The row may have gone since (addon removed, reload): only replace a row still there.
        if (readCatalogRow(deferred.catalogKey) == null) return@forEach
        replaceCatalogRow(deferred.catalogKey, deferred.fresh)
        onCatalogRowItemsChanged(deferred.catalogKey)
        applied = true
    }
    if (applied) scheduleUpdateCatalogRows()
}
