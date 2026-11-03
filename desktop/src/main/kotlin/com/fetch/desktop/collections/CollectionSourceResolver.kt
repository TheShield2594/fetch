package com.fetch.desktop.collections

import com.fetch.desktop.LocalList
import com.fetch.desktop.data.AddonClient
import com.fetch.desktop.tmdb.TmdbCollectionSources
import com.fetch.desktop.tracking.TraktListPage
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonCatalogCollectionSource
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CollectionSource
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.FetchListCollectionSource
import com.fetch.tv.domain.model.LocalListCollectionSource
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import com.fetch.tv.domain.model.TmdbCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionSourceType
import com.fetch.tv.domain.model.TraktCollectionSource
import com.fetch.tv.domain.model.TraktListSort
import com.fetch.tv.domain.model.TraktSortHow
import com.fetch.tv.domain.model.findCollectionCatalog
import com.fetch.tv.domain.model.skipStep
import com.fetch.tv.domain.model.supportsExtra
import java.util.concurrent.ConcurrentHashMap

/** One page of a folder source's titles. */
data class SourcePage(val items: List<MetaPreview>, val hasMore: Boolean)

/** Reads a page of a Trakt list (through `TrackingState.withTraktPublic`); null when this build has no Trakt. */
fun interface TraktListReader {
    suspend fun page(listId: Long, type: String, page: Int, limit: Int, sortBy: String, sortHow: String): TraktListPage?
}

/**
 * Loads a folder source a page at a time, as the TV's `FolderDetailViewModel` does: addon
 * catalogs (with their genre, paged by `skip`, read from the source's `addonUrl` when the addon
 * isn't installed), TMDB, Trakt lists, local lists and lists on the Fetch website this computer is
 * signed in to (one page, as on TV: lists are personal-sized).
 */
