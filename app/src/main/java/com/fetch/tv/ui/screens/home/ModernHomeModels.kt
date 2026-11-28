package com.fetch.tv.ui.screens.home

import com.fetch.tv.ui.components.hero.HeroPreview

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fetch.tv.core.recommendations.BECAUSE_YOU_WATCHED_ADDON_ID
import com.fetch.tv.domain.model.catalogRowLegacyKey
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.stableKey
import com.fetch.tv.ui.util.localizeEpisodeTitle
import com.fetch.tv.ui.util.isPlaceholderEpisodeTitle
import com.fetch.tv.ui.util.localizedContentType
import com.fetch.tv.ui.util.computeAirDateBadgeText
import com.fetch.tv.ui.util.formatHeroRuntime
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.R
import com.fetch.tv.ui.components.formatContinueWatchingProgressLabel
import com.fetch.tv.ui.util.StableList
import com.fetch.tv.ui.util.StableMap
import com.fetch.tv.ui.util.StableSet
import com.fetch.tv.ui.util.asStable
import java.util.Locale
import com.fetch.tv.ui.util.YEAR_REGEX
import com.fetch.tv.ui.util.firstNonBlank

internal const val MODERN_HERO_TEXT_WIDTH_FRACTION = 0.42f
internal const val MODERN_HERO_MEDIA_WIDTH_FRACTION = 0.72f
internal const val MODERN_TRAILER_OVERSCAN_ZOOM = 1.35f
/**
 * How long focus must rest before the hero art follows it. The title and metadata follow at once;
 * only the backdrop waits, so holding the D-pad doesn't crossfade through every card.
 */
internal const val MODERN_HERO_FOCUS_DEBOUNCE_MS = 200L
internal val MODERN_ROW_HEADER_FOCUS_INSET = 40.dp
/** Space under each row title before its cards (ModernRowSection's rowTitleBottom). */
internal val MODERN_ROW_TITLE_BOTTOM = 14.dp
/** Room for a focused card's scale and ring, so the second row on entry isn't clipped. */
internal val MODERN_ROW_FOCUS_ALLOWANCE = 8.dp
/** The rows never take more than this share of the screen, so the hero keeps its title block. */
internal const val MODERN_ROWS_VIEWPORT_MAX_FRACTION = 0.66f
internal const val MODERN_CONTINUE_WATCHING_ROW_KEY = "continue_watching"
internal const val MODERN_UPCOMING_ROW_KEY = "upcoming_section"
internal const val MODERN_READY_TONIGHT_ROW_KEY = "ready_tonight"
/** Featured titles that lead Home when there is nothing to resume (#813). */
internal const val MODERN_SPOTLIGHT_ROW_KEY = "spotlight"
internal const val MODERN_YOUTUBE_ROW_KEY = "youtube_subscriptions"

/** Rows drawn with Continue Watching cards rather than catalog cards. */
internal fun isContinueWatchingStyleRow(key: String): Boolean =
    key == MODERN_CONTINUE_WATCHING_ROW_KEY || key == MODERN_UPCOMING_ROW_KEY || key == MODERN_READY_TONIGHT_ROW_KEY
internal val MODERN_LANDSCAPE_LOGO_GRADIENT = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.58f to Color.Transparent,
        1.0f to Color.Black.copy(alpha = 0.75f)
    )
)

/**
 * What the hero shows for the focused card, for TalkBack: the card reads it after its own title so
 * the year, rating and runtime drawn above the rows are announced with the card. [itemKey] is the
 * [ModernCarouselItem.key] the hero is showing, so only that card picks it up.
 */
@Immutable
internal data class HeroCardAccessibility(
    val itemKey: String,
    val details: String
)

internal val LocalHeroCardAccessibility =
    compositionLocalOf<State<HeroCardAccessibility?>> { mutableStateOf(null) }

/** "2024, PG-13, IMDb 7.8, 2h 10m": the hero's year, ratings and runtime, or null with none. */
internal fun heroAccessibilityDetails(
    preview: HeroPreview,
    showImdbRating: Boolean,
    imdbRatingTemplate: String
): String? = listOfNotNull(
    preview.yearText,
    preview.ageRatingText,
    preview.imdbText?.takeIf { showImdbRating && it.isNotBlank() }?.let { imdbRatingTemplate.format(it) },
    preview.runtimeText
).map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(", ")

