package com.fetch.desktop.servers

import com.fetch.desktop.AppInfo
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * Jellyfin and Emby, which share the MediaBrowser API. Emby serves it under `/emby` and reads
 * the client identity from `X-Emby-Authorization`; the user-scoped endpoints also differ.
 */
class MediaBrowserServerClient(
    override val kind: ServerKind,
    http: OkHttpClient,
    private val deviceId: String,
    private val deviceName: String = defaultDeviceName()
) : MediaServerClient {
    init {
        require(kind == ServerKind.JELLYFIN || kind == ServerKind.EMBY) { "Not a MediaBrowser server: $kind" }
    }

    private val emby = kind == ServerKind.EMBY
    private val apiPath = if (emby) "/emby" else ""
    private val authHeader = if (emby) "X-Emby-Authorization" else "Authorization"
    private val minimumVersion = if (emby) "4.7" else "10.9"

    // Follows redirects only for the anonymous server probe; a token must not travel to another host.
    private val probeHttp = http
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val index = ImdbIndex()

    /**
     * Signs in to the server at [address] with a user name and password. Rejects servers older
     * than Jellyfin 10.9 / Emby 4.7 and returns the account with its libraries filled in.
     */
    suspend fun signIn(address: String, username: String, password: String): ServerAccount {
        val base = normalizeServerAddress(address, apiPath) ?: throw ServerException("Enter the ${kind.label} server's address")
        val (root, info) = publicInfo(base)
        if (emby && info.productName?.contains("Jellyfin", ignoreCase = true) == true) {
            throw ServerException("That is a Jellyfin server; add it as Jellyfin")
        }
        if (!isAtLeastVersion(info.version, minimumVersion)) {
            throw ServerException("${kind.label} ${info.version ?: "(unknown version)"} is too old; Fetch needs $minimumVersion or newer")
        }
        val body = serverJson.encodeToString(MbAuthRequest.serializer(), MbAuthRequest(username, password))
        val response = http.send(kind.label, "POST", buildUrl(root + apiPath, "/Users/AuthenticateByName"), headers(null), body)
        val auth = serverJson.decodeFromString(MbAuthResult.serializer(), response.body)
        val user = auth.user ?: throw ServerAuthException("${kind.label} rejected the sign-in")
        val token = auth.accessToken?.takeIf { it.isNotBlank() } ?: throw ServerAuthException("${kind.label} rejected the sign-in")
        val account = ServerAccount(
            id = UUID.randomUUID().toString(),
            kind = kind,
            name = info.serverName?.takeIf { it.isNotBlank() } ?: root.toHttpUrlOrNull()?.host.orEmpty(),
            address = root,
            serverId = auth.serverId ?: info.id.orEmpty(),
            userId = user.id,
            userName = user.name ?: username,
            token = token
        )
        return account.copy(libraries = libraries(account))
    }

    /** The public system info, and the base URL it was found at after any redirect (http → https). */
    private suspend fun publicInfo(base: String): Pair<String, MbPublicInfo> {
        val path = "$apiPath/System/Info/Public"
        val response = probeHttp.send(kind.label, "GET", buildUrl(base, path), headers(null))
        val info = try {
            serverJson.decodeFromString(MbPublicInfo.serializer(), response.body)
        } catch (_: Exception) {
            throw ServerException("That address doesn't answer as a ${kind.label} server")
        }
        val finalUrl = response.url.toString().substringBefore('?')
        val root = finalUrl.takeIf { it.endsWith(path, ignoreCase = true) }
            ?.dropLast(path.length)
            ?.let { normalizeServerAddress(it, apiPath) }
            ?: base
        return root to info
    }

    override suspend fun libraries(account: ServerAccount): List<ServerLib> {
        val views = if (emby) "/Users/${pathSegment(account.userId)}/Views" to emptyMap() else "/UserViews" to mapOf("userId" to account.userId)
        return get(account, views.first, views.second, MbItems.serializer()).items.mapNotNull { view ->
            val type = when (view.collectionType?.lowercase()) {
                "movies" -> "movie"
                "tvshows" -> "series"
                else -> return@mapNotNull null
            }
            ServerLib(view.id, view.name.orEmpty(), type)
        }
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
        val titleIds = libs.flatMap { findTitle(account, it, imdbId, title) }.distinct()
        val itemIds = if (series) titleIds.flatMap { findEpisodes(account, it, season!!, episode!!) } else titleIds
        return itemIds.distinct().flatMap { streamsOf(account, item(account, it)) }
    }

    /**
     * Items in [lib] whose IMDb id is [imdb]: Emby filters by provider id, Jellyfin searches the
     * title; either way the hits are verified, and a full IMDb index of the library is the fallback.
     */
    private suspend fun findTitle(account: ServerAccount, lib: ServerLib, imdb: String, title: String?): List<String> {
        val base = libraryQuery(lib) + mapOf(
            "fields" to "ProviderIds,ProductionYear",
            "enableImages" to "false",
            "enableUserData" to "false"
        )
        val filter = if (emby) {
            mapOf("anyProviderIdEquals" to "imdb.$imdb")
        } else {
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { mapOf("searchTerm" to it) }
        }
        if (filter != null) {
            val found = items(account, base + filter + ("limit" to TITLE_LOOKUP_LIMIT.toString()))
                .filter { it.imdb() == imdb }
                .map { it.id }
            if (found.isNotEmpty()) return found
        }
        return index.lookup("${account.id}:${lib.id}", imdb) { start, size ->
            items(account, base + mapOf("startIndex" to start.toString(), "limit" to size.toString())).map { it.imdb() to it.id }
        }
    }

    private suspend fun findEpisodes(account: ServerAccount, seriesId: String, season: Int, episode: Int): List<String> =
        get(
            account,
            "/Shows/${pathSegment(seriesId)}/Episodes",
            mapOf(
                "userId" to account.userId,
                "season" to season.toString(),
                "enableImages" to "false",
                "enableUserData" to "false"
            ),
            MbItems.serializer()
        ).items.filter { item ->
            item.parentIndexNumber == season &&
                item.indexNumber == episode &&
                (item.indexNumberEnd == null || item.indexNumberEnd == episode) &&
                !item.isMissing
        }.map { it.id }

    private fun streamsOf(account: ServerAccount, item: MbItem): List<ServerStream> {
        if (item.isMissing) return emptyList()
        val resume = item.userData?.playbackPositionTicks?.takeIf { it > 0 }?.div(TICKS_PER_MS)
        return item.mediaSources.filterNot { it.type.equals("Placeholder", ignoreCase = true) }.map { source ->
            val video = source.mediaStreams.firstOrNull { it.type.equals("Video", ignoreCase = true) }
            val audios = source.mediaStreams.filter { it.type.equals("Audio", ignoreCase = true) }
            val audio = audios.firstOrNull { it.index != null && it.index == source.defaultAudioStreamIndex }
                ?: audios.firstOrNull { it.isDefault }
                ?: audios.firstOrNull()
            ServerStream(
                accountId = account.id,
                itemId = item.id,
                mediaSourceId = source.id,
                url = buildUrl(
                    account.address + apiPath,
                    "/Videos/${pathSegment(item.id)}/stream",
                    mapOf(
                        "static" to "true",
                        "MediaSourceId" to source.id,
                        "deviceId" to deviceId,
                        "api_key" to account.token
                    )
                ),
                fileName = source.path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() },
                sizeBytes = source.size,
                width = video?.width,
                height = video?.height,
                videoCodec = video?.codec,
                audioCodec = audio?.codec,
                audioChannels = audio?.channels,
                bitrate = source.bitrate,
                durationMs = (source.runTimeTicks ?: item.runTimeTicks)?.div(TICKS_PER_MS),
                resumePositionMs = resume
            )
        }
    }

    override suspend fun latest(account: ServerAccount, lib: ServerLib, limit: Int): List<MetaPreview> {
        val items = items(
            account,
            libraryQuery(lib) + mapOf(
                "fields" to "Overview,Genres,ProviderIds,PremiereDate",
                "imageTypeLimit" to "1",
                "enableImageTypes" to "Primary,Backdrop",
                "sortBy" to "DateCreated,SortName",
                "sortOrder" to "Descending",
                "limit" to (limit * 2).coerceIn(1, 200).toString()
            )
        )
        val root = account.address + apiPath
        return items.mapNotNull { item ->
            val imdb = item.imdb() ?: return@mapNotNull null
            val type = when {
                item.type.equals("Movie", ignoreCase = true) -> ContentType.MOVIE
                item.type.equals("Series", ignoreCase = true) -> ContentType.SERIES
                else -> return@mapNotNull null
            }
            MetaPreview(
                id = imdb,
                type = type,
                name = item.name.orEmpty(),
                poster = item.imageTags["Primary"]?.let { image(root, item.id, "Primary", it, maxHeight = 600) },
                posterShape = PosterShape.POSTER,
                background = item.backdrop(root),
                logo = null,
                description = item.overview,
                releaseInfo = item.productionYear?.toString(),
                imdbRating = null,
                genres = item.genres,
                imdbId = imdb
            )
        }.take(limit)
    }

    override suspend fun resume(account: ServerAccount, limit: Int): List<ServerResume> {
        val endpoint = if (emby) "/Users/${pathSegment(account.userId)}/Items/Resume" to emptyMap() else "/UserItems/Resume" to mapOf("userId" to account.userId)
        val items = get(
            account,
            endpoint.first,
            endpoint.second + mapOf(
                "limit" to limit.toString(),
                "mediaTypes" to "Video",
                "fields" to "ProviderIds",
                "imageTypeLimit" to "1",
                "enableImageTypes" to "Primary,Backdrop"
            ),
            MbItems.serializer()
        ).items
        val seriesIds = items.filter { it.isEpisode }.mapNotNull { it.seriesId }.distinct()
        val seriesImdb = if (seriesIds.isEmpty()) {
            emptyMap()
        } else {
            items(account, mapOf("ids" to seriesIds.joinToString(","), "fields" to "ProviderIds", "enableImages" to "false"))
                .associate { it.id to it.imdb() }
        }
        val root = account.address + apiPath
        return items.mapNotNull { item ->
            val data = item.userData ?: return@mapNotNull null
            val position = (data.playbackPositionTicks ?: 0L) / TICKS_PER_MS
            val common = { imdb: String, type: String, name: String, poster: String? ->
                ServerResume(
                    imdbId = imdb,
                    type = type,
                    name = name,
                    season = item.parentIndexNumber.takeIf { item.isEpisode },
                    episode = item.indexNumber.takeIf { item.isEpisode },
                    episodeTitle = item.name.takeIf { item.isEpisode },
                    positionMs = position,
                    durationMs = (item.runTimeTicks ?: 0L) / TICKS_PER_MS,
                    lastPlayedEpochMs = parseInstantMs(data.lastPlayedDate) ?: 0L,
                    poster = poster,
                    backdrop = item.backdrop(root)
                )
            }
            when {
                item.isEpisode -> {
                    val seriesId = item.seriesId ?: return@mapNotNull null
                    val imdb = seriesImdb[seriesId] ?: return@mapNotNull null
                    common(imdb, "series", item.seriesName ?: item.name.orEmpty(), item.seriesPrimaryImageTag?.let { image(root, seriesId, "Primary", it, maxHeight = 600) })
                }
                item.type.equals("Movie", ignoreCase = true) -> {
                    val imdb = item.imdb() ?: return@mapNotNull null
                    common(imdb, "movie", item.name.orEmpty(), item.imageTags["Primary"]?.let { image(root, item.id, "Primary", it, maxHeight = 600) })
                }
                else -> null
            }
        }
    }

    override suspend fun markPlayed(account: ServerAccount, stream: ServerStream): Boolean = bestEffort {
        val endpoint = if (emby) {
            "/Users/${pathSegment(account.userId)}/PlayedItems/${pathSegment(stream.itemId)}" to emptyMap()
        } else {
            "/UserPlayedItems/${pathSegment(stream.itemId)}" to mapOf("userId" to account.userId)
        }
        http.send(kind.label, "POST", buildUrl(account.address + apiPath, endpoint.first, endpoint.second), headers(account.token))
    }

    override suspend fun reportPosition(account: ServerAccount, stream: ServerStream, positionMs: Long, stopped: Boolean): Boolean = bestEffort {
        val report = MbPlaybackReport(
            itemId = stream.itemId,
            mediaSourceId = stream.mediaSourceId,
            positionTicks = positionMs.coerceAtLeast(0L) * TICKS_PER_MS,
            eventName = if (stopped) null else "TimeUpdate"
        )
        val path = if (stopped) "/Sessions/Playing/Stopped" else "/Sessions/Playing/Progress"
        http.send(
            kind.label,
            "POST",
            buildUrl(account.address + apiPath, path),
            headers(account.token),
            serverJson.encodeToString(MbPlaybackReport.serializer(), report)
        )
    }

    override suspend fun signOut(account: ServerAccount) {
        bestEffort { http.send(kind.label, "POST", buildUrl(account.address + apiPath, "/Sessions/Logout"), headers(account.token)) }
    }

    /** The `MediaBrowser Client=…, Device=…, DeviceId=…, Version=…[, Token=…]` identity header. */
    internal fun headers(token: String?): Map<String, String> = mapOf(
        authHeader to buildString {
            append("MediaBrowser Client=\"").append(CLIENT_NAME)
            append("\", Device=\"").append(deviceName.headerValue())
            append("\", DeviceId=\"").append(deviceId.headerValue())
            append("\", Version=\"").append(AppInfo.version.headerValue()).append('"')
            if (token != null) append(", Token=\"").append(token.headerValue()).append('"')
        }
    )

    private fun libraryQuery(lib: ServerLib): Map<String, String?> = mapOf(
        "parentId" to lib.id,
        "recursive" to "true",
        "includeItemTypes" to if (lib.type == "series") "Series" else "Movie"
    )

    private suspend fun items(account: ServerAccount, query: Map<String, String?>): List<MbItem> {
        val endpoint = if (emby) "/Users/${pathSegment(account.userId)}/Items" to emptyMap() else "/Items" to mapOf("userId" to account.userId)
        return get(account, endpoint.first, endpoint.second + query, MbItems.serializer()).items
    }

    private suspend fun item(account: ServerAccount, itemId: String): MbItem {
        val endpoint = if (emby) {
            "/Users/${pathSegment(account.userId)}/Items/${pathSegment(itemId)}" to emptyMap()
        } else {
            "/Items/${pathSegment(itemId)}" to mapOf("userId" to account.userId)
        }
        return get(account, endpoint.first, endpoint.second + ("fields" to "MediaSources,ProviderIds,Path"), MbItem.serializer())
    }

    private suspend fun <T> get(
        account: ServerAccount,
        path: String,
        query: Map<String, String?>,
        deserializer: DeserializationStrategy<T>
    ): T {
        val body = http.send(kind.label, "GET", buildUrl(account.address + apiPath, path, query), headers(account.token)).body
        return try {
            serverJson.decodeFromString(deserializer, body)
        } catch (_: IllegalArgumentException) {
            throw ServerException("${kind.label} sent a response Fetch doesn't understand")
        }
    }

    private fun MbItem.backdrop(root: String): String? =
        backdropImageTags.firstOrNull()?.let { image(root, id, "Backdrop/0", it, maxWidth = 1920) }
            ?: parentBackdropItemId?.let { parent ->
                parentBackdropImageTags.firstOrNull()?.let { image(root, parent, "Backdrop/0", it, maxWidth = 1920) }
            }

    private fun image(root: String, itemId: String, type: String, tag: String, maxWidth: Int? = null, maxHeight: Int? = null): String =
        buildUrl(
            root,
            "/Items/${pathSegment(itemId)}/Images/$type",
            mapOf("tag" to tag, "maxWidth" to maxWidth?.toString(), "maxHeight" to maxHeight?.toString(), "quality" to "90")
        )

    private companion object {
        const val CLIENT_NAME = "Fetch Desktop"
        const val TICKS_PER_MS = 10_000L
        const val TITLE_LOOKUP_LIMIT = 20
    }
}

