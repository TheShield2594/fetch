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

    /** Where each request's time and outcome is recorded (Addons › Addon health), as on TV. */
    var health: com.fetch.desktop.addons.AddonHealthTracker? = null

    /** Runs [block] for [addon], recording how it went; an empty answer counts as [AddonRequestOutcome.EMPTY]. */
    private suspend fun <T> tracked(
        addon: Addon,
        kind: com.fetch.desktop.addons.AddonRequestKind,
        isEmpty: (T) -> Boolean = { false },
        block: suspend () -> T
    ): T {
        val tracker = health ?: return block()
        val start = System.currentTimeMillis()
        try {
            val result = block()
            val outcome = if (isEmpty(result)) com.fetch.desktop.addons.AddonRequestOutcome.EMPTY else com.fetch.desktop.addons.AddonRequestOutcome.SUCCESS
            tracker.record(addon.displayName, outcome, System.currentTimeMillis() - start, kind = kind)
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracker.record(addon.displayName, com.fetch.desktop.addons.AddonRequestOutcome.ERROR, System.currentTimeMillis() - start, e.message ?: e.javaClass.simpleName, kind)
            throw e
        }
    }

    /** Normalizes [input], fetches its manifest and returns the addon, or throws with a readable message. */
    suspend fun fetchAddon(input: String): Addon {
        val baseUrl = normalizeAddonInstallUrl(input)
            ?: throw IllegalArgumentException("That isn't an addon link. It should start with https:// or stremio://")
        val manifest = call { api.getManifest(AddonUrls.manifest(baseUrl)) }
        return manifest.toDomain(baseUrl)
    }

    /** One page of a catalog; [extra] carries filters such as a genre, [skip] pages through it. */
    suspend fun catalog(
        addon: Addon,
        catalog: CatalogDescriptor,
        skip: Int = 0,
        extra: Map<String, String> = emptyMap()
    ): List<MetaPreview> {
        val url = AddonUrls.catalog(addon.baseUrl, catalog.apiType, catalog.id, skip = skip, extraArgs = extra)
        val response = tracked(addon, com.fetch.desktop.addons.AddonRequestKind.CATALOG) { call { api.getCatalog(url) } }
        return response.metas.mapNotNull { it?.toDomainOrNull(catalog.apiType, addon.baseUrl) }
    }

    /** Runs [query] against a catalog that takes the "search" extra. */
    suspend fun search(addon: Addon, catalog: CatalogDescriptor, query: String): List<MetaPreview> {
        val url = AddonUrls.catalog(addon.baseUrl, catalog.apiType, catalog.id, extraArgs = mapOf("search" to query))
        val response = tracked(addon, com.fetch.desktop.addons.AddonRequestKind.CATALOG) { call { api.getCatalog(url) } }
        return response.metas.mapNotNull { it?.toDomainOrNull(catalog.apiType, addon.baseUrl) }
    }

    /**
     * Subtitles for [videoId] from every addon that serves them, as (language, url) pairs. A slow
     * or broken addon just adds nothing.
     */
    suspend fun subtitles(addons: List<Addon>, type: String, videoId: String, timeoutMs: Long = 8_000): List<Pair<String, String>> =
        coroutineScope {
            addons.filter { it.enabled && it.supportsResource("subtitles", type, videoId) }.map { addon ->
                async {
                    try {
                        val (path, query) = AddonUrls.split(addon.baseUrl)
                        val url = "$path/subtitles/${AddonUrls.encode(type)}/${AddonUrls.encode(videoId)}.json$query"
                        withTimeoutOrNull(timeoutMs) { call { api.getSubtitles(url) } }?.subtitles.orEmpty()
                            .mapNotNull { s ->
                                val link = s.url?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                                (s.lang ?: s.language ?: "").lowercase() to link
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

    /** The first addon that serves metadata for [id] answers; null when none does. */
    suspend fun meta(addons: List<Addon>, type: String, id: String): Meta? {
        for (addon in addons.filter { it.enabled && it.supportsResource("meta", type, id) }) {
            try {
                val meta = tracked(addon, com.fetch.desktop.addons.AddonRequestKind.META) { call { api.getMeta(AddonUrls.meta(addon.baseUrl, type, id)) } }.meta
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
                    val start = System.currentTimeMillis()
                    fun note(outcome: com.fetch.desktop.addons.AddonRequestOutcome, error: String? = null) =
                        health?.record(addon.displayName, outcome, System.currentTimeMillis() - start, error, com.fetch.desktop.addons.AddonRequestKind.STREAM)
                    try {
                        val response = withTimeoutOrNull(timeoutMs) {
                            call { api.getStreams(AddonUrls.stream(addon.baseUrl, type, videoId)) }
                        } ?: return@async AddonStreamResult(addon, emptyList(), "Timed out").also {
                            note(com.fetch.desktop.addons.AddonRequestOutcome.TIMEOUT, "Timed out")
                        }
                        val streams = response.streams.orEmpty().map { it.toDomain(addon.displayName, addon.logo) }
                        note(if (streams.isEmpty()) com.fetch.desktop.addons.AddonRequestOutcome.EMPTY else com.fetch.desktop.addons.AddonRequestOutcome.SUCCESS)
                        AddonStreamResult(addon, streams)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        note(com.fetch.desktop.addons.AddonRequestOutcome.ERROR, e.message ?: "Request failed")
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
