package com.fetch.tv.data.mediaserver.plex

import com.fetch.tv.data.mediaserver.ServerCandidate
import com.fetch.tv.data.mediaserver.ServerCapability
import com.fetch.tv.data.mediaserver.ServerEpisode
import com.fetch.tv.data.mediaserver.ServerException
import com.fetch.tv.data.mediaserver.ServerFailure
import com.fetch.tv.data.mediaserver.ServerIndexEntry
import com.fetch.tv.data.mediaserver.ServerItemDetails
import com.fetch.tv.data.mediaserver.ServerLibrary
import com.fetch.tv.data.mediaserver.ServerLinkCode
import com.fetch.tv.data.mediaserver.ServerMediaKind
import com.fetch.tv.data.mediaserver.ServerPage
import com.fetch.tv.data.mediaserver.ServerPlayMethod
import com.fetch.tv.data.mediaserver.ServerPlaybackEvent
import com.fetch.tv.data.mediaserver.ServerPlaybackEventType
import com.fetch.tv.data.mediaserver.ServerPlaybackRequest
import com.fetch.tv.data.mediaserver.ServerPlaybackSession
import com.fetch.tv.data.mediaserver.ServerProvider
import com.fetch.tv.data.mediaserver.ServerSession
import com.fetch.tv.data.mediaserver.ServerSignIn
import com.fetch.tv.data.mediaserver.ServerSignInMethod
import com.fetch.tv.data.mediaserver.ServerTitle
import com.fetch.tv.data.mediaserver.ServerTitleQuery
import com.fetch.tv.data.mediaserver.ServerTrack
import com.fetch.tv.data.mediaserver.isAtLeastVersion
import com.fetch.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.fetch.tv.data.mediaserver.mediabrowser.buildUrl
import com.fetch.tv.data.mediaserver.mediabrowser.pathSegment
import com.fetch.tv.data.mediaserver.verifiedTitleMatches
import com.fetch.tv.domain.model.FilterRowFilters
import com.fetch.tv.domain.model.FilterRowSort
import com.fetch.tv.domain.model.Subtitle
import com.fetch.tv.domain.model.WatchedFilter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Plex Media Server. Sign-in is a plex.tv PIN approved on another device; the account's
 * servers are then found through plex.tv and each one is reached on its best answering address.
 */
