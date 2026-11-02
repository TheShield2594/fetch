package com.fetch.desktop.sync

import kotlinx.serialization.Serializable

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
