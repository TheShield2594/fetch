package com.fetch.desktop.servers

import com.fetch.tv.domain.model.MetaPreview
import okhttp3.OkHttpClient

/**
 * One kind of media server. Every call blocks on OkHttp inside Dispatchers.IO and throws
 * [ServerAuthException] when the token is no longer accepted, [ServerException] otherwise.
 */
interface MediaServerClient {
    val kind: ServerKind

    /** The account's movie and TV libraries (all selected). */
    suspend fun libraries(account: ServerAccount): List<ServerLib>

    /**
     * Every version of the movie, or of the episode [season]x[episode] of the series, that the
     * account's selected libraries hold for [imdbId]. Looked up by IMDb id, falling back to a
     * [title] search verified against the IMDb id. Empty when the server does not have it.
     * [type] is "movie" or "series".
     */
    suspend fun streams(
        account: ServerAccount,
        imdbId: String,
        type: String,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
    ): List<ServerStream>

    /** Recently added titles in [lib] that have an IMDb id, as previews whose id is that IMDb id. */
    suspend fun latest(account: ServerAccount, lib: ServerLib, limit: Int): List<MetaPreview>

    /** The server's continue-watching items that have an IMDb id. */
    suspend fun resume(account: ServerAccount, limit: Int): List<ServerResume>

    /** Marks [stream]'s item watched. Best effort: false when the server refused or was unreachable. */
    suspend fun markPlayed(account: ServerAccount, stream: ServerStream): Boolean

    /**
     * Saves the playback position of [stream]; [stopped] ends the server's playback session.
     * Best effort: false when the server refused or was unreachable.
     */
    suspend fun reportPosition(account: ServerAccount, stream: ServerStream, positionMs: Long, stopped: Boolean = false): Boolean

    /** Ends the server session behind [account]'s token, when the server has one. Never throws. */
    suspend fun signOut(account: ServerAccount)
}

/** The client for [kind]: a [MediaBrowserServerClient] for Jellyfin and Emby, a [PlexServerClient] for Plex. */
fun clientFor(kind: ServerKind, http: OkHttpClient, deviceId: String): MediaServerClient = when (kind) {
    ServerKind.JELLYFIN, ServerKind.EMBY -> MediaBrowserServerClient(kind, http, deviceId)
    ServerKind.PLEX -> PlexServerClient(http, deviceId)
}