@Immutable
sealed class ModernPayload {
    data class ContinueWatching(val item: ContinueWatchingItem) : ModernPayload()
    data class Catalog(
        val focusKey: String,
        val itemId: String,
        val itemType: String,
        val addonBaseUrl: String,
        val trailerTitle: String,
        val trailerReleaseInfo: String?,
        val trailerApiType: String
    ) : ModernPayload()
    data class CollectionFolder(
        val focusKey: String,
        val collectionId: String,
        val folderId: String,
        val posterShape: PosterShape,
        val focusGifEnabled: Boolean,
        val focusGifUrl: String?,
        val heroBackdropUrl: String?,
        val heroVideoUrl: String?,
        val titleLogoUrl: String?,
        val coverEmoji: String? = null
    ) : ModernPayload()
    /** A video from the profile's YouTube subscriptions; OK plays it on the YouTube screen. */
    data class YouTubeVideo(
        val focusKey: String,
        val video: com.fetch.tv.data.youtube.YouTubeVideo
    ) : ModernPayload()
}

@Immutable
internal data class FocusedCatalogSelection(
    val focusKey: String,
    val payload: ModernPayload
)

@Immutable
data class ModernCarouselItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload,
    val metaPreview: MetaPreview? = null
)

/** One row of Home: Continue Watching, a catalog, a collection, YouTube or Spotlight. */
@Immutable
data class HomeRowModel(
    val key: String,
    val title: String,
    val globalRowIndex: Int,
    val items: StableList<ModernCarouselItem>,
    val catalogId: String? = null,
    val addonId: String? = null,
    val apiType: String? = null,
    val supportsSkip: Boolean = false,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    /** A short explanation shown after the title, on the same line. */
    val subtitle: String? = null
)

/** Home order key of a catalog row, or null for rows that are not catalogs. */
internal fun HomeRowModel.orderKey(): String? {
    val addonId = addonId?.takeIf { it != BECAUSE_YOU_WATCHED_ADDON_ID } ?: return null
    val catalogId = catalogId ?: return null
    val apiType = apiType ?: return null
    return catalogRowLegacyKey(addonId, apiType, catalogId)
}

@Immutable
data class CarouselRowLookups(
    val rowIndexByKey: StableMap<String, Int>,
    val rowByKey: StableMap<String, HomeRowModel>,
    val rowKeyByGlobalRowIndex: StableMap<Int, String>,
    val activeRowKeys: StableSet<String>,
    val itemIdentitiesByRow: StableMap<String, StableList<String>>,
    val activeCatalogItemIds: StableSet<String>
)

@Immutable
data class ModernHomePresentationState(
    val rows: StableList<HomeRowModel> = StableList(),
    val lookups: CarouselRowLookups = CarouselRowLookups(
        rowIndexByKey = StableMap(),
        rowByKey = StableMap(),
        rowKeyByGlobalRowIndex = StableMap(),
        activeRowKeys = StableSet(),
        itemIdentitiesByRow = StableMap(),
        activeCatalogItemIds = StableSet()
    ),
    /** The Movies / Shows filter in effect (#814): All when the saved one has nothing to show. */
    val contentFilter: HomeContentFilter = HomeContentFilter.ALL,
    /** The filters offered above the rows; empty hides the filter (#814). */
    val contentFilters: StableList<HomeContentFilter> = StableList()
)

@Immutable
internal data class ModernHeroSceneState(
    val heroBackdrop: String?,
    val preview: HeroPreview?,
    val enrichmentActive: Boolean,
    val shouldPlayTrailer: Boolean,
    val trailerFirstFrameRendered: Boolean,
    val trailerUrl: String?,
    val trailerAudioUrl: String?,
    val trailerPlaybackKey: String?,
    val trailerMuted: Boolean,
    val fullScreenBackdrop: Boolean
)

internal data class ModernCatalogRowBuildCacheEntry(
    val source: CatalogRow,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val showImdbRatings: Boolean,
    val localeTag: String,
    val mappedRow: HomeRowModel
)

internal data class ModernCollectionRowBuildCacheEntry(
    val source: Collection,
    val mappedRow: HomeRowModel
)
@Stable
class ModernCarouselRowBuildCache {
    var continueWatchingItems: List<ContinueWatchingItem> = emptyList()
    var continueWatchingTitle: String = ""
    var continueWatchingAirsDateTemplate: String = ""
    var continueWatchingUpcomingLabel: String = ""
    var continueWatchingUseLandscapePosters: Boolean = false
    var continueWatchingShowImdbRatings: Boolean = true
    var continueWatchingRow: HomeRowModel? = null
    var upcomingItems: List<ContinueWatchingItem> = emptyList()
    var upcomingTitle: String = ""
    var upcomingUseLandscapePosters: Boolean = false
    var upcomingShowImdbRatings: Boolean = true
    var upcomingRow: HomeRowModel? = null
    internal val catalogRows = java.util.concurrent.ConcurrentHashMap<String, ModernCatalogRowBuildCacheEntry>()
    internal val collectionRows = java.util.concurrent.ConcurrentHashMap<String, ModernCollectionRowBuildCacheEntry>()
    // per-item cache: rowKey -> (itemId -> cached carousel item + source MetaPreview)
    internal val catalogItemCache = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, CachedCarouselItem>>()
}

