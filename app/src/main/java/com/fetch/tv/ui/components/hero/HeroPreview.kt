package com.fetch.tv.ui.components.hero

import androidx.compose.runtime.Immutable
import com.fetch.tv.ui.util.StableList

/** What a hero shows for the focused title: Home's, and the browse screens' band (#852). */
@Immutable
data class HeroPreview(
    val title: String,
    val logo: String?,
    val description: String?,
    val contentTypeText: String?,
    val isSeries: Boolean = false,
    val yearText: String?,
    val runtimeText: String? = null,
    val secondaryHighlightText: String? = null,
    val imdbText: String?,
    val ageRatingText: String? = null,
    val statusText: String? = null,
    val languageText: String? = null,
    val genres: StableList<String>,
    val poster: String?,
    val backdrop: String?,
    val imageUrl: String?,
    val mdbListRatings: com.fetch.tv.domain.model.MDBListRatings? = null,
    val mdbListRatingOrder: List<String> = com.fetch.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    /** Snapshot of the backdrop URL captured before TMDB enrichment.
     *  Survives cache rebuilds so landscape cards keep their original art
     *  even after navigation away and back. */
    val frozenBackdropUrl: String? = null,
    /** Same idea for the logo URL. */
    val frozenLogoUrl: String? = null,
    /** Continue Watching only: what OK does ("Resume S2 E6"), shown as the hero's button. */
    val resumeActionText: String? = null,
    /** Continue Watching only: time left ("38m left"), the first part of the spec line. */
    val resumeTimeLeftText: String? = null,
    /** Continue Watching only: how much is watched, 0..1, drawn as the hero's progress line. */
    val resumeProgressFraction: Float? = null,
    /**
     * Key of this video in the Best match cache: the episode or film a Continue Watching card
     * resumes, or a catalog movie, whose hero then says Play starts a cached pick (#812).
     */
    val bestMatchKey: String? = null,
    /**
     * Continue Watching only: the title of the episode Resume (or Play) starts, shown as one line
     * under the logo. The meta line then carries only the episode code, so the title isn't repeated.
     */
    val resumeEpisodeTitle: String? = null,
    /** Why this title is on Home ("NEW EPISODE", a recommendation reason), shown above the title. */
    val eyebrowText: String? = null,
    /** A catalog card: Play/Pause starts it and holding OK opens its options menu (#812). */
    val isCatalogTitle: Boolean = false
)
