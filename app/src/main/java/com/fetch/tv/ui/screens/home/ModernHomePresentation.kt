package com.fetch.tv.ui.screens.home

import android.content.Context
import androidx.compose.runtime.Immutable
import com.fetch.tv.LocaleCache
import com.fetch.tv.R
import com.fetch.tv.core.util.withAppLocale
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.fetch.tv.domain.model.stableItemKey
import com.fetch.tv.ui.util.StableList
import com.fetch.tv.ui.util.asStable
import kotlinx.coroutines.withContext

@Immutable
internal data class ModernHomePresentationInput(
    val homeRows: List<HomeRow>,
    val catalogRows: List<CatalogRow>,
    val becauseYouWatchedRows: List<CatalogRow> = emptyList(),
    val continueWatchingItems: List<ContinueWatchingItem>,
    val upcomingItems: List<ContinueWatchingItem>,
    /** Continue Watching items whose Best match is ready; see [readyTonightItems]. */
    val readyTonightItems: List<ContinueWatchingItem> = emptyList(),
    /** Ready watchlist movies not already in Continue Watching; shown after [readyTonightItems]. */
    val readyWatchlistMovies: List<com.fetch.tv.domain.model.LibraryEntry> = emptyList(),
    /** Videos for the YouTube subscriptions row; empty leaves the row out. See [HomeYouTubeRow]. */
    val youTubeVideos: List<com.fetch.tv.data.youtube.YouTubeVideo> = emptyList(),
    /** A kids profile's Home: no recommendations, and at most [KIDS_HOME_MAX_ROWS] rows after Continue Watching. */
    val kidsHome: Boolean = false,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val showFullReleaseDate: Boolean,
    val showImdbRatings: Boolean,
    val detailImdbRatingsVisibility: com.fetch.tv.domain.model.DetailImdbRatingsVisibility =
        com.fetch.tv.domain.model.DetailImdbRatingsVisibility.SHOW_ALL,
    val localeTag: String,
    /** The profile's Movies / Shows filter (#814). */
    val contentFilter: HomeContentFilter = HomeContentFilter.ALL,
    /** Watched state by [homeItemStatusKey], so the Spotlight leaves watched titles out (#813). */
    val watchedStatus: Map<String, Boolean> = emptyMap(),
    /** The day the Spotlight is picked for; see [spotlightDay]. */
    val spotlightDay: Long = 0L
)

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache,
    context: Context,
    maxCatalogRows: Int? = null
): ModernHomePresentationState {
    // A saved filter that would leave nothing (its addon was removed, say) falls back to All,
    // so Home never ends up empty with the filter out of reach.
    val contentFilters = availableHomeContentFilters(input)
    val contentFilter = input.contentFilter.takeIf { it in contentFilters } ?: HomeContentFilter.ALL
    @Suppress("NAME_SHADOWING") val input = input.filteredBy(contentFilter)
    val visibleHomeRows = resolveVisibleHomeRows(input)
    val localizedContext = context.withAppLocale()
    val strContinueWatching = localizedContext.getString(R.string.continue_watching)
    val strAirsDate = localizedContext.getString(R.string.cw_airs_date)
    val strUpcoming = localizedContext.getString(R.string.cw_upcoming)
    val strTypeMovie = localizedContext.getString(R.string.type_movie)
    val strTypeSeries = localizedContext.getString(R.string.type_series)

    val rows = buildList {
        val activeCatalogKeys = LinkedHashSet<String>()
        val activeCollectionKeys = LinkedHashSet<String>()
        val catalogRowLimit = maxCatalogRows?.coerceAtLeast(0)
        var renderedCatalogRows = 0

        if (input.continueWatchingItems.isNotEmpty()) {
            val reuseContinueWatchingRow =
                cache.continueWatchingRow != null &&
                    cache.continueWatchingItems == input.continueWatchingItems &&
                    cache.continueWatchingTitle == strContinueWatching &&
                    cache.continueWatchingAirsDateTemplate == strAirsDate &&
                    cache.continueWatchingUpcomingLabel == strUpcoming &&
                    cache.continueWatchingUseLandscapePosters == input.useLandscapePosters &&
                    cache.continueWatchingShowImdbRatings == input.showImdbRatings
            val continueWatchingRow = if (reuseContinueWatchingRow) {
                checkNotNull(cache.continueWatchingRow)
            } else {
                HomeRowModel(
                    key = MODERN_CONTINUE_WATCHING_ROW_KEY,
                    title = strContinueWatching,
                    globalRowIndex = -1,
                    items = input.continueWatchingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            detailImdbRatingsVisibility = input.detailImdbRatingsVisibility,
                            airsDateTemplate = strAirsDate,
                            upcomingLabel = strUpcoming,
                            context = localizedContext
                        )
                    }.asStable()
                )
            }
            cache.continueWatchingItems = input.continueWatchingItems
            cache.continueWatchingTitle = strContinueWatching
            cache.continueWatchingAirsDateTemplate = strAirsDate
            cache.continueWatchingUpcomingLabel = strUpcoming
            cache.continueWatchingUseLandscapePosters = input.useLandscapePosters
            cache.continueWatchingShowImdbRatings = input.showImdbRatings
            cache.continueWatchingRow = continueWatchingRow
            add(continueWatchingRow)
        } else {
            cache.continueWatchingItems = emptyList()
            cache.continueWatchingRow = null
            // Nothing to resume: lead with a few featured titles instead (#813). Not on a kids Home.
            if (!input.kidsHome) {
                buildSpotlightRow(
                    sourceRows = visibleHomeRows.mapNotNull { (it as? HomeRow.Catalog)?.row },
                    input = input,
                    title = localizedContext.getString(R.string.home_row_spotlight),
                    strTypeMovie = strTypeMovie,
                    strTypeSeries = strTypeSeries
                )?.let(::add)
            }
        }

        // Ready tonight: titles that will start instantly. A single one would only repeat a card
        // from the row above, so the row needs at least two.
        // Watchlist movies follow as catalog cards: OK opens their detail page, where Play
        // names the same ready pick.
        if (input.readyTonightItems.size + input.readyWatchlistMovies.size >= READY_TONIGHT_MIN_ITEMS) {
            val continueWatchingCards = input.readyTonightItems.map { item ->
                buildContinueWatchingItem(
                    item = item,
                    useLandscapePosters = input.useLandscapePosters,
                    detailImdbRatingsVisibility = input.detailImdbRatingsVisibility,
                    airsDateTemplate = strAirsDate,
                    upcomingLabel = strUpcoming,
                    context = localizedContext
                )
            }
            val watchlistCards = input.readyWatchlistMovies.map { entry ->
                buildCatalogItem(
                    item = entry.toMetaPreview(),
                    row = readyWatchlistRow(entry),
                    useLandscapePosters = input.useLandscapePosters,
                    occurrence = 0,
                    strTypeMovie = strTypeMovie,
                    strTypeSeries = strTypeSeries,
                    showFullReleaseDate = input.showFullReleaseDate
                )
            }
            add(
                HomeRowModel(
                    key = MODERN_READY_TONIGHT_ROW_KEY,
                    title = localizedContext.getString(R.string.home_row_ready_tonight),
                    subtitle = localizedContext.getString(R.string.home_row_ready_tonight_explainer),
                    globalRowIndex = -1,
                    items = (continueWatchingCards + watchlistCards).asStable()
                )
            )
        }

        // Upcoming row (SPLIT_UPCOMING mode)
        val strUpcomingSectionTitle = localizedContext.getString(R.string.upcoming_section_title)
        if (input.upcomingItems.isNotEmpty()) {
            val reuseUpcomingRow =
                cache.upcomingRow != null &&
                    cache.upcomingItems == input.upcomingItems &&
                    cache.upcomingTitle == strUpcomingSectionTitle &&
                    cache.upcomingUseLandscapePosters == input.useLandscapePosters &&
                    cache.upcomingShowImdbRatings == input.showImdbRatings
            val upcomingRow = if (reuseUpcomingRow) {
                checkNotNull(cache.upcomingRow)
            } else {
                HomeRowModel(
                    key = MODERN_UPCOMING_ROW_KEY,
                    title = strUpcomingSectionTitle,
                    globalRowIndex = -1,
                    items = input.upcomingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            detailImdbRatingsVisibility = input.detailImdbRatingsVisibility,
                            airsDateTemplate = strAirsDate,
                            upcomingLabel = strUpcoming,
                            context = localizedContext
                        )
                    }.asStable()
                )
            }
            cache.upcomingItems = input.upcomingItems
            cache.upcomingTitle = strUpcomingSectionTitle
            cache.upcomingUseLandscapePosters = input.useLandscapePosters
            cache.upcomingShowImdbRatings = input.showImdbRatings
            cache.upcomingRow = upcomingRow
            add(upcomingRow)
        } else {
            cache.upcomingItems = emptyList()
            cache.upcomingRow = null
        }

        // A kids Home never shows it, whatever the loader sent.
        if (input.youTubeVideos.isNotEmpty() && !input.kidsHome) {
            add(
                HomeRowModel(
                    key = MODERN_YOUTUBE_ROW_KEY,
                    title = localizedContext.getString(R.string.youtube_home_row_label),
                    globalRowIndex = -1,
                    items = input.youTubeVideos.map(::buildYouTubeVideoItem).asStable()
                )
            )
        }

        visibleHomeRows.forEachIndexed { index, homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val row = homeRow.row
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    renderedCatalogRows++
                    val rowKey = catalogRowKey(row)
                    activeCatalogKeys += rowKey
                    val cached = cache.catalogRows[rowKey]
                    val currentLocaleTag = LocaleCache.localeTag
                    val canReuseMappedRow =
                        cached != null &&
                            cached.source == row &&
                            cached.useLandscapePosters == input.useLandscapePosters &&
                            cached.showCatalogTypeSuffix == input.showCatalogTypeSuffix &&
                            cached.showImdbRatings == input.showImdbRatings &&
                            cached.localeTag == currentLocaleTag

                    val mappedRow = if (canReuseMappedRow) {
                        val cachedMappedRow = checkNotNull(cached).mappedRow
                        if (cachedMappedRow.globalRowIndex == index) {
                            cachedMappedRow
                        } else {
                            cachedMappedRow.copy(globalRowIndex = index)
                        }
                    } else {
                        val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                        val rowItemCache = cache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                        HomeRowModel(
                            key = rowKey,
                            title = catalogRowTitle(
                                row = row,
                                showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                                strTypeMovie = strTypeMovie,
                                strTypeSeries = strTypeSeries
                            ),
                            globalRowIndex = index,
                            catalogId = row.catalogId,
                            addonId = row.addonId,
                            apiType = row.apiType,
                            supportsSkip = row.supportsSkip,
                            hasMore = row.hasMore,
                            isLoading = row.isLoading,
                            items = row.items.mapIndexed { itemIndex, item ->
                                val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                                rowItemOccurrenceCounts[item.id] = occurrence + 1
                                val cacheKey = "${item.id}_$occurrence"
                                val cachedItem = rowItemCache[cacheKey]
                                if (cachedItem != null &&
                                    cachedItem.source == item &&
                                    cachedItem.useLandscapePosters == input.useLandscapePosters &&
                                    cachedItem.showFullReleaseDate == input.showFullReleaseDate &&
                                    cachedItem.showImdbRatings == input.showImdbRatings
                                ) {
                                    cachedItem.carouselItem.let { cached ->
                                        val stableItemKey = row.stableItemKey(item, occurrence)
                                        if (cached.key == stableItemKey) cached
                                        else cached.copy(key = stableItemKey)
                                    }
                                } else {
                                    val built = buildCatalogItem(
                                        item = item,
                                        row = row,
                                        useLandscapePosters = input.useLandscapePosters,
                                        occurrence = occurrence,
                                        strTypeMovie = strTypeMovie,
                                        strTypeSeries = strTypeSeries,
                                        showFullReleaseDate = input.showFullReleaseDate,
                                        previousCachedItem = cachedItem?.carouselItem
                                    ).copy(key = row.stableItemKey(item, occurrence))
                                    rowItemCache[cacheKey] = CachedCarouselItem(
                                        source = item,
                                        useLandscapePosters = input.useLandscapePosters,
                                        showFullReleaseDate = input.showFullReleaseDate,
                                        showImdbRatings = input.showImdbRatings,
                                        carouselItem = built
                                    )
                                    built
                                }
                            }.asStable()
                        )
                    }

                    cache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                        source = row,
                        useLandscapePosters = input.useLandscapePosters,
                        showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                        showImdbRatings = input.showImdbRatings,
                        localeTag = currentLocaleTag,
                        mappedRow = mappedRow
                    )
                    add(mappedRow)
                }

                is HomeRow.CollectionRow -> {
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    val collection = homeRow.collection
                    val rowKey = collectionRowKey(collection)
                    activeCollectionKeys += rowKey
                    val cached = cache.collectionRows[rowKey]
                    val canReuseMappedRow =
                        cached != null &&
                            cached.source == collection

                    val mappedRow = if (canReuseMappedRow) {
                        val cachedMappedRow = checkNotNull(cached).mappedRow
                        if (cachedMappedRow.globalRowIndex == index) {
                            cachedMappedRow
                        } else {
                            cachedMappedRow.copy(globalRowIndex = index)
                        }
                    } else {
                        val occurrenceCounts = mutableMapOf<String, Int>()
                        HomeRowModel(
                            key = rowKey,
                            title = collection.title,
                            globalRowIndex = index,
                            items = collection.folders.map { folder ->
                                val occurrence = occurrenceCounts.getOrDefault(folder.id, 0)
                                occurrenceCounts[folder.id] = occurrence + 1
                                buildCollectionFolderItem(
                                    collection = collection,
                                    folder = folder,
                                    occurrence = occurrence
                                )
                            }.asStable()
                        )
                    }

                    cache.collectionRows[rowKey] = ModernCollectionRowBuildCacheEntry(
                        source = collection,
                        mappedRow = mappedRow
                    )
                    add(mappedRow)
                }

                is HomeRow.PlaceholderCatalog -> {
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    renderedCatalogRows++
                    val stableRowKey = homeRow.stableCatalogKey
                    val fakeItemCount = 8
                    val fakeItems = (0 until fakeItemCount).map { i ->
                        val fakeId = "__placeholder_${homeRow.catalogKey}_$i"
                        ModernCarouselItem(
                            key = "${stableRowKey}_$i",
                            title = "",
                            subtitle = null,
                            // Dummy URL triggers shimmer instead of MonochromePosterPlaceholder
                            imageUrl = PLACEHOLDER_IMAGE_URL,
                            heroPreview = HeroPreview(
                                title = "", logo = null, description = null,
                                contentTypeText = null, yearText = null, imdbText = null,
                                genres = com.fetch.tv.ui.util.StableList(emptyList()), poster = null, backdrop = null,
                                imageUrl = PLACEHOLDER_IMAGE_URL
                            ),
                            payload = ModernPayload.Catalog(
                                focusKey = "placeholder_${homeRow.catalogKey}_$i",
                                itemId = fakeId,
                                itemType = homeRow.apiType,
                                addonBaseUrl = homeRow.addonBaseUrl,
                                trailerTitle = "",
                                trailerReleaseInfo = null,
                                trailerApiType = homeRow.apiType
                            )
                        )
                    }.asStable()
                    val placeholderTitle = if (input.showCatalogTypeSuffix) {
                        homeRow.displayTitle
                    } else {
                        homeRow.catalogName.replaceFirstChar { it.uppercase() }
                    }
                    val placeholderRow = HomeRowModel(
                        key = stableRowKey,
                        title = placeholderTitle,
                        globalRowIndex = index,
                        catalogId = homeRow.catalogId,
                        addonId = homeRow.addonId,
                        apiType = homeRow.apiType,
                        items = fakeItems,
                        isLoading = true
                    )
                    add(placeholderRow)
                }
            }
        }

        cache.catalogRows.keys.retainAll(activeCatalogKeys)
        cache.catalogItemCache.keys.retainAll(activeCatalogKeys)
        cache.collectionRows.keys.retainAll(activeCollectionKeys)
    }

    val lookups = buildCarouselRowLookups(rows)
    return ModernHomePresentationState(
        rows = rows.asStable(),
        lookups = lookups,
        contentFilter = contentFilter,
        contentFilters = contentFilters.asStable()
    )
}

