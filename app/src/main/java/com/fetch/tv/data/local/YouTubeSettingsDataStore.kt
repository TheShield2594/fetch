package com.fetch.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.youtube.SponsorBlockCategory
import com.fetch.tv.data.youtube.SponsorBlockMode
import com.fetch.tv.data.youtube.YouTubeFeedFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Each profile's YouTube playback choices. Kept apart from the sign-in so signing out keeps them. */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "youtube_settings"
        const val DEFAULT_SPONSOR_BLOCK_ENABLED = true
        const val DEFAULT_WATCH_HISTORY_ENABLED = true
        const val DEFAULT_AUTOPLAY_ENABLED = true
        val DEFAULT_FEED_FILTER = YouTubeFeedFilter()
        /** TV screens are 1080p or 4K; 1080p keeps 4K streams off devices and networks that struggle. */
        const val DEFAULT_MAX_VIDEO_HEIGHT = 1080
        /** The heights the quality setting offers, lowest first. */
        val VIDEO_HEIGHT_OPTIONS = listOf(720, 1080, 1440, 2160)
        const val DEFAULT_DEARROW_ENABLED = false
        const val DEFAULT_RETURN_DISLIKE_ENABLED = false
        const val DEFAULT_HOME_ROW_ENABLED = false
        val DEFAULT_SPONSOR_BLOCK_MODES: Map<SponsorBlockCategory, SponsorBlockMode> =
            SponsorBlockCategory.entries.associateWith { it.defaultMode }
        const val MAX_RECENT_SEARCHES = 10
    }

    private val sponsorBlockKey = booleanPreferencesKey("sponsor_block_enabled")
    private val watchHistoryKey = booleanPreferencesKey("watch_history_enabled")
    private val autoplayKey = booleanPreferencesKey("autoplay_enabled")
    private val showShortsKey = booleanPreferencesKey("show_shorts")
    private val showLiveKey = booleanPreferencesKey("show_live")
    private val hideWatchedKey = booleanPreferencesKey("hide_watched")
    private val captionsEnabledKey = booleanPreferencesKey("captions_enabled")
    private val captionLanguageKey = stringPreferencesKey("caption_language")
    private val maxVideoHeightKey = intPreferencesKey("max_video_height")
    private val deArrowKey = booleanPreferencesKey("dearrow_enabled")
    private val returnDislikeKey = booleanPreferencesKey("return_dislike_enabled")
    private val homeRowKey = booleanPreferencesKey("home_row_enabled")
    private val recentSearchesKey = stringPreferencesKey("recent_searches")
    private fun sponsorBlockModeKey(category: SponsorBlockCategory) = stringPreferencesKey("sponsor_block_mode_${category.apiName}")

    private fun store(profileId: Int) =
        factory.get(profileId, FEATURE)

    /** Skip the sponsor, self-promotion and subscribe-reminder parts SponsorBlock users marked. */
    val sponsorBlockEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { id ->
        store(id).data.map { it[sponsorBlockKey] ?: DEFAULT_SPONSOR_BLOCK_ENABLED }
    }

    /** Add the videos watched in Fetch to the account's YouTube history, with where they stopped. */
    val watchHistoryEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { id ->
        store(id).data.map { it[watchHistoryKey] ?: DEFAULT_WATCH_HISTORY_ENABLED }
    }

    /** Which Shorts, live streams and watched videos the feeds, search and Up Next leave out. */
    val feedFilter: Flow<YouTubeFeedFilter> = profileManager.activeProfileId.flatMapLatest { id ->
        // The YouTube screen collects this for as long as it's open; an unreadable file means the defaults.
        store(id).data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.map { prefs ->
            YouTubeFeedFilter(
                showShorts = prefs[showShortsKey] ?: DEFAULT_FEED_FILTER.showShorts,
                showLive = prefs[showLiveKey] ?: DEFAULT_FEED_FILTER.showLive,
                hideWatched = prefs[hideWatchedKey] ?: DEFAULT_FEED_FILTER.hideWatched
            )
        }
    }

    suspend fun setShowShorts(show: Boolean) {
        store(profileManager.currentProfileId()).edit { it[showShortsKey] = show }
    }

    suspend fun setShowLive(show: Boolean) {
        store(profileManager.currentProfileId()).edit { it[showLiveKey] = show }
    }

    suspend fun setHideWatched(hide: Boolean) {
        store(profileManager.currentProfileId()).edit { it[hideWatchedKey] = hide }
    }

    /**
     * Captions stay on or off between videos, as on YouTube, and keep the last language picked.
     * Null language means the subtitle language set in Fetch's playback settings.
     */
    suspend fun captionPreference(): Pair<Boolean, String?> {
        val prefs = store(profileManager.currentProfileId()).data.first()
        return (prefs[captionsEnabledKey] ?: false) to prefs[captionLanguageKey]
    }

    suspend fun setCaptionPreference(enabled: Boolean, languageCode: String?) {
        store(profileManager.currentProfileId()).edit { prefs ->
            prefs[captionsEnabledKey] = enabled
            languageCode?.let { prefs[captionLanguageKey] = it }
        }
    }

    /** When a video ends, count down and play YouTube's Up Next pick. */
    val autoplayEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { id ->
        store(id).data.map { it[autoplayKey] ?: DEFAULT_AUTOPLAY_ENABLED }
    }

    suspend fun setAutoplayEnabled(enabled: Boolean) {
        store(profileManager.currentProfileId()).edit { it[autoplayKey] = enabled }
    }

    suspend fun isSponsorBlockEnabled(): Boolean = sponsorBlockEnabled.first()

    /** The tallest video stream to play: 720, 1080, 1440 or 2160. Streams the device can't decode are never picked. */
    val maxVideoHeight: Flow<Int> = profileManager.activeProfileId.flatMapLatest { id ->
        store(id).data.map { prefs ->
            prefs[maxVideoHeightKey]?.takeIf { it in VIDEO_HEIGHT_OPTIONS } ?: DEFAULT_MAX_VIDEO_HEIGHT
        }
    }

    suspend fun setMaxVideoHeight(height: Int) {
        if (height !in VIDEO_HEIGHT_OPTIONS) return
        store(profileManager.currentProfileId()).edit { it[maxVideoHeightKey] = height }
    }

    /** Steps the quality cap to [next] of the stored one, read inside the edit so quick presses each count. */
    suspend fun stepMaxVideoHeight(next: (Int) -> Int) {
        store(profileManager.currentProfileId()).edit { prefs ->
            val current = prefs[maxVideoHeightKey]?.takeIf { it in VIDEO_HEIGHT_OPTIONS } ?: DEFAULT_MAX_VIDEO_HEIGHT
            prefs[maxVideoHeightKey] = next(current)
        }
    }

    /** What playback does with each kind of SponsorBlock segment, while SponsorBlock is on. */
    val sponsorBlockModes: Flow<Map<SponsorBlockCategory, SponsorBlockMode>> = profileManager.activeProfileId.flatMapLatest { id ->
        store(id).data.map { prefs ->
            SponsorBlockCategory.entries.associateWith { category ->
                prefs[sponsorBlockModeKey(category)]
                    ?.let { saved -> SponsorBlockMode.entries.firstOrNull { it.name == saved } }
                    ?: category.defaultMode
            }
        }
    }

    suspend fun setSponsorBlockMode(category: SponsorBlockCategory, mode: SponsorBlockMode) {
        store(profileManager.currentProfileId()).edit { it[sponsorBlockModeKey(category)] = mode.name }
    }

    /** Steps [category]'s mode to [next] of the stored one, read inside the edit so quick presses each count. */
    suspend fun stepSponsorBlockMode(category: SponsorBlockCategory, next: (SponsorBlockMode) -> SponsorBlockMode) {
        store(profileManager.currentProfileId()).edit { prefs ->
            val current = prefs[sponsorBlockModeKey(category)]
                ?.let { saved -> SponsorBlockMode.entries.firstOrNull { it.name == saved } }
                ?: category.defaultMode
            prefs[sponsorBlockModeKey(category)] = next(current).name
        }
    }

    /** Show DeArrow's community titles and thumbnails in place of YouTube's. */
    val deArrowEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { id ->
        // The YouTube screen collects this for as long as it's open; an unreadable file means the default.
        store(id).data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.map { it[deArrowKey] ?: DEFAULT_DEARROW_ENABLED }
    }

    suspend fun setDeArrowEnabled(enabled: Boolean) {
        store(profileManager.currentProfileId()).edit { it[deArrowKey] = enabled }
    }

    /** Show like and dislike counts from Return YouTube Dislike in the player. */
    val returnDislikeEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { id ->
        store(id).data.map { it[returnDislikeKey] ?: DEFAULT_RETURN_DISLIKE_ENABLED }
    }

    suspend fun setReturnDislikeEnabled(enabled: Boolean) {
        store(profileManager.currentProfileId()).edit { it[returnDislikeKey] = enabled }
    }

    /** Show a row of the newest subscription videos on Fetch's home screen. */
    val homeRowEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { id ->
        store(id).data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.map { it[homeRowKey] ?: DEFAULT_HOME_ROW_ENABLED }
    }

    suspend fun setHomeRowEnabled(enabled: Boolean) {
        store(profileManager.currentProfileId()).edit { it[homeRowKey] = enabled }
    }

    /** The profile's last searches on the YouTube screen, newest first. */
    val recentSearches: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { id ->
        store(id).data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.map { decodeRecentSearches(it[recentSearchesKey]) }
    }

    suspend fun addRecentSearch(query: String) {
        val trimmed = query.trim().takeIf { it.isNotEmpty() } ?: return
        store(profileManager.currentProfileId()).edit { prefs ->
            val current = decodeRecentSearches(prefs[recentSearchesKey])
            prefs[recentSearchesKey] = encodeRecentSearches(addRecent(current, trimmed, MAX_RECENT_SEARCHES))
        }
    }

    suspend fun clearRecentSearches() {
        store(profileManager.currentProfileId()).edit { it.remove(recentSearchesKey) }
    }

    suspend fun isWatchHistoryEnabled(): Boolean = watchHistoryEnabled.first()

    suspend fun setSponsorBlockEnabled(enabled: Boolean) {
        store(profileManager.currentProfileId()).edit { it[sponsorBlockKey] = enabled }
    }

    suspend fun setWatchHistoryEnabled(enabled: Boolean) {
        store(profileManager.currentProfileId()).edit { it[watchHistoryKey] = enabled }
    }
}

/** [query] first, with any earlier copy of it (ignoring case) dropped, keeping [max]. */
internal fun addRecent(current: List<String>, query: String, max: Int): List<String> =
    (listOf(query) + current.filterNot { it.equals(query, ignoreCase = true) }).take(max)

// One search per line; a search typed on a remote never holds a line break, but strip any anyway.
internal fun encodeRecentSearches(searches: List<String>): String =
    searches.joinToString("\n") { it.replace('\n', ' ') }

internal fun decodeRecentSearches(stored: String?): List<String> =
    stored?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
