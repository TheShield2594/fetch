package com.fetch.desktop.servers

import com.fetch.desktop.AppInfo
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaCastMember
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A plex.tv sign-in code the user enters at [url] before [expiresAtEpochMs]. */
data class PlexLink(
    val id: Long,
    val code: String,
    val url: String = "https://plex.tv/link",
    val expiresAtEpochMs: Long
)

/**
 * Plex Media Server. Sign-in is a plex.tv PIN approved in a browser; the account's servers are
 * then found through plex.tv, each reached on the first of its addresses that answers.
 */
class PlexServerClient(
    http: OkHttpClient,
    private val deviceId: String,
    private val deviceName: String = defaultDeviceName(),
    private val plexTv: String = PLEX_TV
) : MediaServerClient {
    override val kind: ServerKind = ServerKind.PLEX

    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    // Finds which of a server's addresses answers, so it gives up quickly.
    private val probeHttp = this.http.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .build()
    private val index = ImdbIndex()

    /** Asks plex.tv for a sign-in code. */
    suspend fun startLink(): PlexLink {
        val response = http.send(LABEL, "POST", buildUrl(plexTv, "/api/v2/pins", mapOf("strong" to "false")), headers(null))
        val pin = decode(PlexPin.serializer(), response.body)
        return PlexLink(
            id = pin.id,
            code = pin.code,
            expiresAtEpochMs = System.currentTimeMillis() + (pin.expiresIn ?: DEFAULT_PIN_LIFETIME_S) * 1_000L
        )
    }

    /** The account token once [link] is approved; null while it waits. Throws once it has expired. */
    suspend fun pollLink(link: PlexLink): String? {
        if (System.currentTimeMillis() > link.expiresAtEpochMs) throw ServerException("The Plex code expired")
        val response = try {
            http.send(LABEL, "GET", buildUrl(plexTv, "/api/v2/pins/${link.id}", mapOf("code" to link.code)), headers(null))
        } catch (e: ServerException) {
            // plex.tv forgets a PIN once it expires.
            throw if (e.status == 404) ServerException("The Plex code expired", 404) else e
        }
        return decode(PlexPin.serializer(), response.body).authToken?.takeIf { it.isNotBlank() }
    }

    /**
     * The Plex Media Servers [token]'s account can reach, each with its own access token, the
     * best answering address (local, then remote, then relay) and its libraries. Servers older
     * than 1.30 or unreachable are left out; throws when that leaves none of several.
     */
    suspend fun servers(token: String): List<ServerAccount> {
        val user = decode(PlexUser.serializer(), http.send(LABEL, "GET", buildUrl(plexTv, "/api/v2/user"), headers(token)).body)
        val resources = decode(
            ListSerializer(PlexResource.serializer()),
            http.send(
                LABEL,
                "GET",
                buildUrl(plexTv, "/api/v2/resources", mapOf("includeHttps" to "1", "includeRelay" to "1", "includeIPv6" to "1")),
                headers(token)
            ).body
        ).filter { it.isServer }
        if (resources.isEmpty()) return emptyList()
        val supported = resources.filter { isAtLeastVersion(it.productVersion, MINIMUM_VERSION) }
        if (supported.isEmpty()) throw ServerException("These Plex servers are too old; Fetch needs Plex Media Server $MINIMUM_VERSION or newer")
        val userName = user.username ?: user.title ?: user.email.orEmpty()
        val accounts = coroutineScope {
            supported.map { server ->
                async {
                    val serverToken = server.accessToken?.takeIf { it.isNotBlank() } ?: token
                    val address = reachableAddress(server, serverToken) ?: return@async null
                    val account = ServerAccount(
                        id = UUID.randomUUID().toString(),
                        kind = ServerKind.PLEX,
                        name = server.name?.takeIf { it.isNotBlank() } ?: address.toHttpUrlOrNull()?.host.orEmpty(),
                        address = address,
                        serverId = server.clientIdentifier,
                        userId = user.id.toString(),
                        userName = userName,
                        token = serverToken
                    )
                    account.copy(libraries = runCatching { libraries(account) }.getOrDefault(emptyList()))
                }
            }.awaitAll().filterNotNull()
        }
        if (accounts.isEmpty()) throw ServerException("Couldn't reach any of this account's Plex servers")
        return accounts
    }

    /** The first address, in preference order, that answers `/identity` as this server. */
    private suspend fun reachableAddress(server: PlexResource, token: String): String? = coroutineScope {
        orderedConnections(server.connections).map { connection ->
            async {
                val address = connection.uri.trimEnd('/')
                val identity = try {
                    decode(PlexResponse.serializer(), probeHttp.send(LABEL, "GET", buildUrl(address, "/identity"), headers(token)).body).container
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@async null
                }
                address.takeIf { identity.machineIdentifier == null || identity.machineIdentifier == server.clientIdentifier }
            }
        }.awaitAll().firstOrNull { it != null }
    }

    override suspend fun libraries(account: ServerAccount): List<ServerLib> =
        container(account, "/library/sections").directories.mapNotNull { section ->
            val type = when (section.type) {
                "movie" -> "movie"
                "show" -> "series"
                else -> return@mapNotNull null
            }
            ServerLib(section.key, section.title.orEmpty(), type)
        }

    override suspend fun streams(
        account: ServerAccount,
        imdbId: String,
        type: String,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
    ): List<ServerStream> {
        val series = type == "series"
        if (series && (season == null || episode == null)) return emptyList()
        val libs = account.libraries.filter { it.selected && it.type == (if (series) "series" else "movie") }
        val titleKeys = libs.flatMap { findTitle(account, it, imdbId, title) }.distinct()
        val itemKeys = if (series) {
            titleKeys.flatMap { show ->
                container(account, "/library/metadata/${pathSegment(show)}/allLeaves").metadata
                    .filter { it.parentIndex == season && it.index == episode && it.media.isNotEmpty() }
                    .map { it.ratingKey }
            }
        } else {
            titleKeys
        }
        return itemKeys.distinct().flatMap { key ->
            container(account, "/library/metadata/${pathSegment(key)}", mapOf("includeGuids" to "1")).metadata.flatMap { streamsOf(account, it) }
        }
    }

    /** Items in [lib] whose IMDb guid is [imdb]: a title search verified by guid, else a full index of the library. */
    private suspend fun findTitle(account: ServerAccount, lib: ServerLib, imdb: String, title: String?): List<String> {
        val path = "/library/sections/${pathSegment(lib.id)}/all"
        val base = mapOf("type" to plexType(lib), "includeGuids" to "1")
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            val found = container(account, path, base + ("title" to name) + page(0, TITLE_LOOKUP_LIMIT)).metadata
                .filter { it.imdb() == imdb }
                .map { it.ratingKey }
            if (found.isNotEmpty()) return found
        }
        return index.lookup("${account.id}:${lib.id}", imdb) { start, size ->
            container(account, path, base + page(start, size)).metadata.map { it.imdb() to it.ratingKey }
        }
    }

    private fun streamsOf(account: ServerAccount, item: PlexMetadata): List<ServerStream> =
        item.media.mapNotNull { media ->
            val part = media.parts.firstOrNull { it.key != null } ?: return@mapNotNull null
            ServerStream(
                accountId = account.id,
                itemId = item.ratingKey,
                mediaSourceId = media.id.toString(),
                url = buildUrl(account.address, part.key!!, mapOf("X-Plex-Token" to account.token)),
                fileName = part.file?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() },
                sizeBytes = media.parts.mapNotNull { it.size }.takeIf { it.isNotEmpty() }?.sum(),
                width = media.width,
                height = media.height,
                videoCodec = media.videoCodec,
                audioCodec = media.audioCodec,
                audioChannels = media.audioChannels,
                bitrate = media.bitrate?.times(1_000L),
                durationMs = media.duration ?: item.duration,
                resumePositionMs = item.viewOffset?.takeIf { it > 0 },
                subtitles = part.streams.filter { it.isSubtitle && !it.key.isNullOrBlank() }.map { stream ->
                    ServerSubtitle(
                        url = buildUrl(account.address, stream.key!!, mapOf("X-Plex-Token" to account.token)),
                        language = (stream.languageCode ?: stream.language)?.takeIf { it.isNotBlank() },
                        title = (stream.title ?: stream.extendedDisplayTitle ?: stream.displayTitle)?.takeIf { it.isNotBlank() },
                        forced = stream.forced,
                        default = stream.default
                    )
                }
            )
        }

    override suspend fun streamsForItem(account: ServerAccount, itemId: String): List<ServerStream> =
        metadata(account, itemId)?.let { streamsOf(account, it) }.orEmpty()

    override fun transcodeUrl(account: ServerAccount, stream: ServerStream, maxBitrateKbps: Int): String? {
        val session = UUID.randomUUID().toString()
        val bitrate = maxBitrateKbps.coerceAtLeast(MIN_TRANSCODE_KBPS).toString()
        return buildUrl(
            account.address,
            "/video/:/transcode/universal/start.m3u8",
            mapOf(
                "path" to "/library/metadata/${stream.itemId}",
                "partIndex" to "0",
                "protocol" to "hls",
                "fastSeek" to "1",
                "directPlay" to "0",
                "directStream" to "1",
                "directStreamAudio" to "0",
                "videoQuality" to "100",
                "videoBitrate" to bitrate,
                "maxVideoBitrate" to bitrate,
                // Stereo AAC: a slow link has no room for multichannel audio.
                "X-Plex-Client-Profile-Extra" to "add-limitation(scope=videoAudioCodec&scopeName=*&type=upperBound&name=audio.channels&value=2)",
                "subtitles" to "none",
                "session" to session,
                "X-Plex-Session-Identifier" to session
            ) + headers(account.token)
        )
    }

    /** The item [ratingKey] with its media, or null when the server has no such item. */
    private suspend fun metadata(account: ServerAccount, ratingKey: String): PlexMetadata? = try {
        container(account, "/library/metadata/${pathSegment(ratingKey)}", mapOf("includeGuids" to "1")).metadata.firstOrNull()
    } catch (e: ServerException) {
        if (e.status == 404 || e.status == 400) null else throw e
    }

    override suspend fun latest(account: ServerAccount, lib: ServerLib, limit: Int): List<MetaPreview> {
        val items = container(
            account,
            "/library/sections/${pathSegment(lib.id)}/all",
            mapOf("type" to plexType(lib), "sort" to "addedAt:desc", "includeGuids" to "1") + page(0, limit.coerceIn(1, 200))
        ).metadata
        return items.mapNotNull { preview(account, it) }.take(limit)
    }

    override suspend fun browse(account: ServerAccount, lib: ServerLib, start: Int, limit: Int, sort: ServerSort): ServerPageResult {
        val from = start.coerceAtLeast(0)
        val order = when (sort) {
            ServerSort.ADDED -> "addedAt:desc"
            ServerSort.TITLE -> "titleSort"
            ServerSort.YEAR -> "originallyAvailableAt:desc"
        }
        val result = container(
            account,
            "/library/sections/${pathSegment(lib.id)}/all",
            mapOf("type" to plexType(lib), "sort" to order, "includeGuids" to "1") + page(from, limit.coerceIn(1, 500))
        )
        return ServerPageResult(
            items = result.metadata.mapNotNull { preview(account, it) },
            total = result.totalSize ?: (from + result.metadata.size)
        )
    }

    override suspend fun filteredPage(
        account: ServerAccount,
        lib: ServerLib,
        filters: com.fetch.desktop.FilterRowFilters,
        start: Int,
        limit: Int
    ): ServerPageResult {
        val from = start.coerceAtLeast(0)
        val genreKey = filters.genre?.let { name -> genreKeys(account, lib)[name] ?: return ServerPageResult(emptyList(), 0) }
        val result = container(
            account,
            "/library/sections/${pathSegment(lib.id)}/all",
            mapOf("type" to plexType(lib), "includeGuids" to "1") + filterQuery(filters, genreKey) + page(from, limit.coerceIn(1, 500))
        )
        return ServerPageResult(
            items = result.metadata.mapNotNull { preview(account, it) },
            total = result.totalSize ?: (from + result.metadata.size)
        )
    }

    override suspend fun genres(account: ServerAccount, lib: ServerLib): List<String> = genreKeys(account, lib).keys.toList()

    private val genreKeyCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    /** Genre name → Plex's key for it in [lib] (Plex filters by key). */
    private suspend fun genreKeys(account: ServerAccount, lib: ServerLib): Map<String, String> =
        genreKeyCache["${account.id}:${lib.id}"] ?: container(
            account,
            "/library/sections/${pathSegment(lib.id)}/genre",
            mapOf("type" to plexType(lib))
        ).directories.mapNotNull { g -> g.title?.takeIf(String::isNotBlank)?.let { it to g.key } }.toMap()
            .also { genreKeyCache["${account.id}:${lib.id}"] = it }

    override suspend fun details(account: ServerAccount, itemId: String): Meta? {
        val item = metadata(account, itemId) ?: return null
        val type = item.contentType() ?: return null
        val episodes = if (type == ContentType.SERIES) {
            container(account, "/library/metadata/${pathSegment(item.ratingKey)}/allLeaves").metadata.filter { it.type == "episode" }
        } else {
            emptyList()
        }
        val cast = item.roles.filter { !it.tag.isNullOrBlank() }
        return Meta(
            id = ServerItemRef(account.id, item.ratingKey).encode(),
            type = type,
            rawType = type.rawType(),
            name = item.title.orEmpty(),
            poster = image(account, item.thumb, height = 600),
            posterShape = PosterShape.POSTER,
            background = image(account, item.art, width = 1920),
            logo = item.logo(account),
            description = item.summary,
            releaseInfo = item.year?.toString(),
            imdbRating = item.score()?.roundRating(),
            genres = item.genres.mapNotNull { it.tag },
            runtime = item.duration?.takeIf { type == ContentType.MOVIE && it > 0 }?.let { "${it / 60_000L} min" },
            director = item.directors.mapNotNull { it.tag },
            writer = item.writers.mapNotNull { it.tag },
            cast = cast.mapNotNull { it.tag },
            castMembers = cast.map { role ->
                MetaCastMember(name = role.tag.orEmpty(), character = role.role?.takeIf { it.isNotBlank() }, photo = image(account, role.thumb, height = 300))
            },
            videos = episodes.map { episode ->
                Video(
                    id = ServerItemRef(account.id, episode.ratingKey).encode(),
                    title = episode.title.orEmpty(),
                    released = episode.originallyAvailableAt,
                    thumbnail = image(account, episode.thumb, width = 640),
                    season = episode.parentIndex,
                    episode = episode.index,
                    overview = episode.summary,
                    runtime = episode.duration?.let { (it / 60_000L).toInt() },
                    rating = episode.score(),
                    available = episode.media.isNotEmpty()
                )
            },
            ageRating = item.contentRating,
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            imdbId = item.imdb(),
            released = item.originallyAvailableAt?.take(10)
        )
    }

    /** A movie or show as a preview: id is its IMDb id when it has one, else its [ServerItemRef]. */
    private fun preview(account: ServerAccount, item: PlexMetadata): MetaPreview? {
        val type = item.contentType() ?: return null
        val imdb = item.imdb()
        return MetaPreview(
            id = imdb ?: ServerItemRef(account.id, item.ratingKey).encode(),
            type = type,
            rawType = type.rawType(),
            name = item.title.orEmpty(),
            poster = image(account, item.thumb, height = 600),
            posterShape = PosterShape.POSTER,
            background = image(account, item.art, width = 1920),
            logo = item.logo(account),
            description = item.summary,
            releaseInfo = item.year?.toString(),
            imdbRating = item.score()?.roundRating(),
            genres = item.genres.mapNotNull { it.tag },
            released = item.originallyAvailableAt?.take(10),
            imdbId = imdb
        )
    }

    private fun PlexMetadata.logo(account: ServerAccount): String? =
        images.firstOrNull { it.type == "clearLogo" }?.url?.let { image(account, it, width = 800) }

    override suspend fun resume(account: ServerAccount, limit: Int): List<ServerResume> {
        val items = try {
            container(account, "/hubs/continueWatching/items", page(0, limit)).metadata
        } catch (e: ServerException) {
            // Servers before the Continue Watching hub only have On Deck.
            if (e.status != 404) throw e
            container(account, "/library/onDeck", page(0, limit)).metadata
        }
        val selected = account.libraries.filter { it.selected }.map { it.id }.toSet()
        val wanted = items.filter { item ->
            account.libraries.isEmpty() || item.librarySectionID?.toString()?.let { it in selected } ?: true
        }
        val showKeys = wanted.filter { it.type == "episode" }.mapNotNull { it.grandparentRatingKey }.distinct()
        val showImdb = coroutineScope {
            showKeys.map { key ->
                async {
                    key to runCatching {
                        container(account, "/library/metadata/${pathSegment(key)}", mapOf("includeGuids" to "1")).metadata.firstOrNull()?.imdb()
                    }.getOrNull()
                }
            }.awaitAll().toMap()
        }
        return wanted.mapNotNull { item ->
            val episode = item.type == "episode"
            val imdb = when {
                episode -> item.grandparentRatingKey?.let(showImdb::get)
                item.type == "movie" -> item.imdb()
                else -> null
            } ?: return@mapNotNull null
            ServerResume(
                imdbId = imdb,
                type = if (episode) "series" else "movie",
                name = (if (episode) item.grandparentTitle else item.title) ?: item.title.orEmpty(),
                season = item.parentIndex.takeIf { episode },
                episode = item.index.takeIf { episode },
                episodeTitle = item.title.takeIf { episode },
                positionMs = item.viewOffset ?: 0L,
                durationMs = item.duration ?: 0L,
                lastPlayedEpochMs = item.lastViewedAt?.times(1_000L) ?: 0L,
                poster = image(account, if (episode) item.grandparentThumb ?: item.thumb else item.thumb, height = 600),
                backdrop = image(account, if (episode) item.grandparentArt ?: item.art else item.art, width = 1920)
            )
        }
    }

    override suspend fun markPlayed(account: ServerAccount, stream: ServerStream): Boolean = bestEffort {
        http.send(
            LABEL,
            "GET",
            buildUrl(account.address, "/:/scrobble", mapOf("identifier" to LIBRARY_IDENTIFIER, "key" to stream.itemId)),
            headers(account.token)
        )
    }

    override suspend fun reportPosition(account: ServerAccount, stream: ServerStream, positionMs: Long, stopped: Boolean): Boolean = bestEffort {
        http.send(
            LABEL,
            "GET",
            buildUrl(
                account.address,
                "/:/timeline",
                mapOf(
                    "ratingKey" to stream.itemId,
                    "key" to "/library/metadata/${stream.itemId}",
                    "identifier" to LIBRARY_IDENTIFIER,
                    "state" to if (stopped) "stopped" else "playing",
                    "time" to positionMs.coerceAtLeast(0L).toString(),
                    "duration" to stream.durationMs?.toString()
                )
            ),
            headers(account.token)
        )
    }

    /** Plex server tokens are not session-bound, so there is nothing to end on the server. */
    override suspend fun signOut(account: ServerAccount) = Unit

    /** The `X-Plex-*` client headers, plus the token when given. */
    internal fun headers(token: String?): Map<String, String> = buildMap {
        put("X-Plex-Product", PRODUCT)
        put("X-Plex-Version", AppInfo.version.headerValue())
        put("X-Plex-Client-Identifier", deviceId.headerValue())
        put("X-Plex-Platform", defaultDeviceName().headerValue())
        put("X-Plex-Device", deviceName.headerValue())
        put("X-Plex-Device-Name", deviceName.headerValue())
        if (token != null) put("X-Plex-Token", token.headerValue())
    }

    private suspend fun container(account: ServerAccount, path: String, query: Map<String, String?> = emptyMap()): PlexContainer =
        decode(PlexResponse.serializer(), http.send(LABEL, "GET", buildUrl(account.address, path, query), headers(account.token)).body.ifBlank { "{}" }).container

    private fun <T> decode(deserializer: DeserializationStrategy<T>, body: String): T = try {
        serverJson.decodeFromString(deserializer, body)
    } catch (_: IllegalArgumentException) {
        throw ServerException("Plex sent a response Fetch doesn't understand")
    }

    /** A server image scaled through the photo transcoder; absolute URLs are used as they are. */
    private fun image(account: ServerAccount, path: String?, width: Int? = null, height: Int? = null): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return buildUrl(
            account.address,
            "/photo/:/transcode",
            mapOf(
                "url" to path,
                "width" to (width ?: height?.times(2)?.div(3))?.toString(),
                "height" to (height ?: width?.times(9)?.div(16))?.toString(),
                "upscale" to "0",
                "X-Plex-Token" to account.token
            )
        )
    }

    private fun plexType(lib: ServerLib): String = if (lib.type == "series") "2" else "1"

    /** Plex's filter syntax, as on TV: `year>>=1999` is "after 1999", `year<<=2011` "before 2011". */
    internal fun filterQuery(filters: com.fetch.desktop.FilterRowFilters, genreKey: String?): Map<String, String?> = mapOf(
        "genre" to genreKey,
        "year>>" to filters.yearFrom?.minus(1)?.toString(),
        "year<<" to filters.yearTo?.plus(1)?.toString(),
        "unwatched" to when (filters.watched) {
            com.fetch.desktop.RowWatched.ANY -> null
            com.fetch.desktop.RowWatched.WATCHED -> "0"
            com.fetch.desktop.RowWatched.UNWATCHED -> "1"
        },
        "audienceRating>>" to filters.minRating?.let { (it - 0.05).toString() },
        "sort" to when (filters.sort) {
            com.fetch.desktop.RowSort.DATE_ADDED -> "addedAt"
            com.fetch.desktop.RowSort.TITLE -> "titleSort"
            com.fetch.desktop.RowSort.RELEASE_DATE -> "originallyAvailableAt"
            com.fetch.desktop.RowSort.RATING -> "audienceRating"
            com.fetch.desktop.RowSort.RANDOM -> "random"
        } + if (filters.descending) ":desc" else ""
    )

    private fun page(start: Int, size: Int): Map<String, String> =
        mapOf("X-Plex-Container-Start" to start.toString(), "X-Plex-Container-Size" to size.toString())

    internal companion object {
        const val PLEX_TV = "https://plex.tv"
        const val LABEL = "Plex"
        const val PRODUCT = "Fetch Desktop"
        const val MINIMUM_VERSION = "1.30"
        const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"
        const val DEFAULT_PIN_LIFETIME_S = 900L
        const val PROBE_TIMEOUT_S = 4L
        const val TITLE_LOOKUP_LIMIT = 20
        const val MIN_TRANSCODE_KBPS = 300

        /** On the LAN first, then direct over the internet, then plex.tv's relay; IPv4 before IPv6. */
        fun orderedConnections(connections: List<PlexConnection>): List<PlexConnection> =
            connections.sortedWith(
                compareBy<PlexConnection> {
                    when {
                        it.relay -> 2
                        it.local -> 0
                        else -> 1
                    }
                }.thenBy { it.ipv6 }
            )
    }
}

