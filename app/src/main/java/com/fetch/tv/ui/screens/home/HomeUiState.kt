package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.THEME_CARD_CORNER_RADIUS
import androidx.compose.runtime.Immutable
import com.fetch.tv.data.local.StartupAuthNotice
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContinueWatchingCardStyle
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.fetch.tv.domain.model.HomeImdbRatingsVisibility
import com.fetch.tv.domain.model.DetailImdbRatingsVisibility
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.WatchProgress

/**
 * Why Home is empty (#324). A kind, not a localized string: the screen used to compare the
 * message against its own stringResource, which failed whenever the two were resolved in
 * different languages, and showed a generic error instead of the "Add addon" empty state.
 */
enum class HomeErrorKind {
    /** No addons installed. */
    NO_ADDONS,
    /** Addons installed, but none of them has catalogs. */
    NO_CATALOG_ADDONS,
    /** Loading failed; [HomeUiState.error] has the message when there is one. */
    LOAD_FAILED,
}

@Immutable
data class HomeUiState(
    val catalogRows: List<CatalogRow> = emptyList(),
    val continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    val upcomingItems: List<ContinueWatchingItem> = emptyList(),
    val isLoading: Boolean = true,
    val layoutPreferencesReady: Boolean = false,
    /** Why Home has nothing to show; the screen picks its empty or error state from this. */
    val errorKind: HomeErrorKind? = null,
    /** The failure's own message, for [HomeErrorKind.LOAD_FAILED]; null for the others. */
    val error: String? = null,
    val installedAddonsCount: Int = 0,
    val modernLandscapePostersEnabled: Boolean = true,
    val alwaysShowLandscapeClearlogo: Boolean = false,
    val modernHeroFullScreenBackdropEnabled: Boolean = true,
    val homeImdbRatingsVisibility: HomeImdbRatingsVisibility = HomeImdbRatingsVisibility.SHOW_ALL,
    val detailImdbRatingsVisibility: DetailImdbRatingsVisibility = DetailImdbRatingsVisibility.SHOW_ALL,
    val heroItems: List<MetaPreview> = emptyList(),
    val heroCatalogKeys: List<String> = emptyList(),
    val heroSectionEnabled: Boolean = true,
    /**
     * A kids profile's Home: no hero, fewer rows, bigger cards, and nothing about addons,
     * sources or ratings on the cards. See [KIDS_HOME_MAX_ROWS].
     */
    val kidsHome: Boolean = false,
    val modernHomePresentation: ModernHomePresentationState = ModernHomePresentationState(),
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val focusedPosterBackdropExpandEnabled: Boolean = false,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true,
    val focusedPosterBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget =
        FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = THEME_CARD_CORNER_RADIUS,
    val movieWatchedStatus: Map<String, Boolean> = emptyMap(),
    val hideUnreleasedContent: Boolean = false,
    val showFullReleaseDate: Boolean = true,
    val blurUnwatchedEpisodes: Boolean = false,
    val useEpisodeThumbnailsInCw: Boolean = true,
    val continueWatchingEnabled: Boolean = true,
    val continueWatchingCardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    val heroEnrichmentEnabled: Boolean = false,
    val startupAuthNotice: StartupAuthNotice? = null,
    val homeRows: List<HomeRow> = emptyList(),
    /** "Because you watched" rows, shown after Continue Watching and pinned collections. */
    val becauseYouWatchedRows: List<CatalogRow> = emptyList(),
    val customPosterUrlPattern: String = "",
    val customPosterEnabledScreens: Set<com.fetch.tv.core.poster.CustomPosterScreen> =
        com.fetch.tv.core.poster.CustomPosterScreen.ALL,
    val mdbListShowOnHero: Boolean = false,
    val mdbListRatingOrder: List<String> = com.fetch.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER
)

@Immutable
sealed class ContinueWatchingItem {
    abstract val shufflePlayback: Boolean
    @Immutable
    data class InProgress(
        val progress: WatchProgress,
        val episodeDescription: String? = null,
        val episodeThumbnail: String? = null,
        val episodeImdbRating: Float? = null,
        val genres: List<String> = emptyList(),
        val releaseInfo: String? = null,
        val contentLanguage: String? = null,
        override val shufflePlayback: Boolean = false,
        val originalPoster: String? = null,
        val customLandscapePoster: String? = null,
        val mdbListRatings: com.fetch.tv.domain.model.MDBListRatings? = null
    ) : ContinueWatchingItem()

    @Immutable
    data class NextUp(
        val info: NextUpInfo,
        override val shufflePlayback: Boolean = false,
        val originalPoster: String? = null,
        val customLandscapePoster: String? = null
    ) : ContinueWatchingItem()
}

val ContinueWatchingItem.shuffleFocusKey: String?
    get() = if (!shufflePlayback) null else when (this) {
        is ContinueWatchingItem.InProgress -> "cw_shuffle_${progress.contentId}"
        is ContinueWatchingItem.NextUp -> "cw_shuffle_${info.contentId}"
    }

@Immutable
data class NextUpInfo(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null,
    val mdbListRatings: com.fetch.tv.domain.model.MDBListRatings? = null
)

@Immutable
sealed class HomeRow {
    @Immutable
    data class Catalog(val row: CatalogRow) : HomeRow()

    @Immutable
    data class CollectionRow(val collection: Collection) : HomeRow()

    /**
     * Placeholder for a catalog row whose data hasn't been fetched yet.
     * Rendered as a shimmer/skeleton row until the user scrolls near it
     * and the actual catalog data is loaded on demand.
     */
    @Immutable
    data class PlaceholderCatalog(
        val catalogKey: String,
        val stableCatalogKey: String,
        val addonId: String,
        val addonName: String,
        val addonBaseUrl: String,
        val catalogId: String,
        val catalogName: String,
        val apiType: String,
        val displayTitle: String
    ) : HomeRow()
}

sealed class HomeEvent {
    data class OnLoadMoreCatalog(val catalogId: String, val addonId: String, val type: String) : HomeEvent()
    data class OnRemoveContinueWatching(
        val contentId: String,
        val season: Int? = null,
        val episode: Int? = null,
        val isNextUp: Boolean = false
    ) : HomeEvent()
    data object OnRetry : HomeEvent()
}

fun homeItemStatusKey(itemId: String, itemType: String): String {
    return "${itemType.lowercase()}|$itemId"
}
