package com.fetch.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
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
    }

    private val sponsorBlockKey = booleanPreferencesKey("sponsor_block_enabled")
    private val watchHistoryKey = booleanPreferencesKey("watch_history_enabled")
    private val autoplayKey = booleanPreferencesKey("autoplay_enabled")
    private val showShortsKey = booleanPreferencesKey("show_shorts")
    private val showLiveKey = booleanPreferencesKey("show_live")
    private val hideWatchedKey = booleanPreferencesKey("hide_watched")
    private val captionsEnabledKey = booleanPreferencesKey("captions_enabled")
    private val captionLanguageKey = stringPreferencesKey("caption_language")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
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
        store().edit { it[showShortsKey] = show }
    }

    suspend fun setShowLive(show: Boolean) {
        store().edit { it[showLiveKey] = show }
    }

    suspend fun setHideWatched(hide: Boolean) {
        store().edit { it[hideWatchedKey] = hide }
    }

    /**
     * Captions stay on or off between videos, as on YouTube, and keep the last language picked.
     * Null language means the subtitle language set in Fetch's playback settings.
     */
    suspend fun captionPreference(): Pair<Boolean, String?> {
        val prefs = store().data.first()
        return (prefs[captionsEnabledKey] ?: false) to prefs[captionLanguageKey]
    }

    suspend fun setCaptionPreference(enabled: Boolean, languageCode: String?) {
        store().edit { prefs ->
            prefs[captionsEnabledKey] = enabled
            languageCode?.let { prefs[captionLanguageKey] = it }
        }
    }

    /** When a video ends, count down and play YouTube's Up Next pick. */
    val autoplayEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { id ->
        store(id).data.map { it[autoplayKey] ?: DEFAULT_AUTOPLAY_ENABLED }
    }

    suspend fun setAutoplayEnabled(enabled: Boolean) {
        store().edit { it[autoplayKey] = enabled }
    }

    suspend fun isSponsorBlockEnabled(): Boolean = sponsorBlockEnabled.first()

    suspend fun isWatchHistoryEnabled(): Boolean = watchHistoryEnabled.first()

    suspend fun setSponsorBlockEnabled(enabled: Boolean) {
        store().edit { it[sponsorBlockKey] = enabled }
    }

    suspend fun setWatchHistoryEnabled(enabled: Boolean) {
        store().edit { it[watchHistoryKey] = enabled }
    }
}