/** The IMDb id among this item's guids (`imdb://tt…` or the legacy `com.plexapp.agents.imdb://tt…?lang=en`). */
internal fun PlexMetadata.imdb(): String? =
    (guids.map { it.id } + listOfNotNull(guid)).firstNotNullOfOrNull { value ->
        val scheme = value.substringBefore("://", "").substringAfterLast('.').lowercase()
        if (scheme != "imdb") return@firstNotNullOfOrNull null
        value.substringAfter("://").substringBefore('?').substringBefore('/').imdbOrNull()
    }

private fun PlexMetadata.contentType(): ContentType? = when (type) {
    "movie" -> ContentType.MOVIE
    "show" -> ContentType.SERIES
    else -> null
}

private fun PlexMetadata.score(): Double? = audienceRating ?: rating

@Serializable
internal data class PlexPin(val id: Long, val code: String, val authToken: String? = null, val expiresIn: Long? = null)

@Serializable
internal data class PlexUser(val id: Long, val username: String? = null, val title: String? = null, val email: String? = null)

@Serializable
internal data class PlexResource(
    val name: String? = null,
    val productVersion: String? = null,
    val clientIdentifier: String,
    val provides: String? = null,
    val accessToken: String? = null,
    val connections: List<PlexConnection> = emptyList()
) {
    val isServer: Boolean get() = provides.orEmpty().split(',').any { it.trim() == "server" }
}

