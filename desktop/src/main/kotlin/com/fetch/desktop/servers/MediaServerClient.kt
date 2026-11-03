package com.fetch.desktop.servers

import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.domain.model.Meta
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

    /**
     * Recently added movies and series in [lib]. A title with an IMDb id has that id; one without
     * has its encoded [ServerItemRef] (`srv1:<account>:<item>`), for [details] and [streamsForItem].
     */
    suspend fun latest(account: ServerAccount, lib: ServerLib, limit: Int): List<MetaPreview>

    /**
     * The page of [lib] starting at [start] (at most [limit] items) in [sort] order, with the
     * library's total count. Ids follow [latest]: the IMDb id when there is one, else a [ServerItemRef].
     */
    suspend fun browse(account: ServerAccount, lib: ServerLib, start: Int, limit: Int, sort: ServerSort = ServerSort.ADDED): ServerPageResult

    /** A page of [lib] narrowed and sorted by a filter row's [filters], applied by the server (TV: `filteredLibraryPage`). */
    suspend fun filteredPage(account: ServerAccount, lib: ServerLib, filters: com.fetch.desktop.FilterRowFilters, start: Int, limit: Int): ServerPageResult

    /** The genres [lib] has, for a filter row's genre picker. */
    suspend fun genres(account: ServerAccount, lib: ServerLib): List<String>

    /**
     * Full metadata of the server movie or series [itemId] (a raw server item id, not a [ServerItemRef]).
     * The meta's id is the item's [ServerItemRef]; a series lists every episode in `videos`, each
     * with its own [ServerItemRef] id. Null when the server has no such item.
     */
    suspend fun details(account: ServerAccount, itemId: String): Meta?

    /** Every direct-play version of the server movie or episode [itemId] (a raw server item id). */
    suspend fun streamsForItem(account: ServerAccount, itemId: String): List<ServerStream>

    /**
     * An HLS URL (token included) that has the server transcode [stream] to H.264/AAC stereo at
     * about [maxBitrateKbps], for links too slow to play the file directly. Null when unsupported.
     */
    fun transcodeUrl(account: ServerAccount, stream: ServerStream, maxBitrateKbps: Int): String?

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
