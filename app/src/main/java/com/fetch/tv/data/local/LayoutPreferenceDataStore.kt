package com.fetch.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.LocalHomeCatalogSettingsState
import com.fetch.tv.core.sync.SyncHomeCatalogPayload
import com.fetch.tv.core.sync.buildHomeCatalogSyncPayload
import com.fetch.tv.core.sync.homeCatalogKey
import com.fetch.tv.core.sync.homeCollectionKey
import com.fetch.tv.core.sync.withLocalServerKeys
import com.fetch.tv.data.mediaserver.ServerCatalog
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.ContinueWatchingCardStyle
import com.fetch.tv.domain.model.ContinueWatchingSortMode
import com.fetch.tv.domain.model.DiscoverLocation
import com.fetch.tv.domain.model.EpisodeOptionsOverlayStyle
import com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.fetch.tv.domain.model.DetailImdbRatingsVisibility
import com.fetch.tv.domain.model.HomeImdbRatingsVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LayoutPreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "LayoutPreferenceDS"
        private const val FEATURE = "layout_settings"
        private const val DEFAULT_POSTER_CARD_WIDTH_DP = 126
        private const val DEFAULT_POSTER_CARD_HEIGHT_DP = 189
        private const val DEFAULT_POSTER_CARD_CORNER_RADIUS_DP = 8
        private const val DEFAULT_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS = 3
        private const val MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()

    private val legacyLayoutKey = stringPreferencesKey("selected_layout")
    // Set once first-run setup finished. The key name predates the single Home layout.
    private val hasChosenKey = booleanPreferencesKey("has_chosen_layout")
    private val heroCatalogKey = stringPreferencesKey("hero_catalog_key")
    private val heroCatalogKeysKey = stringPreferencesKey("hero_catalog_keys")
    private val homeCatalogOrderKeysKey = stringPreferencesKey("home_catalog_order_keys")
    private val disabledHomeCatalogKeysKey = stringPreferencesKey("disabled_home_catalog_keys")
    private val pinnedHomeCatalogKeysKey = stringPreferencesKey("pinned_home_catalog_keys")
    private val customCatalogTitlesKey = stringPreferencesKey("custom_catalog_titles")
    private val sidebarCollapsedKey = booleanPreferencesKey("sidebar_collapsed_by_default")
    private val modernLandscapePostersEnabledKey = booleanPreferencesKey("modern_landscape_posters_enabled")
    private val heroSectionEnabledKey = booleanPreferencesKey("hero_section_enabled")
    private val posterLabelsEnabledKey = booleanPreferencesKey("poster_labels_enabled")
    private val catalogAddonNameEnabledKey = booleanPreferencesKey("catalog_addon_name_enabled")
    private val catalogTypeSuffixEnabledKey = booleanPreferencesKey("catalog_type_suffix_enabled")
    private val legacyClassicFocusGradientEnabledKey = booleanPreferencesKey("classic_focus_gradient_enabled")
    private val focusedPosterBackdropExpandEnabledKey = booleanPreferencesKey("focused_poster_backdrop_expand_enabled")
    private val focusedPosterBackdropExpandDelaySecondsKey = intPreferencesKey("focused_poster_backdrop_expand_delay_seconds")
    private val focusedPosterBackdropTrailerEnabledKey = booleanPreferencesKey("focused_poster_backdrop_trailer_enabled")
    private val focusedPosterBackdropTrailerMutedKey = booleanPreferencesKey("focused_poster_backdrop_trailer_muted")
    private val focusedPosterBackdropTrailerPlaybackTargetKey =
        stringPreferencesKey("focused_poster_backdrop_trailer_playback_target")
    private val posterCardWidthDpKey = intPreferencesKey("poster_card_width_dp")
    private val posterCardHeightDpKey = intPreferencesKey("poster_card_height_dp")
    private val posterCardCornerRadiusDpKey = intPreferencesKey("poster_card_corner_radius_dp")
    private val blurUnwatchedEpisodesKey = booleanPreferencesKey("blur_unwatched_episodes")
    private val randomEpisodeEnabledKey = booleanPreferencesKey("random_episode_enabled")
    private val startupSplashEnabledKey = booleanPreferencesKey("startup_splash_enabled")
    private val alwaysShowLandscapeClearlogoKey = booleanPreferencesKey("always_show_landscape_clearlogo")
    private val trailerCropToFillKey = booleanPreferencesKey("trailer_crop_to_fill")
    private val episodeOptionsOverlayStyleKey = stringPreferencesKey("episode_options_overlay_style")
    private val homeImdbRatingsVisibilityKey = stringPreferencesKey("home_imdb_ratings_visibility")
    private val detailImdbRatingsVisibilityKey = stringPreferencesKey("detail_imdb_ratings_visibility")
    private val useEpisodeThumbnailsInCwKey = booleanPreferencesKey("use_episode_thumbnails_in_cw")
    private val continueWatchingEnabledKey = booleanPreferencesKey("continue_watching_enabled")
    private val continueWatchingCardStyleKey = stringPreferencesKey("continue_watching_card_style")
    private val showUnairedNextUpKey = booleanPreferencesKey("show_unaired_next_up")
    private val nextUpFromFurthestEpisodeKey = booleanPreferencesKey("next_up_from_furthest_episode")
    private val blurContinueWatchingNextUpKey = booleanPreferencesKey("blur_continue_watching_next_up")
    private val continueWatchingSortModeKey = stringPreferencesKey("continue_watching_sort_mode")
    private val detailPageTrailerButtonEnabledKey = booleanPreferencesKey("detail_page_trailer_button_enabled")
    private val preferExternalMetaAddonDetailKey = booleanPreferencesKey("prefer_external_meta_addon_detail")
    private val modernHeroFullScreenBackdropKey = booleanPreferencesKey("modern_hero_full_screen_backdrop")
    private val hideUnreleasedContentKey = booleanPreferencesKey("hide_unreleased_content")
    private val showFullReleaseDateKey = booleanPreferencesKey("show_full_release_date")
    private val memoryOnlyVerticalScrollKey = booleanPreferencesKey("memory_only_vertical_scroll")
    private val smoothBringIntoViewEnabledKey = booleanPreferencesKey("smooth_bring_into_view_enabled")
    private val fastHorizontalNavigationEnabledKey = booleanPreferencesKey("fast_horizontal_navigation_enabled")
    private val followAddonsOrderKey = booleanPreferencesKey("follow_addons_order")
    private val composeHighlighterEnabledKey = booleanPreferencesKey("compose_highlighter_enabled")

    private val customPosterUrlPatternKey = stringPreferencesKey("custom_poster_url_pattern")
    private val customPosterEnabledScreensKey = stringPreferencesKey("custom_poster_enabled_screens")

    private fun <T> profileFlow(extract: (prefs: androidx.datastore.preferences.core.Preferences) -> T): Flow<T> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs -> extract(prefs) }
        }

    private fun Preferences.getStringOrMigrateSet(key: Preferences.Key<String>): String? {
        return try {
            this[key]
        } catch (e: ClassCastException) {
            val setKey = stringSetPreferencesKey(key.name)
            val legacySet = try { this[setKey] } catch (_: Exception) { null }
            if (legacySet != null) {
                Log.w(TAG, "Key '${key.name}' stored as Set instead of String (${legacySet.size} items), converting")
                gson.toJson(legacySet.toList())
            } else {
                Log.e(TAG, "ClassCastException for key '${key.name}' but no Set value found", e)
                null
            }
        }
    }

    private fun positiveOrDefault(value: Int?, defaultValue: Int): Int =
        value?.takeIf { it > 0 } ?: defaultValue

    val continueWatchingEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[continueWatchingEnabledKey] ?: true
    }

    val continueWatchingCardStyle: Flow<ContinueWatchingCardStyle> = profileFlow { prefs ->
        val styleName = prefs[continueWatchingCardStyleKey] ?: ContinueWatchingCardStyle.CARD.name
        try {
            ContinueWatchingCardStyle.valueOf(styleName)
        } catch (e: IllegalArgumentException) {
            ContinueWatchingCardStyle.CARD
        }
    }

    val hasChosenLayout: Flow<Boolean> = profileFlow { prefs ->
        prefs[hasChosenKey] ?: false
    }

    val heroCatalogSelections: Flow<List<String>> = profileFlow { prefs ->
        val multiSelection = parseCatalogKeys(prefs.getStringOrMigrateSet(heroCatalogKeysKey))
        if (multiSelection.isNotEmpty()) {
            multiSelection
        } else {
            prefs.getStringOrMigrateSet(heroCatalogKey)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::listOf)
                .orEmpty()
        }
    }

    val heroCatalogSelection: Flow<String?> = heroCatalogSelections.map { selections ->
        selections.firstOrNull()
    }

    val homeCatalogOrderKeys: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        val profile = profileManager.profiles.value.find { it.id == pid }
        val usePrimary = profile != null && !profile.isPrimary && profile.usesPrimaryAddons
        val effectivePid = if (usePrimary) 1 else pid
        factory.get(effectivePid, FEATURE).data.map { prefs ->
            parseCatalogKeys(prefs.getStringOrMigrateSet(homeCatalogOrderKeysKey))
        }
    }

    val disabledHomeCatalogKeys: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        val profile = profileManager.profiles.value.find { it.id == pid }
        val usePrimary = profile != null && !profile.isPrimary && profile.usesPrimaryAddons
        val effectivePid = if (usePrimary) 1 else pid
        factory.get(effectivePid, FEATURE).data.map { prefs ->
            parseCatalogKeys(prefs.getStringOrMigrateSet(disabledHomeCatalogKeysKey))
        }
    }

    /** Order keys of rows pinned to the top of Home, in the order they were pinned. */
    val pinnedHomeCatalogKeys: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        val profile = profileManager.profiles.value.find { it.id == pid }
        val usePrimary = profile != null && !profile.isPrimary && profile.usesPrimaryAddons
        val effectivePid = if (usePrimary) 1 else pid
        factory.get(effectivePid, FEATURE).data.map { prefs ->
            parseCatalogKeys(prefs[pinnedHomeCatalogKeysKey])
        }
    }

    val customCatalogTitles: Flow<Map<String, String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        val profile = profileManager.profiles.value.find { it.id == pid }
        val usePrimary = profile != null && !profile.isPrimary && profile.usesPrimaryAddons
        val effectivePid = if (usePrimary) 1 else pid
        factory.get(effectivePid, FEATURE).data.map { prefs ->
            parseCustomTitles(prefs.getStringOrMigrateSet(customCatalogTitlesKey))
        }
    }

    val sidebarCollapsedByDefault: Flow<Boolean> = profileFlow { prefs ->
        prefs[sidebarCollapsedKey] ?: false
    }

    val modernLandscapePostersEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernLandscapePostersEnabledKey] ?: true
    }

    val modernHeroFullScreenBackdropEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernHeroFullScreenBackdropKey] ?: true
    }

    val heroSectionEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[heroSectionEnabledKey] ?: true
    }

    val discoverLocation: Flow<DiscoverLocation> = profileFlow { prefs ->
        val stored = prefs[discoverLocationKey] ?: DiscoverLocation.IN_SEARCH.name
        runCatching { DiscoverLocation.valueOf(stored) }
            .getOrDefault(DiscoverLocation.IN_SEARCH)
    }

    val lastNonOffDiscoverLocation: Flow<DiscoverLocation> = profileFlow { prefs ->
        val stored = prefs[lastNonOffDiscoverLocationKey]
            ?.takeIf { it != DiscoverLocation.OFF.name }
        val fallback = prefs[discoverLocationKey]
            ?.takeIf { it != DiscoverLocation.OFF.name }
        val source = stored ?: fallback ?: DiscoverLocation.IN_SEARCH.name
        runCatching { DiscoverLocation.valueOf(source) }
            .getOrDefault(DiscoverLocation.IN_SEARCH)
    }

    val posterLabelsEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[posterLabelsEnabledKey] ?: true
    }

    val catalogAddonNameEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[catalogAddonNameEnabledKey] ?: true
    }

    val catalogTypeSuffixEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[catalogTypeSuffixEnabledKey] ?: true
    }

    val focusedPosterBackdropExpandEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropExpandEnabledKey] ?: true
    }

    val focusedPosterBackdropExpandDelaySeconds: Flow<Int> = profileFlow { prefs ->
        (prefs[focusedPosterBackdropExpandDelaySecondsKey]
            ?: DEFAULT_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
            .coerceAtLeast(MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
    }

    val focusedPosterBackdropTrailerEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropTrailerEnabledKey] ?: false
    }

    val focusedPosterBackdropTrailerMuted: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropTrailerMutedKey] ?: true
    }

    val focusedPosterBackdropTrailerPlaybackTarget: Flow<FocusedPosterTrailerPlaybackTarget> =
        profileFlow { prefs ->
            val stored = prefs[focusedPosterBackdropTrailerPlaybackTargetKey]
                ?: FocusedPosterTrailerPlaybackTarget.HERO_MEDIA.name
            runCatching { FocusedPosterTrailerPlaybackTarget.valueOf(stored) }
                .getOrDefault(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
        }

    val posterCardWidthDp: Flow<Int> = profileFlow { prefs ->
        positiveOrDefault(prefs[posterCardWidthDpKey], DEFAULT_POSTER_CARD_WIDTH_DP)
    }

    val posterCardHeightDp: Flow<Int> = profileFlow { prefs ->
        positiveOrDefault(prefs[posterCardHeightDpKey], DEFAULT_POSTER_CARD_HEIGHT_DP)
    }

    val posterCardCornerRadiusDp: Flow<Int> = profileFlow { prefs ->
        prefs[posterCardCornerRadiusDpKey] ?: DEFAULT_POSTER_CARD_CORNER_RADIUS_DP
    }

    val blurUnwatchedEpisodes: Flow<Boolean> = profileFlow { prefs ->
        prefs[blurUnwatchedEpisodesKey] ?: false
    }

    val randomEpisodeEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[randomEpisodeEnabledKey] ?: false
    }

    fun randomEpisodeEnabledForProfile(profileId: Int): Flow<Boolean> =
        factory.get(profileId, FEATURE).data.map { it[randomEpisodeEnabledKey] ?: false }

    val startupSplashEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[startupSplashEnabledKey] ?: true
    }

    val alwaysShowLandscapeClearlogo: Flow<Boolean> = profileFlow { prefs ->
        prefs[alwaysShowLandscapeClearlogoKey] ?: false
    }

    /** Background trailers zoom to fill their frame (true) or play whole, letterboxed (false). */
    val trailerCropToFill: Flow<Boolean> = profileFlow { prefs ->
        prefs[trailerCropToFillKey] ?: true
    }

    val episodeOptionsOverlayStyle: Flow<EpisodeOptionsOverlayStyle> = profileFlow { prefs ->
        val stored = prefs[episodeOptionsOverlayStyleKey] ?: EpisodeOptionsOverlayStyle.BLUR.name
        runCatching { EpisodeOptionsOverlayStyle.valueOf(stored) }
            .getOrDefault(EpisodeOptionsOverlayStyle.BLUR)
    }

    val homeImdbRatingsVisibility: Flow<HomeImdbRatingsVisibility> = profileFlow { prefs ->
        val stored = prefs[homeImdbRatingsVisibilityKey] ?: HomeImdbRatingsVisibility.SHOW_ALL.name
        runCatching { HomeImdbRatingsVisibility.valueOf(stored) }
            .getOrDefault(HomeImdbRatingsVisibility.SHOW_ALL)
    }

    val detailImdbRatingsVisibility: Flow<DetailImdbRatingsVisibility> = profileFlow { prefs ->
        val stored = prefs[detailImdbRatingsVisibilityKey] ?: DetailImdbRatingsVisibility.SHOW_ALL.name
        runCatching { DetailImdbRatingsVisibility.valueOf(stored) }
            .getOrDefault(DetailImdbRatingsVisibility.SHOW_ALL)
            .asEpisodeVisibility()
    }

    val useEpisodeThumbnailsInCw: Flow<Boolean> = profileFlow { prefs ->
        prefs[useEpisodeThumbnailsInCwKey] ?: true
    }

    val showUnairedNextUp: Flow<Boolean> = profileFlow { prefs ->
        prefs[showUnairedNextUpKey] ?: true
    }

    val nextUpFromFurthestEpisode: StateFlow<Boolean> = profileFlow { prefs ->
        prefs[nextUpFromFurthestEpisodeKey] ?: true
    }.stateIn(scope, SharingStarted.Eagerly, true)

    val blurContinueWatchingNextUp: Flow<Boolean> = profileFlow { prefs ->
        prefs[blurContinueWatchingNextUpKey] ?: false
    }

    val continueWatchingSortMode: Flow<ContinueWatchingSortMode> = profileFlow { prefs ->
        val stored = prefs[continueWatchingSortModeKey] ?: ContinueWatchingSortMode.DEFAULT.name
        runCatching { ContinueWatchingSortMode.valueOf(stored) }
            .getOrDefault(ContinueWatchingSortMode.DEFAULT)
    }

    val detailPageTrailerButtonEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[detailPageTrailerButtonEnabledKey] ?: true
    }

    val preferExternalMetaAddonDetail: StateFlow<Boolean> = profileFlow { prefs ->
        prefs[preferExternalMetaAddonDetailKey] ?: true
    }.stateIn(scope, SharingStarted.Eagerly, true)

    val hideUnreleasedContent: Flow<Boolean> = profileFlow { prefs ->
        prefs[hideUnreleasedContentKey] ?: false
    }

    val showFullReleaseDate: Flow<Boolean> = profileFlow { prefs ->
        prefs[showFullReleaseDateKey] ?: true
    }

    val memoryOnlyVerticalScroll: Flow<Boolean> = profileFlow { prefs ->
        prefs[memoryOnlyVerticalScrollKey] ?: true
    }

    val smoothBringIntoViewEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[smoothBringIntoViewEnabledKey] ?: true
    }

    val fastHorizontalNavigationEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[fastHorizontalNavigationEnabledKey] ?: false
    }

    val followAddonsOrder: Flow<Boolean> = profileFlow { prefs ->
        prefs[followAddonsOrderKey] ?: false
    }

    val composeHighlighterEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[composeHighlighterEnabledKey] ?: false
    }

    /** Custom poster URL pattern with `{placeholder}` tokens. Empty string means disabled. */
    val customPosterUrlPattern: Flow<String> = profileFlow { prefs ->
        prefs[customPosterUrlPatternKey] ?: ""
    }

    val customPosterEnabledScreens: Flow<Set<com.fetch.tv.core.poster.CustomPosterScreen>> = profileFlow { prefs ->
        com.fetch.tv.core.poster.CustomPosterScreen.fromStorageValue(prefs[customPosterEnabledScreensKey])
    }

    suspend fun setMemoryOnlyVerticalScroll(enabled: Boolean) {
        store().edit { prefs ->
            prefs[memoryOnlyVerticalScrollKey] = enabled
        }
    }

    suspend fun setSmoothBringIntoViewEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[smoothBringIntoViewEnabledKey] = enabled
        }
    }

    suspend fun setFastHorizontalNavigationEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[fastHorizontalNavigationEnabledKey] = enabled
        }
    }

    suspend fun setFollowAddonsOrder(enabled: Boolean) {
        store().edit { prefs ->
            prefs[followAddonsOrderKey] = enabled
        }
    }

    suspend fun setComposeHighlighterEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[composeHighlighterEnabledKey] = enabled
        }
    }

    suspend fun setCustomPosterUrlPattern(pattern: String) {
        store().edit { prefs ->
            if (pattern.isBlank()) {
                prefs.remove(customPosterUrlPatternKey)
            } else {
                prefs[customPosterUrlPatternKey] = pattern.trim()
            }
        }
    }

    suspend fun clearCustomPosterSettings() {
        store().edit { prefs ->
            prefs.remove(customPosterUrlPatternKey)
            prefs.remove(customPosterEnabledScreensKey)
        }
    }

    suspend fun setCustomPosterEnabledScreens(screens: Set<com.fetch.tv.core.poster.CustomPosterScreen>) {
        store().edit { prefs ->
            val stored = com.fetch.tv.core.poster.CustomPosterScreen.toStorageValue(screens)
            if (stored == null) {
                prefs.remove(customPosterEnabledScreensKey)
            } else {
                prefs[customPosterEnabledScreensKey] = stored
            }
        }
    }

    /**
     * Marks first-run setup as done. A fresh profile also starts with hero trailers, the default
     * the Home layout is designed around.
     */
    suspend fun markHomeSetUp() {
        store().edit { prefs ->
            val wasSetUp = prefs[hasChosenKey] ?: false
            if (!wasSetUp) {
                if (prefs[focusedPosterBackdropTrailerPlaybackTargetKey] == null) {
                    prefs[focusedPosterBackdropTrailerPlaybackTargetKey] =
                        FocusedPosterTrailerPlaybackTarget.HERO_MEDIA.name
                }
            }
            prefs[hasChosenKey] = true
            // Classic and Grid are gone; drop their stored choices so nothing reads them again.
            prefs.remove(legacyLayoutKey)
            prefs.remove(legacyClassicFocusGradientEnabledKey)
        }
    }

    suspend fun setHeroCatalogKeys(catalogKeys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(catalogKeys)
        store().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(heroCatalogKeysKey)
                prefs.remove(heroCatalogKey)
            } else {
                prefs[heroCatalogKeysKey] = gson.toJson(normalizedKeys)
                prefs[heroCatalogKey] = normalizedKeys.first()
            }
        }
    }

    suspend fun setHeroCatalogKey(catalogKey: String) {
        setHeroCatalogKeys(listOf(catalogKey))
    }

    suspend fun setHomeCatalogOrderKeys(keys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(keys)
        store().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(homeCatalogOrderKeysKey)
            } else {
                prefs[homeCatalogOrderKeysKey] = gson.toJson(normalizedKeys)
            }
        }
    }

    suspend fun setDisabledHomeCatalogKeys(keys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(keys)
        store().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(disabledHomeCatalogKeysKey)
            } else {
                prefs[disabledHomeCatalogKeysKey] = gson.toJson(normalizedKeys)
            }
        }
    }

    suspend fun setPinnedHomeCatalogKeys(keys: List<String>) {
        editPinnedHomeCatalogKeys { normalizeCatalogOrderKeys(keys) }
    }

    /** Pins [key] below the rows already pinned, or unpins it. */
    suspend fun togglePinnedHomeCatalogKey(key: String) {
        val normalized = key.trim().takeIf { it.isNotEmpty() } ?: return
        editPinnedHomeCatalogKeys { current ->
            if (normalized in current) current - normalized else current + normalized
        }
    }

    // Read and write in one edit, on the store pinnedHomeCatalogKeys reads: profiles that use
    // the primary profile's addons share its pins.
    private suspend fun editPinnedHomeCatalogKeys(transform: (List<String>) -> List<String>) {
        val pid = profileManager.activeProfileId.value
        val profile = profileManager.profiles.value.find { it.id == pid }
        val effectivePid = if (profile != null && !profile.isPrimary && profile.usesPrimaryAddons) 1 else pid
        store(effectivePid).edit { prefs ->
            val updated = transform(parseCatalogKeys(prefs[pinnedHomeCatalogKeysKey]))
            if (updated.isEmpty()) {
                prefs.remove(pinnedHomeCatalogKeysKey)
            } else {
                prefs[pinnedHomeCatalogKeysKey] = gson.toJson(updated)
            }
        }
    }

    suspend fun setSidebarCollapsedByDefault(collapsed: Boolean) {
        store().edit { prefs ->
            prefs[sidebarCollapsedKey] = collapsed
        }
    }

    suspend fun setModernLandscapePostersEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernLandscapePostersEnabledKey] = enabled
        }
    }

    suspend fun setModernHeroFullScreenBackdropEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernHeroFullScreenBackdropKey] = enabled
        }
    }

    suspend fun setHeroSectionEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[heroSectionEnabledKey] = enabled
        }
    }

    suspend fun setDiscoverLocation(location: DiscoverLocation) {
        store().edit { prefs ->
            prefs[discoverLocationKey] = location.name
            if (location != DiscoverLocation.OFF) {
                prefs[lastNonOffDiscoverLocationKey] = location.name
            }
            prefs.remove(legacySearchDiscoverEnabledKey)
        }
    }

    suspend fun setPosterLabelsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[posterLabelsEnabledKey] = enabled
        }
    }

    suspend fun setCatalogAddonNameEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[catalogAddonNameEnabledKey] = enabled
        }
    }

    suspend fun setCatalogTypeSuffixEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[catalogTypeSuffixEnabledKey] = enabled
        }
    }

    suspend fun setFocusedPosterBackdropExpandEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropExpandEnabledKey] = enabled
            if (!enabled) {
                prefs[focusedPosterBackdropTrailerEnabledKey] = false
                prefs[focusedPosterBackdropTrailerMutedKey] = true
            }
        }
    }

    suspend fun setFocusedPosterBackdropExpandDelaySeconds(seconds: Int) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropExpandDelaySecondsKey] =
                seconds.coerceAtLeast(MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
        }
    }

    suspend fun setFocusedPosterBackdropTrailerEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerEnabledKey] = enabled
            if (!enabled) {
                prefs[focusedPosterBackdropTrailerMutedKey] = true
            }
        }
    }

    suspend fun setFocusedPosterBackdropTrailerMuted(muted: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerMutedKey] = muted
        }
    }

    suspend fun setFocusedPosterBackdropTrailerPlaybackTarget(
        target: FocusedPosterTrailerPlaybackTarget
    ) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerPlaybackTargetKey] = target.name
        }
    }

    suspend fun setPosterCardWidthDp(widthDp: Int) {
        store().edit { prefs ->
            prefs[posterCardWidthDpKey] = positiveOrDefault(widthDp, DEFAULT_POSTER_CARD_WIDTH_DP)
        }
    }

    suspend fun setPosterCardHeightDp(heightDp: Int) {
        store().edit { prefs ->
            prefs[posterCardHeightDpKey] = positiveOrDefault(heightDp, DEFAULT_POSTER_CARD_HEIGHT_DP)
        }
    }

    suspend fun setPosterCardCornerRadiusDp(cornerRadiusDp: Int) {
        store().edit { prefs ->
            prefs[posterCardCornerRadiusDpKey] = cornerRadiusDp
        }
    }

    suspend fun setBlurUnwatchedEpisodes(enabled: Boolean) {
        store().edit { prefs ->
            prefs[blurUnwatchedEpisodesKey] = enabled
        }
    }

    suspend fun setRandomEpisodeEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[randomEpisodeEnabledKey] = enabled
        }
    }

    suspend fun setStartupSplashEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[startupSplashEnabledKey] = enabled
        }
    }

    suspend fun setTrailerCropToFill(enabled: Boolean) {
        store().edit { prefs ->
            prefs[trailerCropToFillKey] = enabled
        }
    }

    suspend fun setAlwaysShowLandscapeClearlogo(enabled: Boolean) {
        store().edit { prefs ->
            prefs[alwaysShowLandscapeClearlogoKey] = enabled
        }
    }

    suspend fun setEpisodeOptionsOverlayStyle(style: EpisodeOptionsOverlayStyle) {
        store().edit { prefs ->
            prefs[episodeOptionsOverlayStyleKey] = style.name
        }
    }

    suspend fun setHomeImdbRatingsVisibility(visibility: HomeImdbRatingsVisibility) {
        store().edit { prefs ->
            prefs[homeImdbRatingsVisibilityKey] = visibility.name
        }
    }

    suspend fun setDetailImdbRatingsVisibility(visibility: DetailImdbRatingsVisibility) {
        store().edit { prefs ->
            prefs[detailImdbRatingsVisibilityKey] = visibility.asEpisodeVisibility().name
        }
    }

    suspend fun setUseEpisodeThumbnailsInCw(enabled: Boolean) {
        store().edit { prefs ->
            prefs[useEpisodeThumbnailsInCwKey] = enabled
        }
    }

    suspend fun setContinueWatchingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[continueWatchingEnabledKey] = enabled
        }
    }

    suspend fun setContinueWatchingCardStyle(style: ContinueWatchingCardStyle) {
        store().edit { prefs ->
            prefs[continueWatchingCardStyleKey] = style.name
        }
    }

    suspend fun setShowUnairedNextUp(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showUnairedNextUpKey] = enabled
        }
    }

    suspend fun setNextUpFromFurthestEpisode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[nextUpFromFurthestEpisodeKey] = enabled
        }
    }

    suspend fun setBlurContinueWatchingNextUp(enabled: Boolean) {
        store().edit { prefs ->
            prefs[blurContinueWatchingNextUpKey] = enabled
        }
    }

    suspend fun setContinueWatchingSortMode(mode: ContinueWatchingSortMode) {
        store().edit { prefs ->
            prefs[continueWatchingSortModeKey] = mode.name
        }
    }

    suspend fun setDetailPageTrailerButtonEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[detailPageTrailerButtonEnabledKey] = enabled
        }
    }

    suspend fun setPreferExternalMetaAddonDetail(enabled: Boolean) {
        store().edit { prefs ->
            prefs[preferExternalMetaAddonDetailKey] = enabled
        }
    }

    suspend fun setHideUnreleasedContent(enabled: Boolean) {
        store().edit { prefs ->
            prefs[hideUnreleasedContentKey] = enabled
        }
    }

    suspend fun setShowFullReleaseDate(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showFullReleaseDateKey] = enabled
        }
    }

    private fun parseCatalogKeys(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed = gson.fromJson<List<String>>(json, type).orEmpty()
            normalizeCatalogOrderKeys(parsed)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeCatalogOrderKeys(keys: List<String>): List<String> {
        return keys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun parseCustomTitles(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type).orEmpty()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun setCustomCatalogTitles(titles: Map<String, String>) {
        store().edit { prefs ->
            val filtered = titles.filterValues { it.isNotBlank() }
            if (filtered.isEmpty()) {
                prefs.remove(customCatalogTitlesKey)
            } else {
                prefs[customCatalogTitlesKey] = gson.toJson(filtered)
            }
        }
    }

    internal suspend fun getHomeCatalogSettingsState(): LocalHomeCatalogSettingsState {
        return readHomeCatalogSettingsState(store().data.first())
    }

    internal suspend fun exportCatalogSettingsToSyncPayload(
        addons: List<Addon>,
        collections: List<Collection>
    ): SyncHomeCatalogPayload {
        return buildHomeCatalogSyncPayload(
            addons = addons,
            collections = collections,
            localState = getHomeCatalogSettingsState()
        )
    }

    suspend fun applyCatalogSettingsFromRemote(payload: SyncHomeCatalogPayload) {
        val sortedItems = payload.items.sortedBy { it.order }
        val remoteOrderKeys = sortedItems.map { item ->
            if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
        }
        val remoteDisabledKeys = sortedItems.filter { !it.enabled }.map { item ->
            if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
        }
        val remoteTitles = sortedItems.associate { item ->
            val key = if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
            key to item.customTitle
        }.filterValues { it.isNotBlank() }

        store().edit { prefs ->
            val local = readHomeCatalogSettingsState(prefs)
            val orderKeys = remoteOrderKeys.withLocalServerKeys(local.orderKeys)
            val disabledKeys = remoteDisabledKeys + local.disabledKeys.filter(ServerCatalog::isServerKey)
            val titles = remoteTitles + local.customTitles.filterKeys(ServerCatalog::isServerKey)
            prefs[hideUnreleasedContentKey] = payload.hideUnreleasedContent
            if (orderKeys.isNotEmpty()) {
                prefs[homeCatalogOrderKeysKey] = gson.toJson(orderKeys)
            } else {
                prefs.remove(homeCatalogOrderKeysKey)
            }
            if (disabledKeys.isNotEmpty()) {
                prefs[disabledHomeCatalogKeysKey] = gson.toJson(disabledKeys)
            } else {
                prefs.remove(disabledHomeCatalogKeysKey)
            }
            if (titles.isNotEmpty()) {
                prefs[customCatalogTitlesKey] = gson.toJson(titles)
            } else {
                prefs.remove(customCatalogTitlesKey)
            }
        }
    }

    private fun readHomeCatalogSettingsState(prefs: Preferences): LocalHomeCatalogSettingsState {
        return LocalHomeCatalogSettingsState(
            orderKeys = parseCatalogKeys(prefs.getStringOrMigrateSet(homeCatalogOrderKeysKey)),
            disabledKeys = parseCatalogKeys(prefs.getStringOrMigrateSet(disabledHomeCatalogKeysKey)).toSet(),
            customTitles = parseCustomTitles(prefs.getStringOrMigrateSet(customCatalogTitlesKey)),
            hideUnreleasedContent = prefs[hideUnreleasedContentKey] ?: false
        )
    }
}

internal val legacySearchDiscoverEnabledKey = booleanPreferencesKey("search_discover_enabled")
internal val discoverLocationKey = stringPreferencesKey("discover_location")
internal val lastNonOffDiscoverLocationKey = stringPreferencesKey("last_non_off_discover_location")
