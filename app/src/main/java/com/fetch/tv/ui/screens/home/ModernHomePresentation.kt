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
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val showFullReleaseDate: Boolean,
    val showImdbRatings: Boolean,
    val detailImdbRatingsVisibility: com.fetch.tv.domain.model.DetailImdbRatingsVisibility =
        com.fetch.tv.domain.model.DetailImdbRatingsVisibility.SHOW_ALL,
    val localeTag: String
)

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache,
    context: Context,
    maxCatalogRows: Int? = null
): ModernHomePresentationState {
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
                HeroCarouselRow(
                    key = MODERN_CONTINUE_WATCHING_ROW_KEY,
                    title = strContinueWatching,
                    globalRowIndex = -1,
                    items = input.continueWatchingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            showImdbRatings = input.showImdbRatings,
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
                    showImdbRatings = input.showImdbRatings,
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
                    showFullReleaseDate = input.showFullReleaseDate,
                    showImdbRatings = input.showImdbRatings
                )
            }
            add(
                HeroCarouselRow(
                    key = MODERN_READY_TONIGHT_ROW_KEY,
                    title = localizedContext.getString(R.string.home_row_ready_tonight),
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
                HeroCarouselRow(
                    key = MODERN_UPCOMING_ROW_KEY,
                    title = strUpcomingSectionTitle,
                    globalRowIndex = -1,
                    items = input.upcomingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            showImdbRatings = input.showImdbRatings,
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
                        HeroCarouselRow(
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
                                        showImdbRatings = input.showImdbRatings,
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
                        HeroCarouselRow(
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
                    val placeholderRow = HeroCarouselRow(
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
        lookups = lookups
    )
}

private fun resolveVisibleHomeRows(input: ModernHomePresentationInput): List<HomeRow> {
    val rows = resolveConfiguredHomeRows(input)
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

internal fun buildCarouselRowLookups(carouselRows: List<HeroCarouselRow>): CarouselRowLookups {
    val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
    val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
    val rowKeyByGlobalRowIndex = LinkedHashMap<Int, String>(carouselRows.size)
    val firstHeroPreviewByRow = LinkedHashMap<String, HeroPreview>(carouselRows.size)
    val fallbackBackdropByRow = LinkedHashMap<String, String>(carouselRows.size)
    val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
    val activeItemKeysByRow = LinkedHashMap<String, Set<String>>(carouselRows.size)
    val itemIdentitiesByRow = LinkedHashMap<String, StableList<String>>(carouselRows.size)
    val activeCatalogItemIds = LinkedHashSet<String>()

    carouselRows.forEachIndexed { index, row ->
        rowIndexByKey[row.key] = index
        rowByKey[row.key] = row
        if (row.globalRowIndex >= 0) {
            rowKeyByGlobalRowIndex[row.globalRowIndex] = row.key
        }
        row.items.list.firstOrNull()?.heroPreview?.let { firstHeroPreviewByRow[row.key] = it }
        row.items.list.firstNotNullOfOrNull { item ->
            item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
        }?.let { fallbackBackdropByRow[row.key] = it }
        activeRowKeys += row.key

        val itemKeys = LinkedHashSet<String>(row.items.list.size)
        val itemIdentities = ArrayList<String>(row.items.list.size)
        row.items.list.forEach { item ->
            itemKeys.add(item.key)
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
            }
        }
        activeItemKeysByRow[row.key] = itemKeys
        itemIdentitiesByRow[row.key] = itemIdentities.asStable()
    }

    return CarouselRowLookups(
        rowIndexByKey = rowIndexByKey.asStable(),
        rowByKey = rowByKey.asStable(),
        rowKeyByGlobalRowIndex = rowKeyByGlobalRowIndex.asStable(),
        firstHeroPreviewByRow = firstHeroPreviewByRow.asStable(),
        fallbackBackdropByRow = fallbackBackdropByRow.asStable(),
        activeRowKeys = activeRowKeys.asStable(),
        activeItemKeysByRow = activeItemKeysByRow.asStable(),
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