/**
 * The Spotlight row: a normal row of wide cards, so focus and the hero treat it like any other.
 * Its cards get keys of their own, so they never share a focus or expansion key with the same
 * title in the row it came from. Null with too few titles to feature.
 */
private fun buildSpotlightRow(
    sourceRows: List<CatalogRow>,
    input: ModernHomePresentationInput,
    title: String,
    strTypeMovie: String,
    strTypeSeries: String
): HomeRowModel? {
    val picks = pickSpotlight(
        rows = sourceRows,
        isWatched = { item -> input.watchedStatus[homeItemStatusKey(item.id, item.apiType)] == true },
        day = input.spotlightDay
    )
    if (picks.size < SPOTLIGHT_MIN_ITEMS) return null
    val items = picks.map { pick ->
        val built = buildCatalogItem(
            item = pick.item,
            row = pick.row,
            useLandscapePosters = true,
            occurrence = 0,
            strTypeMovie = strTypeMovie,
            strTypeSeries = strTypeSeries,
            showFullReleaseDate = input.showFullReleaseDate
        )
        val identity = "${pick.item.apiType}:${pick.item.id}"
        val payload = built.payload as ModernPayload.Catalog
        built.copy(
            key = "${MODERN_SPOTLIGHT_ROW_KEY}_$identity",
            payload = payload.copy(focusKey = "${MODERN_SPOTLIGHT_ROW_KEY}::$identity")
        )
    }
    return HomeRowModel(
        key = MODERN_SPOTLIGHT_ROW_KEY,
        title = title,
        globalRowIndex = -1,
        items = items.asStable()
    )
}