internal suspend fun bestEffort(block: suspend () -> Unit): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

@Serializable
internal data class MbPublicInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("ProductName") val productName: String? = null,
    @SerialName("Id") val id: String? = null
)

@Serializable
internal data class MbAuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String
)

@Serializable
internal data class MbAuthResult(
    @SerialName("User") val user: MbUser? = null,
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null
)

@Serializable
internal data class MbUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null
)

@Serializable
internal data class MbItems(
    @SerialName("Items") val items: List<MbItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null
)

@Serializable
internal data class MbItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("ProviderIds") val providerIds: Map<String, String?> = emptyMap(),
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("IndexNumberEnd") val indexNumberEnd: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("LocationType") val locationType: String? = null,
    @SerialName("UserData") val userData: MbUserData? = null,
    @SerialName("MediaSources") val mediaSources: List<MbMediaSource> = emptyList()
) {
    val isMissing: Boolean get() = locationType.equals("Virtual", ignoreCase = true)
    val isEpisode: Boolean get() = type.equals("Episode", ignoreCase = true)

    fun imdb(): String? = providerIds.entries.firstOrNull { it.key.equals("Imdb", ignoreCase = true) }?.value.imdbOrNull()
}

@Serializable
internal data class MbUserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null
)

@Serializable
internal data class MbMediaSource(
    @SerialName("Id") val id: String,
    @SerialName("Type") val type: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MbMediaStream> = emptyList()
)

@Serializable
internal data class MbMediaStream(
    @SerialName("Type") val type: String? = null,
    @SerialName("Index") val index: Int? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false
)

@Serializable
internal data class MbPlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String?,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("EventName") val eventName: String? = null
)
