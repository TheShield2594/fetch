package com.fetch.desktop.sync

import com.fetch.tv.core.streams.ranking.StreamRankingRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A profile as the TV syncs it. [profileIndex] is the profile's slot; 1 is the primary profile. */
data class RemoteProfile(
    val profileIndex: Int,
    val name: String,
    val avatarColorHex: String,
    /** When true the profile has no addons of its own: read and write profile 1's instead. */
    val usesPrimaryAddons: Boolean,
    val avatarId: String? = null,
    val avatarUrl: String? = null,
    /** The TV app's profile background; carried through so a push doesn't clear it. */
    val profileBackgroundId: String? = null,
    val profileBackgroundUrl: String? = null,
)

/**
 * The TV settings the desktop app has too, read from or written into a profile's settings blob.
 * Null means "not in the blob" on a pull, and "leave it as it is" on a push. The TV keys are
 * in [SyncSettingsBlob].
 */
data class RemoteSharedSettings(
    /** `theme_settings.selected_theme`: an [com.fetch.tv.domain.model.AppTheme] name. */
    val theme: String? = null,
    /** `layout_settings.continue_watching_enabled` (TV default true). */
    val showContinueWatching: Boolean? = null,
    /** `layout_settings.catalog_type_suffix_enabled` (TV default true). */
    val showCatalogType: Boolean? = null,
    /** `layout_settings.catalog_addon_name_enabled` (TV default true). */
    val showAddonName: Boolean? = null,
    /** `layout_settings.poster_labels_enabled` (TV default true). */
    val showPosterLabels: Boolean? = null,
    /** `layout_settings.discover_location`: `OFF`, `IN_SEARCH` (TV default) or `IN_SIDEBAR`. */
    val discoverLocation: String? = null,
    /** `player_settings.preferred_audio_language`: a language code, or `device`, `default`, `original`. */
    val audioLanguage: String? = null,
    /** `player_settings.subtitle_preferred_language`: a language code, or `device`, `forced`. */
    val subtitleLanguage: String? = null,
    /** `player_settings.stream_ranking_rules`, stored by the TV app as a JSON string. */
    val rankingRules: StreamRankingRules? = null,
    /** `player_settings.stream_auto_play_next_episode_enabled` (TV default false). */
    val autoplayNextEpisode: Boolean? = null,
    /** `player_settings.auto_skip_segment_types`: `intro`, `recap`, `outro`, `movie-credits`. */
    val autoSkipSegments: Set<String>? = null,
    /** `layout_settings.show_unaired_next_up` (TV default true). */
    val showUnairedNextUp: Boolean? = null,
    /** `layout_settings.next_up_from_furthest_episode` (TV default true). */
    val nextUpFromFurthest: Boolean? = null,
    /** `layout_settings.continue_watching_sort_mode`: a ContinueWatchingSortMode name. */
    val continueWatchingSort: String? = null,
    /** `tmdb_settings` booleans by key (`tmdb_enabled`, `tmdb_use_artwork`, …); only the ones present. */
    val tmdbFlags: Map<String, Boolean>? = null,
    /** `tmdb_settings.tmdb_language`. */
    val tmdbLanguage: String? = null,
    /** `mdblist_settings` booleans by key (`mdblist_enabled`, `mdblist_show_imdb`, …); only the ones present. */
    val mdblistFlags: Map<String, Boolean>? = null,
    /** `mdblist_settings.mdblist_rating_order`: providers joined by commas. The API key is not synced here. */
    val mdblistRatingOrder: String? = null,
    /** `theme_settings`' contrast, AMOLED, custom colours and font (see AppearanceExperienceSync.kt). */
    val appearance: RemoteAppearance? = null,
    /** `experience_settings`: Essential or Advanced. */
    val experience: RemoteExperience? = null,
    /** Subtitle, audio, Anime-Skip and trailer settings the TV syncs ([RemotePlayerExtras]). */
    val playerExtras: RemotePlayerExtras? = null,
    /** `player_settings` auto-play keys ([AutoPlaySync.KEYS]) with their values; only the ones present. */
    val autoPlay: Map<String, kotlinx.serialization.json.JsonPrimitive>? = null,
    /** `player_settings.stream_auto_play_selected_addons`: addon names auto-play may pick from (empty: all). */
    val autoPlayAddons: Set<String>? = null,
    /** `episode_queue.items`: the playback queue as the TV's JSON array. */
    val episodeQueue: String? = null,
    /**
     * Layout's other settings ([com.fetch.desktop.data.LayoutSync]): the blob's typed entries
     * (`{"type":…,"value":…}`) by `feature/key`; only the ones present.
     */
    val layoutValues: Map<String, kotlinx.serialization.json.JsonObject>? = null,
    /** `trakt_settings` strings by key (`library_source_mode`, `watch_progress_source`, …); only the ones present. */
    val trackingStrings: Map<String, String>? = null,
    /** `trakt_settings` ints by key (`continue_watching_days_cap`). */
    val trackingInts: Map<String, Int>? = null,
    /** `trakt_settings` booleans by key (`show_meta_comments`). */
    val trackingFlags: Map<String, Boolean>? = null,
)

/**
 * A profile's parental limits as the TV app syncs them, in the settings blob's `parental` object
 * (only for profiles other than 1). [maxRating] is a [com.fetch.tv.core.parental.CertificationLevel]
 * name; null means no limit. Daily minutes, bedtime and the autoplay cap stay on each device.
 */
data class RemoteParentalLimits(
    val isKidsProfile: Boolean = false,
    val maxRating: String? = null,
    val allowUnrated: Boolean = true,
)

/** An installed Stremio addon, by manifest URL. Lower [order] comes first. */
data class RemoteAddon(
    val url: String,
    val order: Int,
    val enabled: Boolean = true,
    /** A name the user set; null keeps the manifest's. */
    val name: String? = null,
)