internal data class CachedCarouselItem(
    val source: MetaPreview,
    val useLandscapePosters: Boolean,
    val showFullReleaseDate: Boolean,
    val showImdbRatings: Boolean,
    val carouselItem: ModernCarouselItem
)

@Immutable
internal data class ModernCatalogCardMetrics(
    val width: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp
)

internal fun ModernCarouselItem.catalogCardMetrics(
    useLandscapePosters: Boolean,
    portraitCardWidth: androidx.compose.ui.unit.Dp,
    portraitCardHeight: androidx.compose.ui.unit.Dp,
    landscapeCardWidth: androidx.compose.ui.unit.Dp,
    landscapeCardHeight: androidx.compose.ui.unit.Dp
): ModernCatalogCardMetrics {
    // Collection folders define their own tile shape — never override with
    // the global landscape-posters toggle.
    // YouTube thumbnails are 16:9, so their cards stay landscape whatever the poster setting.
    if ((useLandscapePosters && payload !is ModernPayload.CollectionFolder) || payload is ModernPayload.YouTubeVideo) {
        return ModernCatalogCardMetrics(
            width = landscapeCardWidth,
            height = landscapeCardHeight
        )
    }

    val posterShape = when (payload) {
        is ModernPayload.Catalog -> metaPreview?.posterShape ?: PosterShape.POSTER
        is ModernPayload.CollectionFolder -> payload.posterShape
        is ModernPayload.ContinueWatching -> PosterShape.POSTER
        is ModernPayload.YouTubeVideo -> PosterShape.LANDSCAPE
    }

    return when (posterShape) {
        PosterShape.LANDSCAPE -> ModernCatalogCardMetrics(
            width = landscapeCardWidth,
            height = landscapeCardHeight
        )

        PosterShape.SQUARE -> ModernCatalogCardMetrics(
            width = portraitCardHeight,
            height = portraitCardHeight
        )

        PosterShape.POSTER -> ModernCatalogCardMetrics(
            width = portraitCardWidth,
            height = portraitCardHeight
        )
    }
}

/** Memory cache key for a catalog card's artwork; prefetches use it too so the card finds their bitmap. */
internal fun catalogCardImageCacheKey(url: String, widthPx: Int, heightPx: Int, revalidationKey: Int = 0): String =
    "${url}_${widthPx}x${heightPx}_v$revalidationKey"

/** Cache key extras for a catalog card's artwork, carrying the URL to fall back to if [imageUrl] fails. */
internal fun ModernCarouselItem.catalogCardImageCacheExtras(
    imageUrl: String,
    isLandscapeCustomPoster: Boolean
): Map<String, String> {
    val fallbackUrl = if (isLandscapeCustomPoster) {
        // Landscape custom poster -> fall back to original backdrop
        metaPreview?.background ?: heroPreview.backdrop ?: metaPreview?.rawPosterUrl
    } else {
        metaPreview?.rawPosterUrl
    }
    return if (!fallbackUrl.isNullOrBlank() && fallbackUrl != imageUrl) {
        mapOf(com.fetch.tv.core.image.CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to fallbackUrl)
    } else {
        emptyMap()
    }
}