internal class PlexProvider(
    http: OkHttpClient,
    identity: ServerClientIdentity
) : ServerProvider {
    override val id: String = "plex"
    override val displayName: String = "Plex"
    override val minimumVersion: String = "1.30"
    override val signInMethod: ServerSignInMethod = ServerSignInMethod.LINK_CODE
    override val capabilities: Set<ServerCapability> = ServerCapability.entries.toSet()

    private val client = PlexClient(http, identity)
    private val genreKeys = ConcurrentHashMap<String, Map<String, String>>()
    private val playbackDurations = ConcurrentHashMap<String, Long>()

    override suspend fun startLink(): ServerLinkCode {
        val response = client.execute("POST", PlexClient.PLEX_TV, "/api/v2/pins", token = null, query = mapOf("strong" to "false"))
        val pin = client.json.decodeFromString(PlexPin.serializer(), response.body)
        return ServerLinkCode(
            id = pin.id.toString(),
            code = pin.code,
            displayUrl = LINK_DISPLAY_URL,
            qrUrl = LINK_URL,
            expiresAtEpochMs = System.currentTimeMillis() + (pin.expiresIn ?: DEFAULT_PIN_LIFETIME_S) * 1_000L,
            pollIntervalMs = PIN_POLL_INTERVAL_MS
        )
    }

    override suspend fun completeLink(link: ServerLinkCode): List<ServerSignIn>? {
        val pin = try {
            client.get(
                PlexClient.PLEX_TV,
                "/api/v2/pins/${pathSegment(link.id)}",
                PlexPin.serializer(),
                token = null,
                query = mapOf("code" to link.code)
            )
        } catch (error: ServerException) {
            // plex.tv forgets a PIN once it expires.
            throw if (error.failure == ServerFailure.NOT_FOUND) ServerException(ServerFailure.AUTH_REQUIRED) else error
        }
        val accountToken = pin.authToken?.takeIf { it.isNotBlank() } ?: return null
        return discover(accountToken)
    }

    private suspend fun discover(accountToken: String): List<ServerSignIn> {
        val account = client.get(PlexClient.PLEX_TV, "/api/v2/user", PlexAccount.serializer(), accountToken)
        val servers = client.get(
            PlexClient.PLEX_TV,
            "/api/v2/resources",
            ListSerializer(PlexResource.serializer()),
            accountToken,
            mapOf("includeHttps" to "1", "includeRelay" to "1", "includeIPv6" to "1")
        ).filter { it.isServer }
        if (servers.isEmpty()) return emptyList()
        val supported = servers.filter { isAtLeastVersion(it.productVersion, minimumVersion) }
        if (supported.isEmpty()) throw ServerException(ServerFailure.UNSUPPORTED)
        val userName = account.username ?: account.title ?: account.email.orEmpty()
        val signIns = coroutineScope {
            supported.map { server ->
                async {
                    val token = server.accessToken?.takeIf { it.isNotBlank() } ?: accountToken
                    val address = reachableAddress(server, token) ?: return@async null
                    ServerSignIn(
                        address = address,
                        serverName = server.name?.takeIf { it.isNotBlank() } ?: address.toHttpUrlOrNull()?.host.orEmpty(),
                        serverId = server.clientIdentifier,
                        userId = account.id.toString(),
                        userName = userName,
                        token = token
                    )
                }
            }.awaitAll().filterNotNull()
        }
        if (signIns.isEmpty()) throw ServerException(ServerFailure.UNREACHABLE)
        return signIns
    }

    /** The first address that answers as this server, trying local, then remote, then relayed ones. */
    private suspend fun reachableAddress(server: PlexResource, token: String): String? = coroutineScope {
        orderedConnections(server.connections).map { connection ->
            async {
                val address = connection.uri.trimEnd('/')
                val identity = try {
                    client.json.decodeFromString(
                        PlexResponse.serializer(),
                        client.execute("GET", address, "/identity", token, probe = true).body
                    ).container
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@async null
                }
                address.takeIf { identity.machineIdentifier == null || identity.machineIdentifier == server.clientIdentifier }
            }
        }.awaitAll().firstOrNull { it != null }
    }

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> =
        container(session, "/library/sections").directories.mapNotNull { section ->
            val kind = libraryKind(section.type) ?: return@mapNotNull null
            ServerLibrary(id = section.key, name = section.title.orEmpty(), kind = kind)
        }

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = titlePage(
        session,
        "/library/sections/${pathSegment(library.id)}/all",
        mapOf("type" to library.kind.plexType(), "sort" to "addedAt:desc") + page(start, limit)
    )

    override suspend fun filteredLibraryPage(
        session: ServerSession,
        library: ServerLibrary,
        filters: FilterRowFilters,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> {
        val genreKey = filters.genre?.let { name ->
            genreKeys(session, library)[name] ?: return ServerPage(emptyList(), 0)
        }
        return titlePage(
            session,
            "/library/sections/${pathSegment(library.id)}/all",
            mapOf("type" to library.kind.plexType()) + filterQuery(filters, genreKey) + page(start, limit)
        )
    }

    override suspend fun genres(session: ServerSession, library: ServerLibrary): List<String> =
        genreKeys(session, library).keys.toList()

    private suspend fun genreKeys(session: ServerSession, library: ServerLibrary): Map<String, String> {
        val cacheKey = "${session.connection.id}:${library.id}"
        genreKeys[cacheKey]?.let { return it }
        val genres = container(
            session,
            "/library/sections/${pathSegment(library.id)}/genre",
            mapOf("type" to library.kind.plexType())
        ).directories
            .mapNotNull { genre -> genre.title?.takeIf(String::isNotBlank)?.let { it to genre.key } }
            .toMap()
        genreKeys[cacheKey] = genres
        return genres
    }

    override suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = titlePage(session, "/library/collections/${pathSegment(collectionId)}/children", page(start, limit))

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> {
        val mapper = mapper(session)
        val items = coroutineScope {
            libraries.map { library ->
                async {
                    container(
                        session,
                        "/library/sections/${pathSegment(library.id)}/all",
                        mapOf(
                            "type" to kind.plexType(),
                            "title" to query,
                            "includeGuids" to "1"
                        ) + page(0, limit)
                    ).metadata
                }
            }.awaitAll().flatten()
        }
        return items.mapNotNull(mapper::title).distinctBy { it.preview.id }.take(limit)
    }

    override suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> {
        val items = try {
            container(session, "/hubs/continueWatching/items", page(0, limit)).metadata
        } catch (error: ServerException) {
            // Servers before the Continue Watching hub only have On Deck.
            if (error.failure != ServerFailure.NOT_FOUND) throw error
            container(session, "/library/onDeck", page(0, limit)).metadata
        }
        val libraries = session.connection.selectedLibraries
        val selectedIds = libraries.map { it.id }.toSet()
        val mapper = mapper(session)
        return items
            .filter { item -> item.librarySectionID?.toString()?.let { it in selectedIds } ?: true }
            .mapNotNull(mapper::resumeTitle)
            .filter { title ->
                val kind = ServerMediaKind.fromContentType(title.preview.apiType)
                kind != null && libraries.any { it.holds(kind) }
            }
            .distinctBy { it.preview.id }
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val item = metadata(session, itemId)
        val episodes = if (item.mediaKind() == ServerMediaKind.SERIES) leaves(session, itemId) else emptyList()
        val mapper = mapper(session)
        return ServerItemDetails(
            meta = mapper.details(item, episodes),
            externalIds = item.externalIds(),
            userStates = (if (episodes.isEmpty()) listOf(item) else episodes).map(mapper::userState)
        )
    }

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> =
        mapper(session).candidates(metadata(session, itemId))

    override suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean) {
        client.execute(
            method = "GET",
            baseUrl = session.connection.address,
            path = if (played) "/:/scrobble" else "/:/unscrobble",
            token = session.token,
            query = mapOf("identifier" to LIBRARY_IDENTIFIER, "key" to itemId)
        )
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> {
        val result = container(
            session,
            "/library/sections/${pathSegment(library.id)}/all",
            mapOf("type" to library.kind.plexType(), "includeGuids" to "1") + page(start, limit)
        )
        return ServerPage(
            items = result.metadata.map { ServerIndexEntry(itemId = it.ratingKey, ids = it.externalIds()) },
            totalCount = result.totalSize ?: result.size
        )
    }

    /** Plex filters a section by title (a "contains" match); the original title is tried once when that finds nothing. */
    override suspend fun lookupTitle(
        session: ServerSession,
        library: ServerLibrary,
        query: ServerTitleQuery
    ): List<ServerIndexEntry>? {
        val name = query.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val found = titleEntries(session, library, name)
        if (verifiedTitleMatches(found, query).isNotEmpty()) return found
        val original = query.originalName?.trim()?.takeIf { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
            ?: return found
        return found + titleEntries(session, library, original)
    }

    private suspend fun titleEntries(session: ServerSession, library: ServerLibrary, title: String): List<ServerIndexEntry> =
        container(
            session,
            "/library/sections/${pathSegment(library.id)}/all",
            mapOf("type" to library.kind.plexType(), "includeGuids" to "1", "title" to title) + page(0, TITLE_LOOKUP_LIMIT)
        ).metadata.map { ServerIndexEntry(itemId = it.ratingKey, ids = it.externalIds(), year = it.year) }

    override suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int
    ): ServerEpisode? =
        leaves(session, seriesItemId)
            .filter { it.parentIndex == season && it.index == episode && it.media.isNotEmpty() }
            .singleOrNull()
            ?.let { ServerEpisode(itemId = it.ratingKey, premiereDate = it.originallyAvailableAt) }

    override suspend fun preparePlayback(
        session: ServerSession,
        request: ServerPlaybackRequest
    ): ServerPlaybackSession {
        val itemId = request.target.item.itemId
        val item = metadata(session, itemId)
        val mediaIndex = item.media.indexOfFirst { it.id.toString() == request.target.mediaSourceId }.coerceAtLeast(0)
        val media = item.media.getOrNull(mediaIndex) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val part = media.parts.firstOrNull() ?: throw ServerException(ServerFailure.NOT_FOUND)
        val directPlay = request.capabilities.allowDirectPlay && part.key != null
        if (!directPlay && (request.audioStreamIndex != null || request.subtitleStreamIndex != null)) {
            selectStreams(session, part.id, request.audioStreamIndex, request.subtitleStreamIndex)
        }
        val playSessionId = UUID.randomUUID().toString()
        item.duration?.let { playbackDurations[playSessionId] = it }
        val subtitles = part.streams
            .filter { it.isSubtitle && it.key != null }
            .map { stream ->
                Subtitle(
                    id = "${media.id}:${stream.id}",
                    url = buildUrl(session.connection.address, stream.key!!, mapOf("X-Plex-Token" to session.token)),
                    lang = stream.languageCode ?: "und",
                    addonName = stream.extendedDisplayTitle ?: stream.displayTitle ?: displayName,
                    addonLogo = null,
                    isStreamProvided = true
                )
            }
        val audioTracks = part.streams.filter { it.isAudio }.map { it.track(request.audioStreamIndex) }
        if (directPlay) {
            return ServerPlaybackSession(
                target = request.target,
                mediaSourceId = media.id.toString(),
                url = buildUrl(session.connection.address, part.key!!, mapOf("X-Plex-Token" to session.token)),
                headers = client.headers(session.token),
                subtitles = subtitles,
                playSessionId = playSessionId,
                playMethod = ServerPlayMethod.DIRECT_PLAY,
                audioTracks = audioTracks
            )
        }
        val params = transcodeParams(session, itemId, mediaIndex, playSessionId, burnSubtitles = request.subtitleStreamIndex != null)
        val decision = try {
            container(session, "/video/:/transcode/universal/decision", params)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val videoDecision = decision?.metadata?.firstOrNull()?.media?.firstOrNull()?.parts?.firstOrNull()
            ?.streams?.firstOrNull { it.isVideo }?.decision
        val method = if (videoDecision == "copy") ServerPlayMethod.DIRECT_STREAM else ServerPlayMethod.TRANSCODE
        return ServerPlaybackSession(
            target = request.target,
            mediaSourceId = media.id.toString(),
            url = buildUrl(session.connection.address, "/video/:/transcode/universal/start.m3u8", params),
            headers = client.headers(session.token),
            subtitles = subtitles,
            playSessionId = playSessionId,
            playMethod = method,
            audioTracks = audioTracks,
            burnInSubtitles = part.streams.filter { it.isSubtitle && it.key == null }.map { it.track(request.subtitleStreamIndex) },
            transcodeReasons = listOfNotNull(decision?.transcodeDecisionText?.takeIf { method == ServerPlayMethod.TRANSCODE })
        )
    }

    override suspend fun report(
        session: ServerSession,
        playback: ServerPlaybackSession,
        event: ServerPlaybackEvent
    ) {
        val itemId = playback.target.item.itemId
        val state = when {
            event.type == ServerPlaybackEventType.STOP -> "stopped"
            event.isPaused || event.type == ServerPlaybackEventType.PAUSE -> "paused"
            else -> "playing"
        }
        val sessionId = playback.playSessionId
        try {
            client.execute(
                method = "GET",
                baseUrl = session.connection.address,
                path = "/:/timeline",
                token = session.token,
                query = mapOf(
                    "ratingKey" to itemId,
                    "key" to "/library/metadata/$itemId",
                    "identifier" to LIBRARY_IDENTIFIER,
                    "state" to state,
                    "time" to event.positionMs.coerceAtLeast(0L).toString(),
                    "duration" to sessionId?.let(playbackDurations::get)?.toString(),
                    "X-Plex-Session-Identifier" to sessionId
                )
            )
        } finally {
            // A failed last report still ends the session, so the server's transcoder is freed.
            if (event.type == ServerPlaybackEventType.STOP && sessionId != null) {
                playbackDurations.remove(sessionId)
                if (playback.playMethod != ServerPlayMethod.DIRECT_PLAY) stopTranscode(session, sessionId)
            }
        }
    }

    private suspend fun stopTranscode(session: ServerSession, sessionId: String) {
        try {
            client.execute(
                method = "GET",
                baseUrl = session.connection.address,
                path = "/video/:/transcode/universal/stop",
                token = session.token,
                query = mapOf("session" to sessionId)
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: ServerException) {
            // The server ends an idle transcode on its own.
        }
    }

    private suspend fun selectStreams(session: ServerSession, partId: Long, audioStreamId: Int?, subtitleStreamId: Int?) {
        client.execute(
            method = "PUT",
            baseUrl = session.connection.address,
            path = "/library/parts/$partId",
            token = session.token,
            query = mapOf(
                "audioStreamID" to audioStreamId?.toString(),
                "subtitleStreamID" to subtitleStreamId?.toString(),
                "allParts" to "1"
            )
        )
    }

    private fun transcodeParams(
        session: ServerSession,
        itemId: String,
        mediaIndex: Int,
        playSessionId: String,
        burnSubtitles: Boolean
    ): Map<String, String?> = mapOf(
        "path" to "/library/metadata/$itemId",
        "mediaIndex" to mediaIndex.toString(),
        "partIndex" to "0",
        "protocol" to "hls",
        "fastSeek" to "1",
        "directPlay" to "0",
        "directStream" to "1",
        "directStreamAudio" to "1",
        "videoQuality" to "100",
        "maxVideoBitrate" to MAX_VIDEO_BITRATE_KBPS,
        "subtitles" to if (burnSubtitles) "burn" else "none",
        "session" to playSessionId,
        "X-Plex-Session-Identifier" to playSessionId,
        "X-Plex-Client-Profile-Extra" to CLIENT_PROFILE_EXTRA
    ) + client.headers(session.token)

    private fun PlexStream.track(requested: Int?): ServerTrack = ServerTrack(
        index = id.toInt(),
        label = extendedDisplayTitle ?: displayTitle ?: language ?: id.toString(),
        language = languageCode,
        selected = if (requested != null) id.toInt() == requested else selected
    )

    private suspend fun titlePage(session: ServerSession, path: String, query: Map<String, String?>): ServerPage<ServerTitle> {
        val result = container(session, path, query + ("includeGuids" to "1"))
        val mapper = mapper(session)
        return ServerPage(result.metadata.mapNotNull(mapper::title), result.totalSize ?: result.size)
    }

    private suspend fun metadata(session: ServerSession, itemId: String): PlexMetadata =
        container(session, "/library/metadata/${pathSegment(itemId)}", mapOf("includeGuids" to "1"))
            .metadata.firstOrNull() ?: throw ServerException(ServerFailure.NOT_FOUND)

    private suspend fun leaves(session: ServerSession, showId: String): List<PlexMetadata> =
        container(session, "/library/metadata/${pathSegment(showId)}/allLeaves", mapOf("includeGuids" to "1")).metadata

    private suspend fun container(
        session: ServerSession,
        path: String,
        query: Map<String, String?> = emptyMap()
    ): PlexContainer = client.get(session.connection.address, path, PlexResponse.serializer(), session.token, query).container

    private fun mapper(session: ServerSession) = PlexMapper(session.connection.address, session.token, session.connection.id)

    private fun page(start: Int, limit: Int): Map<String, String?> = mapOf(
        "X-Plex-Container-Start" to start.toString(),
        "X-Plex-Container-Size" to limit.toString()
    )

    internal companion object {
        const val LINK_URL = "https://plex.tv/link"
        const val LINK_DISPLAY_URL = "plex.tv/link"
        const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"
        const val PIN_POLL_INTERVAL_MS = 2_000L
        const val DEFAULT_PIN_LIFETIME_S = 900L
        const val MAX_VIDEO_BITRATE_KBPS = "200000"
        const val TITLE_LOOKUP_LIMIT = 20

        // Let the server copy HEVC and Dolby audio into the HLS stream instead of converting them.
        const val CLIENT_PROFILE_EXTRA =
            "append-transcode-target-codec(type=videoProfile&context=streaming&protocol=hls&videoCodec=hevc)+" +
                "append-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=ac3,eac3)"

        /** Connections ordered by preference: on the LAN, then direct over the internet, then plex.tv's relay. */
        fun orderedConnections(connections: List<PlexResourceConnection>): List<PlexResourceConnection> =
            connections.sortedWith(
                compareBy<PlexResourceConnection> {
                    when {
                        it.relay -> 2
                        it.local -> 0
                        else -> 1
                    }
                }.thenBy { it.ipv6 }
            )

        /** Plex's filter syntax: `year>>=1999` is "after 1999", `year<<=2011` "before 2011". */
        fun filterQuery(filters: FilterRowFilters, genreKey: String?): Map<String, String?> = mapOf(
            "genre" to genreKey,
            "year>>" to filters.yearFrom?.minus(1)?.toString(),
            "year<<" to filters.yearTo?.plus(1)?.toString(),
            "unwatched" to when (filters.watched) {
                WatchedFilter.ANY -> null
                WatchedFilter.WATCHED -> "0"
                WatchedFilter.UNWATCHED -> "1"
            },
            "audienceRating>>" to filters.minRating?.let { (it - 0.05).toString() },
            "sort" to sortField(filters.sort) + (if (filters.descending) ":desc" else "")
        )

        private fun sortField(sort: FilterRowSort): String = when (sort) {
            FilterRowSort.DATE_ADDED -> "addedAt"
            FilterRowSort.TITLE -> "titleSort"
            FilterRowSort.RELEASE_DATE -> "originallyAvailableAt"
            FilterRowSort.RATING -> "audienceRating"
            FilterRowSort.RANDOM -> "random"
        }
    }
}