/** Fewer than this many titles is not much of a spotlight; the row is left out. */
private const val SPOTLIGHT_MIN_ITEMS = 3

private fun resolveVisibleHomeRows(input: ModernHomePresentationInput): List<HomeRow> {
    val rows = resolveConfiguredHomeRows(input)
    if (input.kidsHome) return rows.take(KIDS_HOME_MAX_ROWS)
    val recommendationRows = input.becauseYouWatchedRows
        .filter { it.items.isNotEmpty() }
        .map(HomeRow::Catalog)
    if (recommendationRows.isEmpty()) return rows
    val pinnedCount = rows.indexOfFirst { row ->
        !(row is HomeRow.CollectionRow && row.collection.pinToTop)
    }.let { if (it < 0) rows.size else it }
    return rows.take(pinnedCount) + recommendationRows + rows.drop(pinnedCount)
}

private fun resolveConfiguredHomeRows(input: ModernHomePresentationInput): List<HomeRow> {
    if (input.homeRows.isNotEmpty()) {
        val latestCatalogByKey = input.catalogRows.associateBy(::catalogRowKey)
        return input.homeRows.mapNotNull { homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val latest = latestCatalogByKey[catalogRowKey(homeRow.row)] ?: homeRow.row
                    latest.takeIf { it.items.isNotEmpty() }?.let(HomeRow::Catalog)
                }
                is HomeRow.CollectionRow -> {
                    homeRow.collection.takeIf(Collection::hasVisibleFolders)?.let(HomeRow::CollectionRow)
                }
                is HomeRow.PlaceholderCatalog -> {
                    // Keep placeholder rows as-is — they'll be rendered as shimmer skeletons
                    homeRow
                }
            }
        }
    }

    return input.catalogRows
        .filter { it.items.isNotEmpty() }
        .map(HomeRow::Catalog)
}