internal fun buildContinueWatchingItem(
    item: ContinueWatchingItem,
    useLandscapePosters: Boolean,
    detailImdbRatingsVisibility: com.fetch.tv.domain.model.DetailImdbRatingsVisibility =
        com.fetch.tv.domain.model.DetailImdbRatingsVisibility.SHOW_ALL,
    airsDateTemplate: String,
    upcomingLabel: String,
    context: android.content.Context
): ModernCarouselItem {
    val secondaryHighlightText = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val progress = item.progress
            formatContinueWatchingProgressLabel(
                progress = progress,
                resumeLabel = context.getString(R.string.cw_resume),
                percentWatchedLabel = context.getString(R.string.cw_percent_watched),
                hoursMinLeftLabel = context.getString(R.string.cw_hours_min_left),
                minLeftLabel = context.getString(R.string.cw_min_left)
            )
        }
        is ContinueWatchingItem.NextUp -> {
            if (item.info.isReleaseAlert) {
                if (item.info.isNewSeasonRelease) {
                    context.getString(R.string.cw_new_season)
                } else {
                    context.getString(R.string.cw_new_episode)
                }
            } else if (!item.info.hasAired) {
                computeAirDateBadgeText(context, item.info.released, item.info.airDateLabel)
                    ?: context.getString(R.string.cw_upcoming)
            } else {
                context.getString(R.string.cw_next_up)
            }
        }
    }.uppercase(context.resources.configuration.locales[0])

    val heroPreview = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val isSeries = isSeriesType(item.progress.contentType)
            val s = item.progress.season
            val e = item.progress.episode
            val episodeCode = if (s != null && e != null) {
                context.getString(R.string.season_episode_format, s, e)
            } else {
                null
            }
            // "Episode 5" only repeats the code, and the title is already in the label line.
            val episodeTitle = item.progress.episodeTitle
                ?.takeIf { it.isNotBlank() && !it.isPlaceholderEpisodeTitle() }
                ?.localizeEpisodeTitle(context)
            val episodeLabel = when {
                isSeries && episodeCode != null && episodeTitle != null -> "$episodeCode · $episodeTitle"
                isSeries && episodeCode != null -> episodeCode
                isSeries && episodeTitle != null -> episodeTitle
                else -> localizedContentType(context, item.progress.contentType)
            }
            // The episode title gets its own line under the logo, next to the Resume line.
            val resumeEpisodeTitle = episodeTitle?.takeIf { isSeries }
            val resumeLabel = context.getString(R.string.cw_resume)
            val timeLeft = formatContinueWatchingProgressLabel(
                progress = item.progress,
                resumeLabel = resumeLabel,
                percentWatchedLabel = context.getString(R.string.cw_percent_watched),
                hoursMinLeftLabel = context.getString(R.string.cw_hours_min_left),
                minLeftLabel = context.getString(R.string.cw_min_left)
            ).takeIf { it != resumeLabel }
            HeroPreview(
                title = item.progress.name,
                logo = item.progress.logo,
                description = item.episodeDescription,
                contentTypeText = if (resumeEpisodeTitle != null) {
                    episodeCode ?: localizedContentType(context, item.progress.contentType)
                } else {
                    episodeLabel
                },
                isSeries = isSeries,
                yearText = extractYearOrRange(item.releaseInfo),
                // The time left moves to the spec line under the Resume button.
                secondaryHighlightText = null,
                resumeActionText = if (isSeries && s != null && e != null) {
                    context.getString(R.string.detail_btn_resume_episode, s, e)
                } else {
                    context.getString(R.string.detail_btn_resume)
                },
                resumeTimeLeftText = timeLeft,
                resumeProgressFraction = item.progress.progressPercentage
                    .takeIf { it > 0f && !item.progress.isCompleted() },
                bestMatchKey = com.fetch.tv.data.local.BestMatchCacheDataStore.contentKey(
                    item.progress.contentType,
                    item.progress.videoId
                ),
                resumeEpisodeTitle = resumeEpisodeTitle,
                imdbText = item.episodeImdbRating
                    ?.takeIf { !isSeries || detailImdbRatingsVisibility.showEpisodeRating(isWatched = item.progress.isCompleted()) }
                    ?.let { String.format(Locale.getDefault(), "%.1f", it) },
                genres = item.genres.asStable(),
                poster = item.progress.poster,
                backdrop = item.progress.backdrop,
                imageUrl = if (useLandscapePosters) {
                    item.customLandscapePoster ?: item.progress.backdrop ?: item.progress.poster
                } else {
                    item.progress.poster ?: item.progress.backdrop
                },
                mdbListRatings = item.mdbListRatings
            )
        }
        is ContinueWatchingItem.NextUp -> {
            val episodeCode = context.getString(
                R.string.season_episode_format,
                item.info.season,
                item.info.episode
            )
            val episodeTitle = item.info.episodeTitle
                ?.takeIf { it.isNotBlank() && !it.isPlaceholderEpisodeTitle() }
                ?.localizeEpisodeTitle(context)
            val episodeLabel = if (episodeTitle != null) "$episodeCode · $episodeTitle" else episodeCode
            // Only an aired episode has a Play line; the episode title sits under the logo beside it.
            val resumeEpisodeTitle = episodeTitle?.takeIf { item.info.hasAired }
            HeroPreview(
                title = item.info.name,
                logo = item.info.logo,
                description = item.info.episodeDescription
                    ?: item.info.airDateLabel?.let { airsDateTemplate.format(it) },
                contentTypeText = if (resumeEpisodeTitle != null) episodeCode else episodeLabel,
                isSeries = true,
                yearText = extractYearOrRange(item.info.releaseInfo),
                // Next Up, New episode or the air date says why the show is here, so it leads.
                eyebrowText = secondaryHighlightText,
                resumeActionText = if (item.info.hasAired) {
                    context.getString(R.string.detail_btn_play_episode, item.info.season, item.info.episode)
                } else {
                    null
                },
                bestMatchKey = if (item.info.hasAired) {
                    com.fetch.tv.data.local.BestMatchCacheDataStore.contentKey(item.info.contentType, item.info.videoId)
                } else {
                    null
                },
                resumeEpisodeTitle = resumeEpisodeTitle,
                imdbText = item.info.imdbRating
                    ?.takeIf { detailImdbRatingsVisibility.showEpisodeRating(isWatched = false) }
                    ?.let { String.format(Locale.getDefault(), "%.1f", it) },
                genres = item.info.genres.asStable(),
                poster = item.info.poster,
                backdrop = item.info.backdrop,
                imageUrl = if (useLandscapePosters) {
                    item.customLandscapePoster ?: firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
                } else {
                    firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
                },
                mdbListRatings = item.info.mdbListRatings
            )
        }
    }

    val imageUrl = when (item) {
        is ContinueWatchingItem.InProgress -> if (useLandscapePosters) {
            item.customLandscapePoster ?: if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(item.episodeThumbnail, item.progress.poster, item.progress.backdrop)
            } else {
                firstNonBlank(item.progress.backdrop, item.progress.poster)
            }
        } else {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(heroPreview.poster, item.progress.poster, item.progress.backdrop)
            } else {
                firstNonBlank(item.progress.poster, item.progress.backdrop)
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            item.customLandscapePoster ?: if (item.info.hasAired) {
                firstNonBlank(item.info.thumbnail, item.info.poster, item.info.backdrop)
            } else {
                firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
            }
        } else {
            firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
        }
    }

    return ModernCarouselItem(
        key = continueWatchingItemKey(item),
        title = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.name
            is ContinueWatchingItem.NextUp -> item.info.name
        },
        subtitle = when (item) {
            is ContinueWatchingItem.InProgress -> {
                val ps = item.progress.season
                val pe = item.progress.episode
                if (ps != null && pe != null) {
                    context.getString(R.string.season_episode_format, ps, pe)
                } else {
                    item.progress.episodeTitle
                }
            }
            is ContinueWatchingItem.NextUp -> {
                val code = context.getString(
                    R.string.season_episode_format,
                    item.info.season,
                    item.info.episode
                )
                if (item.info.hasAired) {
                    code
                } else {
                    item.info.airDateLabel?.let { "$code • ${airsDateTemplate.format(it)}" } ?: "$code • $upcomingLabel"
                }
            }
        },
        imageUrl = imageUrl,
        heroPreview = heroPreview.copy(imageUrl = imageUrl ?: heroPreview.imageUrl),
        payload = ModernPayload.ContinueWatching(item)
    )
}

