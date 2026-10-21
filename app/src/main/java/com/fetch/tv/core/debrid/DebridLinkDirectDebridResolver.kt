package com.fetch.tv.core.debrid

import com.fetch.tv.data.local.DebridSettingsDataStore
import com.fetch.tv.data.remote.api.DebridLinkApi
import com.fetch.tv.data.remote.dto.DebridLinkTorrentDto
import com.fetch.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a torrent through Debrid-Link's seedbox. Debrid-Link adds a bare info hash only when
 * it already has the torrent cached, so sending the hash doubles as the cache check. File links
 * are direct and play as they are once a file is fully downloaded.
 */
@Singleton
class DebridLinkDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: DebridLinkApi,
    private val fileSelector: DebridLinkFileSelector
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = dataStore.settings.first().debridLinkApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val hash = resolve.infoHash?.trim()?.takeIf { it.isNotBlank() }
        val source = hash
            ?: resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: return DirectDebridResolveResult.Stale
        val authorization = "Bearer $apiKey"

        return try {
            val add = api.addTorrent(authorization, source)
            val added = add.body()?.takeIf { add.isSuccessful && it.success == true }?.value
            if (added == null) {
                val error = add.body()?.error ?: add.errorBody()?.string().orEmpty()
                return when {
                    add.code() == 401 || error.contains("badToken") -> DirectDebridResolveResult.Error
                    hash != null -> DirectDebridResolveResult.NotCached
                    else -> DirectDebridResolveResult.Stale
                }
            }
            val torrentId = added.id?.takeIf { it.isNotBlank() } ?: return DirectDebridResolveResult.Stale
            var resolved = false
            try {
                val files = filesOf(authorization, added)
                if (files.none { it.isReady }) return DirectDebridResolveResult.NotCached
                val file = fileSelector.selectFile(files, resolve, season, episode)
                    ?: return DirectDebridResolveResult.Stale
                val url = file.downloadUrl?.takeIf { it.isNotBlank() }
                    ?: return DirectDebridResolveResult.Stale
                resolved = true
                DirectDebridResolveResult.Success(
                    url = url,
                    filename = file.displayName().takeIf { it.isNotBlank() },
                    videoSize = file.size
                )
            } finally {
                if (!resolved) {
                    runCatching { api.removeTorrents(authorization, torrentId) }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }

    // A torrent with many files comes back as a single ZIP entry; listing it by id gives the files.
    private suspend fun filesOf(
        authorization: String,
        torrent: DebridLinkTorrentDto
    ): List<com.fetch.tv.data.remote.dto.DebridLinkTorrentFileDto> {
        if (torrent.isZip != true) return torrent.files.orEmpty()
        val id = torrent.id ?: return emptyList()
        val list = api.listTorrents(authorization, id)
        return list.body()?.takeIf { list.isSuccessful && it.success == true }
            ?.value?.firstOrNull { it.id == id }?.files
            .orEmpty()
    }
}