private fun collectionRowKey(collection: Collection): String {
    return "collection_${collection.id}"
}

private fun Collection.hasVisibleFolders(): Boolean {
    return folders.isNotEmpty()
}

internal fun buildCarouselRowLookups(carouselRows: List<HomeRowModel>): CarouselRowLookups {
    val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
    val rowByKey = LinkedHashMap<String, HomeRowModel>(carouselRows.size)
    val rowKeyByGlobalRowIndex = LinkedHashMap<Int, String>(carouselRows.size)
    val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
    val itemIdentitiesByRow = LinkedHashMap<String, StableList<String>>(carouselRows.size)
    val activeCatalogItemIds = LinkedHashSet<String>()

    carouselRows.forEachIndexed { index, row ->
        rowIndexByKey[row.key] = index
        rowByKey[row.key] = row
        if (row.globalRowIndex >= 0) {
            rowKeyByGlobalRowIndex[row.globalRowIndex] = row.key
        }
        activeRowKeys += row.key

        val itemIdentities = ArrayList<String>(row.items.list.size)
        row.items.list.forEach { item ->
            when (val payload = item.payload) {
                is ModernPayload.Catalog -> {
                    itemIdentities += "${payload.itemType}:${payload.itemId}"
                    activeCatalogItemIds += payload.itemId
                }
                is ModernPayload.CollectionFolder -> {
                    itemIdentities += "folder:${payload.folderId}"
                }
                is ModernPayload.ContinueWatching -> {
                    itemIdentities += "cw:${payload.item.hashCode()}"
                }
                is ModernPayload.YouTubeVideo -> {
                    itemIdentities += "youtube:${payload.video.videoId}"
                }
            }
        }
        itemIdentitiesByRow[row.key] = itemIdentities.asStable()
    }

    return CarouselRowLookups(
        rowIndexByKey = rowIndexByKey.asStable(),
        rowByKey = rowByKey.asStable(),
        rowKeyByGlobalRowIndex = rowKeyByGlobalRowIndex.asStable(),
        activeRowKeys = activeRowKeys.asStable(),
        itemIdentitiesByRow = itemIdentitiesByRow.asStable(),
        activeCatalogItemIds = activeCatalogItemIds.asStable()
    )
}

/** A stand-in catalog row so a ready watchlist movie opens its detail page through its own addon. */
private fun readyWatchlistRow(entry: com.fetch.tv.domain.model.LibraryEntry): CatalogRow = CatalogRow(
    addonId = MODERN_READY_TONIGHT_ROW_KEY,
    addonName = "",
    addonBaseUrl = entry.addonBaseUrl.orEmpty(),
    catalogId = "watchlist",
    catalogName = "",
    type = com.fetch.tv.domain.model.ContentType.MOVIE,
    items = emptyList()
)
