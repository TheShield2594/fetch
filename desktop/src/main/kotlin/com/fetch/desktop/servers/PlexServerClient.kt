package com.fetch.desktop.servers

import com.fetch.desktop.AppInfo
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
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
                resumePositionMs = item.viewOffset?.takeIf { it > 0 }
            )
        }

    override suspend fun latest(account: ServerAccount, lib: ServerLib, limit: Int): List<MetaPreview> {
        val items = container(
            account,
            "/library/sections/${pathSegment(lib.id)}/all",
            mapOf("type" to plexType(lib), "sort" to "addedAt:desc", "includeGuids" to "1") + page(0, (limit * 2).coerceIn(1, 200))
        ).metadata
        return items.mapNotNull { item ->
            val imdb = item.imdb() ?: return@mapNotNull null
            val type = when (item.type) {
                "movie" -> ContentType.MOVIE
                "show" -> ContentType.SERIES
                else -> return@mapNotNull null
            }
            MetaPreview(
                id = imdb,
                type = type,
                name = item.title.orEmpty(),
                poster = image(account, item.thumb, height = 600),
                posterShape = PosterShape.POSTER,
                background = image(account, item.art, width = 1920),
                logo = null,
                description = item.summary,
                releaseInfo = item.year?.toString(),
                imdbRating = null,
                genres = item.genres.mapNotNull { it.tag },
                imdbId = imdb
            )
        }.take(limit)
    }

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
    @SerialName("Guid") val guids: List<PlexGuid> = emptyList(),
    @SerialName("Media") val media: List<PlexMedia> = emptyList()
)

@Serializable
internal data class PlexTag(val tag: String? = null)

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
internal data class PlexPart(val id: Long, val key: String? = null, val file: String? = null, val size: Long? = null)