@Serializable
internal data class PlexConnection(
    val uri: String,
    val local: Boolean = false,
    val relay: Boolean = false,
    @SerialName("IPv6") val ipv6: Boolean = false
)

@Serializable
internal data class PlexResponse(@SerialName("MediaContainer") val container: PlexContainer = PlexContainer())

@Serializable
internal data class PlexContainer(
    val machineIdentifier: String? = null,
    val size: Int? = null,
    val totalSize: Int? = null,
    @SerialName("Directory") val directories: List<PlexDirectory> = emptyList(),
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList()
)

@Serializable
internal data class PlexDirectory(val key: String, val title: String? = null, val type: String? = null)

@Serializable
internal data class PlexMetadata(
    val ratingKey: String,
    val guid: String? = null,
    val type: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val year: Int? = null,
    val thumb: String? = null,
    val art: String? = null,
    val originallyAvailableAt: String? = null,
    val rating: Double? = null,
    val audienceRating: Double? = null,
    val contentRating: String? = null,
    val duration: Long? = null,
    val viewOffset: Long? = null,
    val lastViewedAt: Long? = null,
    val parentIndex: Int? = null,
    val index: Int? = null,
    val grandparentRatingKey: String? = null,
    val grandparentTitle: String? = null,
    val grandparentThumb: String? = null,
    val grandparentArt: String? = null,
    val librarySectionID: Long? = null,
    @SerialName("Genre") val genres: List<PlexTag> = emptyList(),
    @SerialName("Director") val directors: List<PlexTag> = emptyList(),
    @SerialName("Writer") val writers: List<PlexTag> = emptyList(),
    @SerialName("Role") val roles: List<PlexTag> = emptyList(),
    @SerialName("Guid") val guids: List<PlexGuid> = emptyList(),
    @SerialName("Image") val images: List<PlexImage> = emptyList(),
    @SerialName("Media") val media: List<PlexMedia> = emptyList()
)

@Serializable
internal data class PlexTag(val tag: String? = null, val role: String? = null, val thumb: String? = null)

@Serializable
internal data class PlexImage(val type: String? = null, val url: String? = null)

@Serializable
internal data class PlexGuid(val id: String)

@Serializable
internal data class PlexMedia(
    val id: Long,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val duration: Long? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val container: String? = null,
    @SerialName("Part") val parts: List<PlexPart> = emptyList()
)

@Serializable
internal data class PlexPart(
    val id: Long,
    val key: String? = null,
    val file: String? = null,
    val size: Long? = null,
    @SerialName("Stream") val streams: List<PlexStream> = emptyList()
)

@Serializable
internal data class PlexStream(
    val id: Long? = null,
    val streamType: Int? = null,
    val codec: String? = null,
    val languageCode: String? = null,
    val language: String? = null,
    val title: String? = null,
    val displayTitle: String? = null,
    val extendedDisplayTitle: String? = null,
    val key: String? = null,
    val forced: Boolean = false,
    val default: Boolean = false
) {
    val isSubtitle: Boolean get() = streamType == 3
}