internal fun buildCatalogItem(
    item: MetaPreview,
    row: CatalogRow,
    useLandscapePosters: Boolean,
    occurrence: Int,
    strTypeMovie: String = "",
    strTypeSeries: String = "",
    showFullReleaseDate: Boolean = true,
    previousCachedItem: ModernCarouselItem? = null
): ModernCarouselItem {
    // Carry forward the frozen URLs from the previous cache entry so that
    // TMDB enrichment never changes the image shown on landscape cards,
    // even after the composable remember-state is lost (e.g. navigation).
    val carriedBackdrop = previousCachedItem?.heroPreview?.frozenBackdropUrl
    val carriedLogo = previousCachedItem?.heroPreview?.frozenLogoUrl

    val currentBackdrop = item.backdropUrl
    val currentLogo = item.logo

    // First non-blank value wins and is never replaced.
    val frozenBackdrop = carriedBackdrop?.takeIf { it.isNotBlank() }
        ?: currentBackdrop
    val frozenLogo = carriedLogo?.takeIf { it.isNotBlank() }
        ?: currentLogo

    val heroPreview = HeroPreview(
        title = item.name,
        logo = item.logo,
        description = item.description,
        contentTypeText = when (item.apiType.lowercase()) {
            "movie" -> strTypeMovie.ifBlank { item.apiType.replaceFirstChar { ch -> ch.uppercase() } }
            "series" -> strTypeSeries.ifBlank { item.apiType.replaceFirstChar { ch -> ch.uppercase() } }
            else -> item.apiType.replaceFirstChar { ch -> ch.uppercase() }
        },
        isSeries = isSeriesType(item.apiType),
        yearText = extractYearText(item.type, item.releaseInfo, item.released, showFullReleaseDate),
        runtimeText = formatHeroRuntime(item.runtime),
        imdbText = item.imdbRating
            ?.let { String.format(Locale.getDefault(), "%.1f", it) },
        ageRatingText = item.ageRating,
        statusText = item.status,
        languageText = item.language?.uppercase(),
        genres = item.genres.take(3).asStable(),
        poster = item.poster,
        backdrop = item.backdropUrl,
        imageUrl = if (useLandscapePosters) {
            item.landscapePoster ?: item.backdropUrl ?: item.poster
        } else if (item.posterShape == PosterShape.LANDSCAPE) {
            item.landscapePoster ?: item.poster ?: item.backdropUrl
        } else {
            item.poster ?: item.backdropUrl
        },
        mdbListRatings = item.mdbListRatings,
        mdbListRatingOrder = item.mdbListRatingOrder,
        frozenBackdropUrl = frozenBackdrop,
        frozenLogoUrl = frozenLogo,
        eyebrowText = item.recommendationReason?.takeIf { it.isNotBlank() },
        bestMatchKey = if (item.apiType.equals("movie", ignoreCase = true)) {
            com.fetch.tv.data.local.BestMatchCacheDataStore.contentKey("movie", item.id)
        } else {
            null
        },
        isCatalogTitle = true
    )

    return ModernCarouselItem(
        key = "catalog_${row.key()}_${item.id}_${occurrence}",
        title = item.name,
        subtitle = item.recommendationReason ?: item.releaseInfo,
        imageUrl = if (useLandscapePosters) {
            item.landscapePoster ?: item.backdropUrl ?: item.poster
        } else if (item.posterShape == PosterShape.LANDSCAPE) {
            item.landscapePoster ?: item.poster ?: item.backdropUrl
        } else {
            item.poster ?: item.backdropUrl
        },
        heroPreview = heroPreview,
        payload = ModernPayload.Catalog(
            focusKey = "${row.key()}::${item.id}",
            itemId = item.id,
            itemType = item.apiType,
            addonBaseUrl = row.addonBaseUrl,
            trailerTitle = item.name,
            trailerReleaseInfo = item.releaseInfo,
            trailerApiType = item.apiType
        ),
        metaPreview = item
    )
}

