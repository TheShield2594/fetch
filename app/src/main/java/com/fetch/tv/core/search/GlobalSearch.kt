package com.fetch.tv.core.search

import android.util.Log
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.parental.ProfilePinManager
import com.fetch.tv.core.poster.CustomPosterScreen
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.data.mediaserver.ServerCatalog
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.enabledAddons
import com.fetch.tv.domain.model.skipStep
import com.fetch.tv.domain.model.supportsExtra
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GlobalSearch"

/** One title for the TV's own search: what the launcher shows, and the link that opens it in Fetch. */
data class GlobalSearchResult(
    val id: String,
    val title: String,
    val year: Int?,
    val description: String?,
    val posterUrl: String?,
    val isMovie: Boolean,
    val deepLink: String
)

/**
 * Answers the TV's system search (the launcher's search, and the Assistant on Android TV) from
 * the active profile's addon and media server search catalogs, as Fetch's own search does.
 *
 * It answers nothing on a TV with parental limits on any profile, or while the active profile
 * has a PIN: the launcher shows results to whoever holds the remote, outside every profile lock.
 */
@Singleton
class GlobalSearch @Inject constructor(
    private val addonRepository: AddonRepository,
    private val serverCatalog: ServerCatalog,
    private val catalogRepository: CatalogRepository,
    private val profileManager: ProfileManager,
    private val restrictionsDataStore: ProfileRestrictionsDataStore,
    private val pinManager: ProfilePinManager
) {
    private val cache = object : LinkedHashMap<String, CachedResults>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedResults>?): Boolean =
            size > CACHE_SIZE
    }

    private class CachedResults(val atMs: Long, val results: List<GlobalSearchResult>)

    suspend fun search(rawQuery: String, limit: Int): List<GlobalSearchResult> {
        val query = rawQuery.trim()
        if (query.length < MIN_QUERY_LENGTH) return emptyList()
        if (!isAllowed()) return emptyList()

        val key = "${profileManager.activeProfileId.value}\u0000${query.lowercase()}"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.takeIf { now - it.atMs < CACHE_TTL_MS }?.let { return it.results.take(limit) }
        }
        val results = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { searchCatalogs(query) }.orEmpty()
        val merged = mergeGlobalSearchResults(results, MAX_RESULTS)
        if (merged.isNotEmpty()) synchronized(cache) { cache[key] = CachedResults(now, merged) }
        return merged.take(limit)
    }

    private suspend fun isAllowed(): Boolean {
        withTimeoutOrNull(PROFILE_READY_TIMEOUT_MS) { profileManager.activeProfileReady.first { it } } ?: return false
        if (restrictionsDataStore.restrictions.first().values.any { it.isRestricted }) return false
        val activeId = profileManager.activeProfileId.value
        return pinManager.pinEnabled.first()[activeId] != true
    }

    /** Every searchable catalog at once; each one's first page, in catalog order. */
    private suspend fun searchCatalogs(query: String): List<List<MetaPreview>> = coroutineScope {
        val addons = addonRepository.getInstalledAddons().first().enabledAddons() + serverCatalog.searchAddons.first()
        globalSearchTargets(addons).map { (addon, catalog) ->
            async {
                try {
                    withTimeoutOrNull(CATALOG_TIMEOUT_MS) { loadCatalog(addon, catalog, query) }.orEmpty()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Search failed for ${addon.displayName}/${catalog.id}", error)
                    emptyList()
                }
            }
        }.map { it.await() }
    }

    private suspend fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, query: String): List<MetaPreview> {
        val result = catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = catalog.skipStep(),
            extraArgs = mapOf("search" to query),
            supportsSkip = catalog.supportsExtra("skip"),
            posterScreen = CustomPosterScreen.SEARCH
        ).first { it !is NetworkResult.Loading }
        return (result as? NetworkResult.Success)?.data?.items.orEmpty()
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val DEFAULT_LIMIT = 10
        private const val MAX_RESULTS = 25
        private const val CACHE_SIZE = 16
        private const val CACHE_TTL_MS = 60_000L
        // The launcher stops waiting after a few seconds; slow addons are left out, not waited on.
        private const val SEARCH_TIMEOUT_MS = 4_000L
        private const val CATALOG_TIMEOUT_MS = 3_500L
        private const val PROFILE_READY_TIMEOUT_MS = 2_000L
    }
}

/** Catalogs a search is all they need, as Fetch's search screen picks them. */
internal fun globalSearchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> =
    addons.flatMap { addon ->
        addon.catalogs
            .filter { catalog ->
                catalog.supportsExtra("search") &&
                    catalog.extra.none { it.isRequired && !it.name.equals("search", ignoreCase = true) }
            }
            .map { addon to it }
    }

/**
 * Interleaves the catalogs' results, so the first few the launcher shows aren't all from one
 * addon, and keeps one card per title. Only movies and series: those are what a link can open.
 */
internal fun mergeGlobalSearchResults(rows: List<List<MetaPreview>>, limit: Int): List<GlobalSearchResult> {
    val seen = HashSet<String>()
    val out = ArrayList<GlobalSearchResult>()
    val longest = rows.maxOfOrNull { it.size } ?: 0
    for (index in 0 until longest) {
        for (row in rows) {
            val item = row.getOrNull(index) ?: continue
            val result = item.toGlobalSearchResult() ?: continue
            val key = (item.imdbId ?: item.id).lowercase() + "|" + result.isMovie
            if (!seen.add(key)) continue
            out += result
            if (out.size >= limit) return out
        }
    }
    return out
}

internal fun MetaPreview.toGlobalSearchResult(): GlobalSearchResult? {
    val apiType = when (type) {
        ContentType.MOVIE -> "movie"
        ContentType.SERIES -> "series"
        else -> return null
    }
    if (id.isBlank() || name.isBlank()) return null
    return GlobalSearchResult(
        id = id,
        title = name,
        year = releaseInfo?.let { YEAR.find(it)?.value?.toIntOrNull() },
        description = description?.takeIf { it.isNotBlank() },
        posterUrl = poster?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        isMovie = type == ContentType.MOVIE,
        deepLink = "fetch://meta?type=$apiType&id=${URLEncoder.encode(id, "UTF-8")}"
    )
}

private val YEAR = Regex("\\b(19|20)\\d{2}\\b")
