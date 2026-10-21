package com.fetch.tv.core.cloud

import com.fetch.tv.core.debrid.DebridProviders
import com.fetch.tv.data.remote.api.RealDebridApi
import com.fetch.tv.data.remote.dto.RealDebridDownloadDto
import com.fetch.tv.data.remote.dto.RealDebridTorrentInfoDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Real-Debrid's torrents and downloads. The torrent list has no file names, so each finished
 * torrent's files come from its info call, a few at a time to stay inside Real-Debrid's rate
 * limit. A torrent file plays by unrestricting its hoster link; a download already holds a
 * direct link.
 */
@Singleton
class RealDebridCloudLibraryProviderApi @Inject constructor(
    private val api: RealDebridApi
) : CloudLibraryProviderApi {
    override val provider = DebridProviders.RealDebrid

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> =
        runCatching {
            val authorization = "Bearer $apiKey"
            val torrentsResponse = api.listTorrents(authorization, limit = LIST_LIMIT)
            // Real-Debrid answers 204 with no body when the list is empty.
            if (!torrentsResponse.isSuccessful) {
                throw IllegalStateException(torrentsResponse.errorBody()?.string().orEmpty().ifBlank { null })
            }
            val torrents = torrentsResponse.body().orEmpty()
            val downloadsResponse = api.listDownloads(authorization, limit = LIST_LIMIT)
            val downloads = downloadsResponse.body().takeIf { downloadsResponse.isSuccessful }.orEmpty()

            val infoPermits = Semaphore(INFO_CONCURRENCY)
            val torrentItems = coroutineScope {
                torrents.map { torrent ->
                    async {
                        val details = if (torrent.isDownloaded() && torrent.id != null) {
                            infoPermits.withPermit {
                                runCatching { api.getTorrentInfo(authorization, torrent.id) }
                                    .onFailure { if (it is CancellationException) throw it }
                                    .getOrNull()
                                    ?.takeIf { it.isSuccessful }
                                    ?.body()
                            }
                        } else {
                            null
                        }
                        realDebridTorrentCloudItem(torrent, details, provider.id, provider.displayName)
                    }
                }.awaitAll().filterNotNull()
            }
            torrentItems + downloads.mapNotNull { download ->
                realDebridDownloadCloudItem(download, provider.id, provider.displayName)
            }
        }

    override suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        file.playbackUrl?.takeIf { it.isNotBlank() }?.let { url ->
            return CloudLibraryPlaybackResult.Success(
                url = url,
                filename = file.name.takeIf { it.isNotBlank() },
                videoSizeBytes = file.sizeBytes
            )
        }
        val link = file.id?.takeIf { it.isNotBlank() } ?: return CloudLibraryPlaybackResult.Failed()
        return try {
            val response = api.unrestrictLink("Bearer $apiKey", link)
            val body = response.body()?.takeIf { response.isSuccessful }
                ?: return CloudLibraryPlaybackResult.Failed(response.errorBody()?.string())
            val url = body.download?.takeIf { it.isNotBlank() } ?: return CloudLibraryPlaybackResult.Failed()
            CloudLibraryPlaybackResult.Success(
                url = url,
                filename = body.filename?.takeIf { it.isNotBlank() } ?: file.name.takeIf { it.isNotBlank() },
                videoSizeBytes = body.filesize ?: file.sizeBytes
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CloudLibraryPlaybackResult.Failed(error.message)
        }
    }

    private companion object {
        const val LIST_LIMIT = 50
        const val INFO_CONCURRENCY = 4
    }
}

private fun RealDebridTorrentInfoDto.isDownloaded(): Boolean =
    status.equals("downloaded", ignoreCase = true)

/**
 * Maps a torrent to a cloud item. Real-Debrid gives one link per selected file, in file order,
 * so the selected files and the links pair up by position. When they don't (an archived
 * torrent), each link still plays, under the torrent's name.
 */
fun realDebridTorrentCloudItem(
    torrent: RealDebridTorrentInfoDto,
    details: RealDebridTorrentInfoDto?,
    providerId: String,
    providerName: String
): CloudLibraryItem? {
    val id = torrent.id?.takeIf { it.isNotBlank() } ?: return null
    val name = torrent.filename?.takeIf { it.isNotBlank() } ?: id
    val links = (details?.links ?: torrent.links).orEmpty().filter { it.isNotBlank() }
    val selected = details?.files.orEmpty()
        .filter { it.selected == 1 }
        .sortedBy { it.id ?: Int.MAX_VALUE }
    val files = if (selected.isNotEmpty() && selected.size == links.size) {
        selected.zip(links) { file, link ->
            val fileName = file.displayName().ifBlank { name }
            CloudLibraryFile(
                id = link,
                name = fileName,
                sizeBytes = file.bytes,
                playable = isPlayableCloudFile(name = fileName, mimeType = null)
            )
        }
    } else {
        links.mapIndexed { index, link ->
            CloudLibraryFile(
                id = link,
                name = if (links.size == 1) name else "$name (${index + 1})",
                sizeBytes = if (links.size == 1) torrent.bytes else null,
                playable = true
            )
        }
    }
    return CloudLibraryItem(
        providerId = providerId,
        providerName = providerName,
        id = id,
        type = CloudLibraryItemType.Torrent,
        name = name,
        status = torrent.status,
        sizeBytes = torrent.bytes,
        progressFraction = torrent.progress?.let { (it / 100f).coerceIn(0f, 1f) },
        files = files.sortedWith(compareBy<CloudLibraryFile> { !it.playable }.thenBy { it.name.lowercase() })
    )
}

fun realDebridDownloadCloudItem(
    download: RealDebridDownloadDto,
    providerId: String,
    providerName: String
): CloudLibraryItem? {
    val id = download.id?.takeIf { it.isNotBlank() } ?: return null
    val url = download.download?.takeIf { it.isNotBlank() } ?: return null
    val name = download.filename?.takeIf { it.isNotBlank() } ?: id
    return CloudLibraryItem(
        providerId = providerId,
        providerName = providerName,
        id = id,
        type = CloudLibraryItemType.WebDownload,
        name = name,
        sizeBytes = download.filesize,
        files = listOf(
            CloudLibraryFile(
                id = id,
                name = name,
                sizeBytes = download.filesize,
                mimeType = download.mimeType,
                playable = download.streamable == 1 || isPlayableCloudFile(name = name, mimeType = download.mimeType),
                playbackUrl = url
            )
        )
    )
}
