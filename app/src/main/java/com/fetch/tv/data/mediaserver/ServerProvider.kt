package com.fetch.tv.data.mediaserver

import com.fetch.tv.core.tracking.TrackingExternalIds
import com.fetch.tv.domain.model.FilterRowFilters

enum class ServerCapability {
    SEARCH,
    EXTERNAL_ID_LOOKUP,
    USER_STATE_READ,
    USER_STATE_WRITE,
    TRANSCODING
}

enum class ServerSignInMethod {
    /** Address, user name and password typed on the TV or sent from the phone. */
    PASSWORD,

    /** A short code the user approves on another device; the provider finds the servers. */
    LINK_CODE
}

/** A sign-in code shown on the TV while the user approves it at [displayUrl]. */
class ServerLinkCode(
    val id: String,
    val code: String,
    val displayUrl: String,
    val qrUrl: String,
    val expiresAtEpochMs: Long,
    val pollIntervalMs: Long
) {
    override fun toString(): String = "ServerLinkCode(id=$id)"
}

interface ServerProvider {
    val id: String
    val displayName: String
    val capabilities: Set<ServerCapability>
    val minimumVersion: String
    val signInMethod: ServerSignInMethod
        get() = ServerSignInMethod.PASSWORD

    suspend fun signIn(address: String, username: String, password: String): ServerSignIn = unsupported()

    /** Starts a [ServerSignInMethod.LINK_CODE] sign-in. */
    suspend fun startLink(): ServerLinkCode = unsupported()

    /**
     * Null while [link] is waiting for approval. Once approved, one sign-in per server the
     * account can reach; empty when it has none.
     */
    suspend fun completeLink(link: ServerLinkCode): List<ServerSignIn>? = unsupported()

    suspend fun libraries(session: ServerSession): List<ServerLibrary>

    suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle>

    /** A page of [library] narrowed and sorted by [filters], for filter rows on Home. */
    suspend fun filteredLibraryPage(
        session: ServerSession,
        library: ServerLibrary,
        filters: FilterRowFilters,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = unsupported()

    /** Genre names used in [library], for picking a filter row's genre. */
    suspend fun genres(session: ServerSession, library: ServerLibrary): List<String> = unsupported()

    suspend fun details(session: ServerSession, itemId: String): ServerItemDetails

    suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate>

    suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession

    suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) = Unit

    suspend fun signOut(session: ServerSession) = Unit

    suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = unsupported()

    suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> = unsupported()

    suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> = unsupported()

    suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean): Unit = unsupported()

    suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> = unsupported()

    /** Items of [library] that may be [query]; null when the server cannot look up a single title this way. */
    suspend fun lookupTitle(
        session: ServerSession,
        library: ServerLibrary,
        query: ServerTitleQuery
    ): List<ServerIndexEntry>? = null

    suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int
    ): ServerEpisode? = unsupported()
}

data class ServerIndexEntry(
    val itemId: String,
    val ids: TrackingExternalIds,
    val year: Int? = null
)

data class ServerTitleQuery(
    val ids: TrackingExternalIds,
    val name: String?,
    val year: Int?,
    val originalName: String? = null
)

data class ServerEpisode(
    val itemId: String,
    val premiereDate: String?
)

fun ServerProvider.supports(capability: ServerCapability): Boolean = capability in capabilities

/** True when a dotted [version] ("10.9.11", "1.40.2.8395-c67dce28e") is at least [minimum]. */
internal fun isAtLeastVersion(version: String?, minimum: String): Boolean {
    val actual = version?.split('.')?.map { it.takeWhile(Char::isDigit).toIntOrNull() } ?: return false
    if (actual.firstOrNull() == null) return false
    for ((index, required) in minimum.split('.').map(String::toInt).withIndex()) {
        val part = actual.getOrNull(index) ?: 0
        if (part != required) return part > required
    }
    return true
}

private fun unsupported(): Nothing = throw ServerException(ServerFailure.UNSUPPORTED)