internal fun buildCollectionFolderItem(
    collection: Collection,
    folder: CollectionFolder,
    occurrence: Int = 0
): ModernCarouselItem {
    val title = if (!folder.coverEmoji.isNullOrBlank()) {
        "${folder.coverEmoji}  ${folder.title}"
    } else {
        folder.title
    }
    // Cover image takes priority over emoji. Emoji is only used as fallback
    // when no cover image is available.
    // GIF URL is only used as an animated overlay on focus (when focusGifEnabled is true).
    // Don't use it as a static poster — it would still animate via Coil's GIF decoder.
    val imageUrl = firstNonBlank(folder.coverImageUrl, collection.backdropImageUrl)
    val heroBackdrop = firstNonBlank(folder.heroBackdropUrl, folder.coverImageUrl, collection.backdropImageUrl)

    return ModernCarouselItem(
        key = "collection_${collection.id}_${folder.id}_$occurrence",
        title = if (folder.hideTitle) "" else folder.title,
        subtitle = if (folder.hideTitle) null else collection.title,
        imageUrl = imageUrl,
        heroPreview = HeroPreview(
            title = if (folder.hideTitle) "" else title,
            logo = folder.titleLogoUrl,
            description = null,
            contentTypeText = null,
            yearText = null,
            imdbText = null,
            genres = emptyList<String>().asStable(),
            poster = imageUrl,
            backdrop = heroBackdrop,
            imageUrl = imageUrl,
            eyebrowText = if (folder.hideTitle) null else collection.title.takeIf { it.isNotBlank() }
        ),
        payload = ModernPayload.CollectionFolder(
            focusKey = "collection_${collection.id}::${folder.id}",
            collectionId = collection.id,
            folderId = folder.id,
            posterShape = folder.tileShape,
            focusGifEnabled = folder.focusGifEnabled,
            focusGifUrl = folder.focusGifUrl,
            heroBackdropUrl = folder.heroBackdropUrl,
            heroVideoUrl = folder.heroVideoUrl,
            titleLogoUrl = folder.titleLogoUrl,
            coverEmoji = folder.coverEmoji
        )
    )
}

