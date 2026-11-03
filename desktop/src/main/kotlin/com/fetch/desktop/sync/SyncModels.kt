package com.fetch.desktop.sync

import com.fetch.tv.core.streams.ranking.StreamRankingRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A self-hosted Fetch sync server: a Supabase-compatible backend (GoTrue auth under `/auth/v1`,
 * PostgREST under `/rest/v1`, edge functions under `/functions/v1`). [url] is the backend URL and
 * [anonKey] its publishable (anon) key, both as the TV app's Settings › Account stores them.
 */
@Serializable
data class SyncServerConfig(val url: String, val anonKey: String) {
    /** [url] without a trailing slash. */
    val baseUrl: String get() = url.trim().trimEnd('/')

    /** The web page a device sign-in code is approved on (the TV app's `deviceLoginWebBaseUrl`). */
    val deviceLoginWebUrl: String get() = "$baseUrl/link"

    /** The older TV sign-in page, used when the server only has the legacy flow. */
    val legacyTvLoginWebUrl: String get() = "$baseUrl/tv-login"
}

/** A signed-in account on the sync server. */
@Serializable
data class SyncSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val email: String? = null,
) {
    /** True when the access token expires within [marginSec] of [nowEpochSec]. */
    fun expiresSoon(nowEpochSec: Long = System.currentTimeMillis() / 1000, marginSec: Long = 60): Boolean =
        expiresAtEpochSec - nowEpochSec <= marginSec
}

/** What a server's `/.well-known/fetch` document says it supports. */
data class DiscoveredServer(
    val config: SyncServerConfig,
    val emailPasswordAuth: Boolean,
    val tvLogin: Boolean,
    val watchParty: Boolean,
)

/** A pending device (QR / code) sign-in: show [userCode] and [verificationUrlComplete], then poll. */
data class DeviceLogin(
    /** The code polled and exchanged; the same as [userCode] on legacy servers. */
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    /** The URL to open or show as a QR code; it already carries the code. */
    val verificationUrlComplete: String,
    /** The nonce this device generated; polling and the exchange must send it back. */
    val deviceNonce: String,
    val expiresAt: String,
    val pollIntervalSec: Int,
    /** True when the server only had the older `start_tv_login_session`. */
    val legacy: Boolean,
)

/** One poll of a [DeviceLogin]. */
sealed interface DeviceLoginPoll {
    data object Pending : DeviceLoginPoll

    /** Approved on the web; call [SyncAuthClient.exchangeDeviceLogin] next. */
    data object Approved : DeviceLoginPoll

    /** Terminal: `expired`, `used` or `cancelled`, or anything else the server reports. */
    data class Ended(val status: String) : DeviceLoginPoll
}

/** A profile of the sync owner. [profileIndex] is the `profile_id` other calls take; 1 is the primary profile. */
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

/** One profile background the account can pick (`get_member_profile_background_catalog`). */
data class RemoteProfileBackground(
    val id: String,
    val displayName: String,
    /** In the private `membership-profile-backgrounds` bucket. */
    val storagePath: String,
    val assetVersion: Int,
)

/** Whether the sync server holds a PIN for a profile (RPC `sync_pull_profile_locks`). */
data class RemoteProfileLock(
    val profileIndex: Int,
    val pinEnabled: Boolean,
    /** ISO timestamp until which the server refuses PIN attempts, after too many wrong ones. */
    val pinLockedUntil: String? = null,
)

/** The server's verdict on a PIN (RPC `verify_profile_pin`). */
data class RemotePinVerify(val unlocked: Boolean, val retryAfterSeconds: Int = 0)

/** What `set_profile_pin` said. Any other failure is thrown as a [SyncException]. */
enum class RemotePinSetResult {
    SUCCESS,

    /** The profile already has a PIN on the server and the current one was missing or wrong. */
    CURRENT_PIN_REQUIRED,
}

/**
 * A profile's settings blob as stored (RPC `sync_pull_profile_settings_blob`): the TV app's
 * `{"version":1,"features":{<feature>:{<key>:{"type":..,"value":..}}},"parental":{..}}`.
 */
data class RemoteSettingsBlob(
    val profileId: Int,
    val settingsJson: JsonObject,
    val updatedAt: String? = null,
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

/** A saved title (the TV app's `SavedLibraryItem`). A title is keyed by ([id], [type]). */
data class RemoteLibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    /** `POSTER`, `LANDSCAPE` or `SQUARE`. */
    val posterShape: String = "POSTER",
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val addonBaseUrl: String? = null,
    val addedAtEpochMs: Long = 0L,
) {
    val key: RemoteLibraryKey get() = RemoteLibraryKey(id, type)
}

data class RemoteLibraryKey(val contentId: String, val contentType: String)

/** One change from the library's event log. A delete carries only the key fields of [item]. */
data class RemoteLibraryEvent(val eventId: Long, val deleted: Boolean, val item: RemoteLibraryItem)

/**
 * Playback progress for a movie, or one episode of a series. The server stores only these fields;
 * titles and artwork are not synced, so a client fills them from metadata.
 */
data class RemoteProgress(
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedEpochMs: Long,
    /** The server's key; null means [defaultKey]. */
    val progressKey: String? = null,
) {
    /** The key the TV app uses: `<contentId>_s<season>e<episode>` for an episode, else `<contentId>`. */
    val defaultKey: String
        get() = if (season != null && episode != null) "${contentId}_s${season}e${episode}" else contentId

    val key: String get() = progressKey ?: defaultKey
}

/** One change from the progress event log; for a delete only [progress]'s key fields are meaningful. */
data class RemoteProgressEvent(val eventId: Long, val deleted: Boolean, val progress: RemoteProgress)

/** A title (or one episode) marked as watched. */
data class RemoteWatchedItem(
    val contentId: String,
    val contentType: String,
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val watchedAtEpochMs: Long,
) {
    val key: RemoteWatchedKey get() = RemoteWatchedKey(contentId, season, episode)
}

/** Watched items are deleted by content id and episode, without the type, as the TV app does. */
data class RemoteWatchedKey(val contentId: String, val season: Int? = null, val episode: Int? = null)

data class RemoteWatchedEvent(val eventId: Long, val deleted: Boolean, val item: RemoteWatchedItem)

/** An installed Stremio addon, by manifest URL. Lower [order] comes first. */
data class RemoteAddon(
    val url: String,
    val order: Int,
    val enabled: Boolean = true,
    /** A name the user set; null keeps the manifest's. */
    val name: String? = null,
)

/** Any failure the sync server reported. [code] is the HTTP status, when there was one. */
open class SyncException(message: String, val code: Int? = null) : Exception(message)

/** The session is missing, or was rejected and could not be refreshed: sign in again. */
class SyncAuthException(message: String, code: Int? = null) : SyncException(message, code)