class CollectionSourceResolver(
    private val addons: () -> List<Addon>,
    /** Installed addons that are switched off: their catalogs give nothing, and aren't fetched from the saved link. */
    private val disabledAddons: () -> List<Addon> = { emptyList() },
    private val addonClient: AddonClient,
    private val tmdb: TmdbCollectionSources,
    private val tmdbLanguage: () -> String,
    private val trakt: TraktListReader,
    private val localLists: () -> List<LocalList>,
    /** A website list's titles of the given types (`movie`, `series`); null when the website isn't set up. */
    private val websiteList: (suspend (listId: String, types: List<String>) -> List<MetaPreview>)? = null
) {
    private val remoteAddons = ConcurrentHashMap<String, Addon>()

    /** [page] counts from 1. */
    suspend fun load(source: CollectionSource, page: Int): SourcePage = when (source) {
        is AddonCatalogCollectionSource -> addonPage(source, page)
        is TmdbCollectionSource -> tmdb.resolve(source, page, tmdbLanguage())
        is TraktCollectionSource -> traktPage(source, page)
        is LocalListCollectionSource -> localPage(source)
        is FetchListCollectionSource -> websitePage(source, page)
    }

    private suspend fun addonPage(source: AddonCatalogCollectionSource, page: Int): SourcePage {
        if (findCatalog(addons(), source) == null && findCatalog(disabledAddons(), source) != null) return SourcePage(emptyList(), false)
        val (addon, catalog) = findCatalog(addons(), source) ?: run {
            val url = source.addonUrl ?: error(ADDON_MISSING)
            val remote = remoteAddons[url] ?: addonClient.fetchAddon(url).also { remoteAddons[url] = it }
            findCatalog(listOf(remote), source) ?: error("The addon no longer has this catalog.")
        }
        val step = catalog.skipStep()
        val canPage = catalog.supportsExtra("skip")
        if (page > 1 && !canPage) return SourcePage(emptyList(), false)
        val items = addonClient.catalog(addon, catalog, skip = (page - 1) * step, extra = genreExtra(source))
        return SourcePage(items, hasMore = canPage && items.isNotEmpty())
    }

    private suspend fun traktPage(source: TraktCollectionSource, page: Int): SourcePage {
        val type = if (source.mediaType == TmdbCollectionMediaType.TV) "show" else "movie"
        val result = trakt.page(
            source.traktListId, type, page, TRAKT_PAGE_LIMIT,
            TraktListSort.normalize(source.sortBy), TraktSortHow.normalize(source.sortHow)
        ) ?: error("Trakt isn't set up in this build.")
        val series = source.mediaType == TmdbCollectionMediaType.TV
        val items = result.items.filter { it.isShow == series }.map { item ->
            MetaPreview(
                id = item.id,
                type = if (series) ContentType.SERIES else ContentType.MOVIE,
                rawType = if (series) "series" else "movie",
                name = item.title,
                poster = item.poster,
                posterShape = PosterShape.POSTER,
                background = item.fanart,
                logo = item.logo,
                description = item.overview,
                releaseInfo = item.year?.toString(),
                imdbRating = item.rating?.toFloat(),
                genres = item.genres,
                runtime = item.runtime?.let { "$it min" },
                imdbId = item.id.takeIf { it.startsWith("tt") }
            )
        }.distinctBy { "${it.apiType}:${it.id}" }
        return SourcePage(items, hasMore = page < result.pageCount && items.isNotEmpty())
    }

    private suspend fun websitePage(source: FetchListCollectionSource, page: Int): SourcePage {
        if (page > 1) return SourcePage(emptyList(), hasMore = false)
        val read = websiteList ?: error("Sign in to the Fetch website in Settings › Account to show this list.")
        val types = when (source.mediaType) {
            TmdbCollectionMediaType.MOVIE -> listOf("movie")
            TmdbCollectionMediaType.TV -> listOf("series")
            null -> listOf("movie", "series")
        }
        return SourcePage(read(source.listId, types).distinctBy { "${it.apiType}:${it.id}" }, hasMore = false)
    }

    private fun localPage(source: LocalListCollectionSource): SourcePage {
        val list = localLists().firstOrNull { it.id == source.listId } ?: error("This list isn't on this computer.")
        val items = list.items
            .filter { item ->
                val type = item.type.trim().lowercase()
                when (source.mediaType) {
                    null -> true
                    TmdbCollectionMediaType.MOVIE -> type == "movie"
                    TmdbCollectionMediaType.TV -> type in setOf("series", "tv", "show", "anime")
                }
            }
            .sortedByDescending { it.addedAt }
            .map { it.toPreview() }
        return SourcePage(items, hasMore = false)
    }

    companion object {
        const val TRAKT_PAGE_LIMIT = 50
        const val ADDON_MISSING = "This catalog's addon isn't installed. Install it from Addons, or remove this source from the folder."

        private fun genreExtra(source: AddonCatalogCollectionSource): Map<String, String> =
            source.genre?.takeIf { it.isNotBlank() && !it.equals("None", ignoreCase = true) }?.let { mapOf("genre" to it) }.orEmpty()

        /** The source's addon and catalog: its own addon first, then any that has the catalog (as on TV). */
        fun findCatalog(addons: List<Addon>, source: AddonCatalogCollectionSource): Pair<Addon, CatalogDescriptor>? {
            val own = addons.firstOrNull { it.id == source.addonId }
            if (own != null) {
                val catalog = own.catalogs.findCollectionCatalog(source.type, source.catalogId)
                    ?: own.catalogs.findCollectionCatalog(source.type, source.catalogId.substringBefore(","))
                if (catalog != null) return own to catalog
            }
            for (addon in addons) {
                addon.catalogs.findCollectionCatalog(source.type, source.catalogId)?.let { return addon to it }
            }
            return null
        }

        private fun typeName(type: String): String = when (type.lowercase()) {
            "movie" -> "Movies"
            "series" -> "Series"
            else -> type.replaceFirstChar { it.uppercase() }
        }

        /** A source's tab name and its kind, as the TV labels folder tabs. */
        fun label(source: CollectionSource, addons: List<Addon>): Pair<String, String> = when (source) {
            is AddonCatalogCollectionSource -> {
                val catalog = findCatalog(addons, source)?.second
                val typeLabel = typeName(source.type)
                val base = catalog?.name?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: typeLabel
                val genre = source.genre?.takeIf { it.isNotBlank() && !it.equals("None", ignoreCase = true) }
                (if (genre != null) "$base · $genre" else base) to typeLabel
            }
            is TmdbCollectionSource -> source.title to when (source.sourceType) {
                TmdbCollectionSourceType.LIST -> "TMDB List"
                TmdbCollectionSourceType.COLLECTION -> "Movie collection"
                TmdbCollectionSourceType.COMPANY -> "Production company"
                TmdbCollectionSourceType.NETWORK -> "Network"
                TmdbCollectionSourceType.PERSON -> "Person credits"
                TmdbCollectionSourceType.DIRECTOR -> "Director credits"
                TmdbCollectionSourceType.DISCOVER -> "TMDB Discover"
            }
            is TraktCollectionSource -> source.title to if (source.mediaType == TmdbCollectionMediaType.TV) "Trakt series list" else "Trakt movie list"
            is FetchListCollectionSource -> source.title to "Website list"
            is LocalListCollectionSource -> source.title to "Local list"
        }

        /** The TV's "All" tab: each source's titles in turn, first come first kept. */
        fun roundRobin(lists: List<List<MetaPreview>>): List<MetaPreview> {
            val result = mutableListOf<MetaPreview>()
            val seen = mutableSetOf<String>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (list in lists) {
                val item = list.getOrNull(i) ?: continue
                if (seen.add(item.id)) result += item
            }
            return result
        }
    }
}
