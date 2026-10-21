package com.fetch.tv.core.debrid

import com.fetch.tv.data.local.DebridSettingsDataStore
import com.fetch.tv.data.remote.api.AllDebridApi
import com.fetch.tv.data.remote.dto.AllDebridEnvelopeDto
import com.fetch.tv.data.remote.dto.flattenAllDebridFiles
import com.fetch.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a torrent through AllDebrid. AllDebrid has no instant-availability check, so the
 * magnet is uploaded and only taken when AllDebrid reports it ready at once (it already has the
 * files); anything that would need a download is removed again and reported as not cached.
 */
@Singleton
class AllDebridDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: AllDebridApi,
    private val fileSelector: AllDebridFileSelector
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = dataStore.settings.first().allDebridApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: resolve.infoHash?.takeIf { it.isNotBlank() }
            ?: return DirectDebridResolveResult.Stale
        val authorization = "Bearer $apiKey"

        return try {
            val upload = api.uploadMagnet(authorization, magnet)
            if (upload.isAuthFailure()) return DirectDebridResolveResult.Error
            val uploaded = upload.body()?.takeIf { upload.isSuccessful && it.isSuccess }
                ?.data?.magnets?.firstOrNull()
                ?: return DirectDebridResolveResult.Stale
            if (uploaded.error != null) return DirectDebridResolveResult.Stale
            val magnetId = uploaded.id ?: return DirectDebridResolveResult.Stale
            var resolved = false
            try {
                if (uploaded.ready != true) return DirectDebridResolveResult.NotCached
                val filesResponse = api.getMagnetFiles(authorization, magnetId)
                val files = filesResponse.body()?.takeIf { filesResponse.isSuccessful && it.isSuccess }
                    ?.data?.magnets?.firstOrNull()?.files
                    ?.flattenAllDebridFiles()
                    .orEmpty()
                val file = fileSelector.selectFile(files, resolve, season, episode)
                    ?: return DirectDebridResolveResult.Stale
                val unlock = api.unlockLink(authorization, file.link)
                val unlocked = unlock.body()?.takeIf { unlock.isSuccessful && it.isSuccess }?.data
                    ?: return DirectDebridResolveResult.Stale
                // A delayed link is still being generated and has no URL to play yet.
                if (unlocked.delayed != null) return DirectDebridResolveResult.Stale
                val url = unlocked.link?.takeIf { it.isNotBlank() }
                    ?: return DirectDebridResolveResult.Stale
                resolved = true
                DirectDebridResolveResult.Success(
                    url = url,
                    filename = unlocked.filename?.takeIf { it.isNotBlank() }
                        ?: file.displayName().takeIf { it.isNotBlank() },
                    videoSize = unlocked.filesize ?: file.size
                )
            } finally {
                if (!resolved) {
                    runCatching { api.deleteMagnet(authorization, magnetId) }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }

    private fun Response<out AllDebridEnvelopeDto<*>>.isAuthFailure(): Boolean =
        code() == 401 || code() == 403 || body()?.error?.code?.startsWith("AUTH_") == true
}