/** A YouTube subscriptions card. Its thumbnail is both the card art and, on focus, the hero backdrop. */
internal fun buildYouTubeVideoItem(video: com.fetch.tv.data.youtube.YouTubeVideo): ModernCarouselItem {
    val key = "youtube_${video.videoId}"
    val thumbnail = video.thumbnailUrl.takeIf { it.isNotBlank() }
    return ModernCarouselItem(
        key = key,
        title = video.title,
        subtitle = video.channelName,
        imageUrl = thumbnail,
        heroPreview = HeroPreview(
            title = video.title,
            logo = null,
            description = video.detail,
            contentTypeText = video.channelName,
            yearText = null,
            runtimeText = video.duration,
            imdbText = null,
            genres = emptyList<String>().asStable(),
            poster = thumbnail,
            backdrop = thumbnail,
            imageUrl = thumbnail,
            frozenBackdropUrl = thumbnail
        ),
        payload = ModernPayload.YouTubeVideo(focusKey = key, video = video)
    )
}

internal fun continueWatchingItemKey(item: ContinueWatchingItem): String {
    return item.shuffleFocusKey ?: when (item) {
        is ContinueWatchingItem.InProgress ->
            "cw_inprogress_${item.progress.contentId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
        is ContinueWatchingItem.NextUp ->
            "cw_nextup_${item.info.contentId}_${item.info.season}_${item.info.episode}"
    }
}

internal fun catalogRowKey(row: CatalogRow): String {
    return row.stableKey()
}

internal fun catalogRowTitle(
    row: CatalogRow,
    showCatalogTypeSuffix: Boolean,
    strTypeMovie: String = "",
    strTypeSeries: String = ""
): String {
    val catalogName = row.catalogName.replaceFirstChar { it.uppercase() }
    if (!showCatalogTypeSuffix) return catalogName
    val typeLabel = when (row.apiType.lowercase()) {
        "movie" -> strTypeMovie.ifBlank { row.apiType.replaceFirstChar { it.uppercase() } }
        "series" -> strTypeSeries.ifBlank { row.apiType.replaceFirstChar { it.uppercase() } }
        else -> row.apiType.replaceFirstChar { it.uppercase() }
    }
    return "$catalogName - $typeLabel"
}

internal fun CatalogRow.key(): String {
    return stableKey()
}

internal fun isSeriesType(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

/**
 * The backdrop the hero would show for [item], with the same precedence the hero uses: enriched
 * art first when [enriched] has landed, otherwise the catalog's own art.
 */
internal fun heroBackdropFor(item: ModernCarouselItem, enriched: MetaPreview?): String? {
    val hero = item.heroPreview
    return if (enriched != null) {
        firstNonBlank(enriched.backdropUrl, hero.imageUrl, enriched.poster)
    } else {
        firstNonBlank(hero.backdrop, hero.imageUrl, hero.poster)
    }
}

/** Backdrops of the cards either side of [index] in [items], so the hero can load them early. */
/**
 * Backdrops to preload around the focused card. [singleNeighbour] (the low-RAM tier) keeps just one,
 * the next card's or else the previous card's, since each is decoded at full hero size.
 */
internal fun neighbourHeroBackdrops(
    items: List<ModernCarouselItem>,
    index: Int,
    enrichedPreviews: Map<String, MetaPreview>,
    singleNeighbour: Boolean = false
): List<String> {
    val candidates = if (singleNeighbour) listOf(index + 1, index - 1) else listOf(index - 1, index + 1)
    val backdrops = candidates.mapNotNull { i ->
        val item = items.getOrNull(i) ?: return@mapNotNull null
        heroBackdropFor(item, item.metaPreview?.id?.let { enrichedPreviews[it] })
    }.distinct()
    // Pick after resolving, so a next card without art falls back to the previous one.
    return if (singleNeighbour) backdrops.take(1) else backdrops
}

/**
 * Backdrops for the card that a step Up or Down would land on: each neighbouring row's
 * remembered card ([landingIndex]), the one below first. [singleNeighbour] keeps one, for the
 * low-RAM tier (TheShield2594/fetch#804).
 */
internal fun verticalNeighbourHeroBackdrops(
    rows: List<HomeRowModel>,
    activeRowKey: String?,
    landingIndex: (String) -> Int,
    enrichedPreviews: Map<String, MetaPreview>,
    singleNeighbour: Boolean = false
): List<String> {
    val activeIndex = rows.indexOfFirst { it.key == activeRowKey }
    if (activeIndex < 0) return emptyList()
    val backdrops = listOf(activeIndex + 1, activeIndex - 1).mapNotNull { rowIndex ->
        val row = rows.getOrNull(rowIndex) ?: return@mapNotNull null
        val items = row.items.list
        val item = items.getOrNull(landingIndex(row.key)) ?: items.firstOrNull() ?: return@mapNotNull null
        heroBackdropFor(item, item.metaPreview?.id?.let { enrichedPreviews[it] })
    }.distinct()
    return if (singleNeighbour) backdrops.take(1) else backdrops
}


internal fun extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return YEAR_REGEX.find(releaseInfo)?.value
}

