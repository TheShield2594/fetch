package com.fetch.desktop.data

import com.fetch.tv.core.addon.AddonUrls
import com.fetch.tv.core.addon.normalizeAddonInstallUrl
import com.fetch.tv.core.streams.supportsResource
import com.fetch.tv.data.mapper.toDomain
import com.fetch.tv.data.mapper.toDomainOrNull
import com.fetch.tv.data.remote.api.AddonApi
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Stream
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/** One addon's answer to a stream request: its streams, or why it gave none. */
data class AddonStreamResult(val addon: Addon, val streams: List<Stream>, val error: String? = null)

/**
 * Talks to Stremio addons with the same API, DTOs and mappers as the TV app (from :shared).
 */
class AddonClient(private val api: AddonApi) {

    /** Normalizes [input], fetches its manifest and returns the addon, or throws with a readable message. */
    suspend fun fetchAddon(input: String): Addon {
        val baseUrl = normalizeAddonInstallUrl(input)
            ?: throw IllegalArgumentException("That isn't an addon link. It should start with https:// or stremio://")
        val manifest = call { api.getManifest(AddonUrls.manifest(baseUrl)) }
        return manifest.toDomain(baseUrl)
    }

    suspend fun catalog(addon: Addon, catalog: CatalogDescriptor): List<MetaPreview> {
        val url = AddonUrls.catalog(addon.baseUrl, catalog.apiType, catalog.id)
        val response = call { api.getCatalog(url) }
        return response.metas.mapNotNull { it?.toDomainOrNull(catalog.apiType, addon.baseUrl) }
    }

    /** Runs [query] against a catalog that takes the "search" extra. */
    suspend fun search(addon: Addon, catalog: CatalogDescriptor, query: String): List<MetaPreview> {
        val url = AddonUrls.catalog(addon.baseUrl, catalog.apiType, catalog.id, extraArgs = mapOf("search" to query))
        val response = call { api.getCatalog(url) }
        return response.metas.mapNotNull { it?.toDomainOrNull(catalog.apiType, addon.baseUrl) }
    }

    /** The first addon that serves metadata for [id] answers; null when none does. */
    suspend fun meta(addons: List<Addon>, type: String, id: String): Meta? {
        for (addon in addons.filter { it.enabled && it.supportsResource("meta", type, id) }) {
            try {
                val meta = call { api.getMeta(AddonUrls.meta(addon.baseUrl, type, id)) }.meta
                if (meta != null) return meta.toDomain()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try the next addon.
            }
        }
        return null
    }

    /** Asks every addon that serves streams for [videoId] at once; slow addons are cut off at [timeoutMs]. */
    suspend fun streams(
        addons: List<Addon>,
        type: String,
        videoId: String,
        timeoutMs: Long = STREAM_TIMEOUT_MS
    ): List<AddonStreamResult> = coroutineScope {
        addons
            .filter { it.enabled && it.supportsResource("stream", type, videoId) }
            .map { addon ->
                async {
                    try {
                        val response = withTimeoutOrNull(timeoutMs) {
                            call { api.getStreams(AddonUrls.stream(addon.baseUrl, type, videoId)) }
                        } ?: return@async AddonStreamResult(addon, emptyList(), "Timed out")
                        val streams = response.streams.orEmpty().map { it.toDomain(addon.displayName, addon.logo) }
                        AddonStreamResult(addon, streams)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AddonStreamResult(addon, emptyList(), e.message ?: "Request failed")
                    }
                }
            }
            .awaitAll()
    }

    private suspend fun <T> call(block: suspend () -> Response<T>): T {
        val response = block()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code()}")
        return response.body() ?: throw IllegalStateException("Empty response")
    }

    companion object {
        const val STREAM_TIMEOUT_MS = 20_000L

        fun create(httpClient: OkHttpClient = defaultHttpClient()): AddonClient {
            val retrofit = Retrofit.Builder()
                // Every AddonApi call passes a full @Url, so this is never used.
                .baseUrl("https://placeholder.invalid/")
                .client(httpClient)
                .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
                .build()
            return AddonClient(retrofit.create(AddonApi::class.java))
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