internal fun extractYearOrRange(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return releaseInfo.trim()
}

@Volatile
private var cachedDateFormatLocale: java.util.Locale? = null
@Volatile
private var cachedDateFormatPattern: String? = null

internal fun extractYearText(type: ContentType, releaseInfo: String?, released: String?, showFullDate: Boolean = true): String? {
    if (showFullDate && type == ContentType.MOVIE) {
        val full = released
            ?.let {
                // Try OffsetDateTime first (addon format: "2024-03-15T00:00:00.000Z"),
                // then LocalDate (TMDB collection format: "2024-03-15"). (#2516)
                runCatching { java.time.OffsetDateTime.parse(it).toLocalDate() }.getOrNull()
                    ?: runCatching { java.time.LocalDate.parse(it) }.getOrNull()
            }
            ?.let {
                val locale = java.util.Locale.getDefault()
                val pattern = if (locale == cachedDateFormatLocale && cachedDateFormatPattern != null) {
                    cachedDateFormatPattern!!
                } else {
                    android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMMy").also { p ->
                        cachedDateFormatPattern = p
                        cachedDateFormatLocale = locale
                    }
                }
                // Use SimpleDateFormat (not DateTimeFormatter) to match the Details
                // page formatting. DateTimeFormatter.ofPattern interprets MMMM as
                // the standalone month form in some locales (e.g. Polish "czerwiec"
                // instead of the genitive "czerwca" used in full dates), while
                // SimpleDateFormat uses the inflected form expected in date context.
                java.text.SimpleDateFormat(pattern, locale)
                    .format(java.util.Date(it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
            }
        if (full != null) return full
    }
    return extractYearOrRange(releaseInfo)
}

internal fun ContinueWatchingItem.contentId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentId
        is ContinueWatchingItem.NextUp -> info.contentId
    }
}

internal fun ContinueWatchingItem.contentType(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentType
        is ContinueWatchingItem.NextUp -> info.contentType
    }
}

internal fun ContinueWatchingItem.season(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.season
        is ContinueWatchingItem.NextUp -> info.seedSeason
    }
}

/** Season of the episode the CW card represents (the upcoming/next episode for NextUp). */
internal fun ContinueWatchingItem.detailSeason(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.season
        is ContinueWatchingItem.NextUp -> info.season
    }
}

internal fun ContinueWatchingItem.episode(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.episode
        is ContinueWatchingItem.NextUp -> info.seedEpisode
    }
}

/** What "Add to queue" stores for this card; null for an episode that is not out yet. */
internal fun ContinueWatchingItem.queueItemOrNull(): com.fetch.tv.data.local.QueueItem? = when (this) {
    is ContinueWatchingItem.InProgress -> com.fetch.tv.data.local.QueueItem(
        contentId = progress.contentId,
        contentType = progress.contentType,
        videoId = progress.videoId,
        name = progress.name,
        season = progress.season,
        episode = progress.episode,
        episodeTitle = progress.episodeTitle,
        poster = progress.poster,
        backdrop = progress.backdrop,
        logo = progress.logo
    )
    is ContinueWatchingItem.NextUp -> if (!info.hasAired) {
        null
    } else {
        com.fetch.tv.data.local.QueueItem(
            contentId = info.contentId,
            contentType = info.contentType,
            videoId = info.videoId,
            name = info.name,
            season = info.season,
            episode = info.episode,
            episodeTitle = info.episodeTitle,
            poster = info.thumbnail ?: info.poster,
            backdrop = info.backdrop,
            logo = info.logo
        )
    }
}
