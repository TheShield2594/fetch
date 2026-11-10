package com.fetch.tv.data.repository

import com.fetch.tv.core.addon.AddonUrls
import com.fetch.tv.core.streams.AddonCallResult
import com.fetch.tv.core.streams.AddonRequestGuard
import com.fetch.tv.core.streams.AddonRequestKind
import com.fetch.tv.core.streams.AddonRequestOutcome
import com.fetch.tv.core.streams.metaIdForVideoId
import com.fetch.tv.core.streams.withoutUnplayableStreams
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.fetch.tv.R
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.network.safeApiCall
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.mapper.toDomain
import com.fetch.tv.data.mediaserver.ServerStreamSource
import com.fetch.tv.data.mediaserver.ServerStreams
import com.fetch.tv.data.mediaserver.messageRes
import com.fetch.tv.data.mediaserver.serverFailure
import com.fetch.tv.data.remote.api.AddonApi
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamSearchUpdate
import com.fetch.tv.domain.model.StreamSourceOutcome
import com.fetch.tv.core.streams.supportsResource
import com.fetch.tv.core.streams.supportsStreamResource
import com.fetch.tv.domain.model.enabledAddons
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.domain.repository.StreamRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject

private const val TAG = "StreamRepositoryImpl"

class StreamRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val profileManager: ProfileManager,
    private val serverStreams: ServerStreams,
    private val addonRequestGuard: AddonRequestGuard
) : StreamRepository {
    private val streamSearchSessions = StreamSearchSessionCache()
    private enum class StreamFailureKind {
        MISSING,
        REQUEST_FAILED
    }

    private data class StreamAttemptFailure(
        val addonName: String,
        val kind: StreamFailureKind,
        val detail: String
    )

    private data class StreamSourceConfigurationSnapshot(
        val profileId: Int,
        val addons: List<Addon>
    )

    override fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean
    ): Flow<NetworkResult<List<AddonStreams>>> =
        // An update that only adds a source's outcome repeats the same result; skip it here.
        searchStreamsFromAllSources(type, videoId, season, episode, forceRefresh)
            .map { it.result }
            .distinctUntilChanged { old, new -> old === new }

    override fun searchStreamsFromAllSources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean
    ): Flow<StreamSearchUpdate> = flow {
        val sourceConfiguration = captureSourceConfiguration()
        val isNativeServerRequest = serverStreams.isNativeRequest(videoId)
        val requestKey = buildStreamSearchRequestKey(sourceConfiguration, type, videoId, season, episode)

        emitAll(
            streamSearchSessions.observe(
                key = requestKey,
                forceRefresh = forceRefresh
            ) { onSourceFinished ->
                fetchStreamsFromAllSources(
                    onSourceFinished = onSourceFinished,
                    type = type,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    addons = if (isNativeServerRequest) emptyList() else sourceConfiguration.addons,
                    serverSources = serverStreams.sources(type, videoId, season, episode, forceRefresh),
                    forceRefresh = forceRefresh
                )
            }
        )
    }

    override suspend fun getCachedStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): List<AddonStreams>? = streamSearchSessions.peek(
        buildStreamSearchRequestKey(captureSourceConfiguration(), type, videoId, season, episode)
    )

    private fun buildStreamSearchRequestKey(
        sourceConfiguration: StreamSourceConfigurationSnapshot,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): StreamSearchRequestKey = StreamSearchRequestKey(
        profileId = sourceConfiguration.profileId,
        type = type.lowercase(),
        videoId = videoId,
        season = season,
        episode = episode,
        sourceConfiguration = buildSourceConfigurationKey(
            addons = sourceConfiguration.addons,
            serverRevision = serverStreams.revision
        )
    )

    private suspend fun captureSourceConfiguration(): StreamSourceConfigurationSnapshot {
        while (true) {
            val profileId = profileManager.activeProfileId.value
            val addons = addonRepository.getInstalledAddons().first().enabledAddons()

            if (profileManager.activeProfileId.value != profileId) continue

            return StreamSourceConfigurationSnapshot(
                profileId = profileId,
                addons = addons
            )
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun fetchStreamsFromAllSources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        addons: List<Addon>,
        serverSources: List<ServerStreamSource>,
        forceRefresh: Boolean = false,
        onSourceFinished: (sourceName: String, outcome: StreamSourceOutcome) -> Unit = { _, _ -> }
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)

        try {
            // Filter addons that support streams for this type and id
            val streamAddons = addons.filter { addon ->
                addon.supportsStreamResource(type, videoId)
            }

            val attemptedAddonNames = streamAddons.map { it.displayName } + serverSources.map { it.name }
            val attemptedFailures = java.util.Collections.synchronizedList(
                mutableListOf<StreamAttemptFailure>()
            )

            // Accumulate results as they arrive
            val accumulatedResults = mutableListOf<AddonStreams>()

            coroutineScope {
                // Channel to receive results as they complete
                val resultChannel = Channel<AddonStreams>(Channel.UNLIMITED)
                
                // Track number of pending jobs
                val totalJobs = streamAddons.size + serverSources.size
                val completedJobs = java.util.concurrent.atomic.AtomicInteger(0)
                if (totalJobs == 0) resultChannel.close()

                // Launch addon jobs
                streamAddons.forEach { addon ->
                    launch {
                        try {
                            // A dead addon must not hold the whole search hostage: give each one
                            // its own budget and report it as timed out instead of waiting on OkHttp.
                            // The inline-meta fallback runs inside the same budget, so the health
                            // event (and the addon's chip) only settles once the addon is done.
                            val call = addonRequestGuard.run(
                                addonName = addon.displayName,
                                kind = AddonRequestKind.STREAM,
                                classify = { result ->
                                    when {
                                        // A 404 means the addon has nothing for this title, not that it's down.
                                        result is NetworkResult.Error && result.code == 404 -> AddonRequestOutcome.EMPTY
                                        result is NetworkResult.Error -> AddonRequestOutcome.ERROR
                                        result is NetworkResult.Success && result.data.isEmpty() ->
                                            AddonRequestOutcome.EMPTY
                                        else -> AddonRequestOutcome.SUCCESS
                                    }
                                },
                                errorOf = { (it as? NetworkResult.Error)?.message }
                            ) {
                                val streams = getStreamsFromAddon(addon, type, videoId, forceRefresh)
                                if (streams is NetworkResult.Success && streams.data.isEmpty() &&
                                    canHaveInlineMetaStreams(addon, type, videoId)
                                ) {
                                    // The stream endpoint answered empty: some addons only list
                                    // streams on the meta's video entries.
                                    NetworkResult.Success(
                                        fetchInlineStreamsFromMeta(addon, type, videoId, forceRefresh)
                                    )
                                } else {
                                    streams
                                }
                            }
                            val streamsResult = when (call) {
                                is AddonCallResult.Completed -> call.value
                                AddonCallResult.TimedOut -> {
                                    attemptedFailures += StreamAttemptFailure(
                                        addonName = addon.displayName,
                                        kind = StreamFailureKind.REQUEST_FAILED,
                                        detail = context.getString(com.fetch.tv.R.string.stream_error_detail_addon_timeout)
                                    )
                                    onSourceFinished(addon.displayName, StreamSourceOutcome.TIMED_OUT)
                                    return@launch
                                }
                            }
                            when (streamsResult) {
                                is NetworkResult.Success -> {
                                    if (streamsResult.data.isNotEmpty()) {
                                        val namedStreams = streamsResult.data.map {
                                            it.copy(addonName = addon.displayName, addonLogo = addon.logo)
                                        }
                                        resultChannel.send(
                                            AddonStreams(
                                                addonName = addon.displayName,
                                                addonLogo = addon.logo,
                                                streams = namedStreams
                                            )
                                        )
                                        onSourceFinished(addon.displayName, StreamSourceOutcome.STREAMS)
                                    } else {
                                        attemptedFailures += buildMissingStreamFailure(addon)
                                        onSourceFinished(addon.displayName, StreamSourceOutcome.EMPTY)
                                    }
                                }
                                is NetworkResult.Error -> {
                                    val failure = buildAddonFailure(addon, streamsResult)
                                    attemptedFailures += failure
                                    onSourceFinished(
                                        addon.displayName,
                                        if (failure.kind == StreamFailureKind.MISSING) StreamSourceOutcome.EMPTY else StreamSourceOutcome.FAILED
                                    )
                                }
                                NetworkResult.Loading -> Unit
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Addon ${addon.name} failed: ${e.message}")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = addon.displayName,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = e.message ?: context.getString(com.fetch.tv.R.string.stream_error_detail_addon_request_failed)
                            )
                            onSourceFinished(addon.displayName, StreamSourceOutcome.FAILED)
                        } finally {
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                serverSources.forEach { source ->
                    launch {
                        try {
                            val streams = source.load()
                            if (streams.isNotEmpty()) {
                                resultChannel.send(AddonStreams(addonName = source.name, addonLogo = null, streams = streams))
                                onSourceFinished(source.name, StreamSourceOutcome.STREAMS)
                            } else {
                                attemptedFailures += StreamAttemptFailure(
                                    addonName = source.name,
                                    kind = StreamFailureKind.MISSING,
                                    detail = context.getString(R.string.stream_error_detail_no_streams_for_id)
                                )
                                // A media server without this title answered; it isn't broken.
                                onSourceFinished(source.name, StreamSourceOutcome.EMPTY)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            attemptedFailures += StreamAttemptFailure(
                                addonName = source.name,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = context.getString(e.serverFailure().messageRes())
                            )
                            onSourceFinished(source.name, StreamSourceOutcome.FAILED)
                        } finally {
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Emit results as they arrive
                for (result in resultChannel) {
                    mergePresentedResult(accumulatedResults, result)
                    emit(NetworkResult.Success(accumulatedResults.toList()))
                    Log.d(TAG, "Emitted ${accumulatedResults.size} addon(s), latest: ${result.addonName} with ${result.streams.size} streams")
                }
            }

            // Emit final result (even if empty)
            if (accumulatedResults.isEmpty()) {
                val errorMessage = buildAggregateFailureMessage(
                    type = type,
                    id = videoId,
                    season = season,
                    episode = episode,
                    attemptedAddonNames = attemptedAddonNames,
                    failures = attemptedFailures.toList()
                )
                if (errorMessage != null) {
                    emit(NetworkResult.Error(errorMessage))
                } else {
                    emit(NetworkResult.Success(emptyList()))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to fetch streams: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: context.getString(com.fetch.tv.R.string.stream_error_fetch_failed)))
        }
    }

    private fun buildSourceConfigurationKey(
        addons: List<Addon>,
        serverRevision: Int
    ): String = buildString {
        append("addons:")
        addons.forEach { addon ->
            append("|addon:").append(addon)
        }
        append("|servers:").append(serverRevision)
    }.sha256()

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun mergePresentedResult(
        accumulatedResults: MutableList<AddonStreams>,
        result: AddonStreams
    ) {
        val existingIndex = accumulatedResults.indexOfFirst { it.addonName == result.addonName }
        if (existingIndex >= 0) {
            val existing = accumulatedResults[existingIndex]
            val merged = existing.copy(
                streams = mergeStreams(existing.streams, result.streams),
                // The same addon can answer more than once with overlapping results (cache, then
                // network); mergeStreams dedupes the streams, so don't double count the hidden ones.
                hiddenUnplayableCount = maxOf(existing.hiddenUnplayableCount, result.hiddenUnplayableCount)
            )
            accumulatedResults[existingIndex] = presentStreams(merged)
        } else {
            accumulatedResults.add(presentStreams(result))
        }
    }

    // Fetch plays debrid and direct links only. It has no built-in debrid service and no torrent
    // engine, so drop streams an addon left for the client to resolve and bare torrents. The
    // group keeps a count of what was dropped, so the UI can say why an addon came back empty.
    private fun presentStreams(result: AddonStreams): AddonStreams = result.withoutUnplayableStreams()

    private fun mergeStreams(existing: List<Stream>, incoming: List<Stream>): List<Stream> {
        val streamsByKey = LinkedHashMap<String, Stream>()
        existing.forEach { stream -> streamsByKey[stream.dedupKey()] = stream }
        incoming.forEach { stream -> streamsByKey[stream.dedupKey()] = stream }
        return streamsByKey.values.toList()
    }

    private fun Stream.dedupKey(): String {
        val base = infoHash?.lowercase()?.let { hash -> "$hash:${fileIdx ?: ""}" }
            ?: clientResolve?.let { resolve -> resolve.infoHash?.lowercase()?.let { hash -> "$hash:${resolve.fileIdx}" } }
            ?: url
            ?: externalUrl
            ?: ytId
            ?: serverTarget?.key()
            ?: "${addonName}:${name}:${title}"
        val nameSuffix = if (base == url) {
            val discriminator = name?.takeIf { it.isNotBlank() }
            if (discriminator != null) "|$discriminator" else ""
        } else ""
        return "$base$nameSuffix"
    }

    override suspend fun getStreamsFromAddon(
        addon: Addon,
        type: String,
        videoId: String,
        forceRefresh: Boolean
    ): NetworkResult<List<Stream>> {
        val streamUrl = AddonUrls.stream(addon.baseUrl, type, videoId)
        Log.d(TAG, "Fetching streams type=$type videoId=$videoId url=$streamUrl")

        // Display info comes from the installed addon the caller already holds. Calling
        // addonRepository.fetchAddon() here caused an unconditional manifest GET ahead of every
        // queried addon's stream request: fetchAddon is the low-level fetch that does not consult
        // the cache, so this sidestepped the manifest-cache policy in AddonRepositoryImpl.
        val addonName = addon.displayName
        val addonLogo = addon.logo

        return when (val result = safeApiCall(context) { api.getStreams(streamUrl, cacheControlFor(forceRefresh)) }) {
            is NetworkResult.Success -> {
                val streams = result.data.streams?.map { 
                    it.toDomain(addonName, addonLogo) 
                } ?: emptyList()
                Log.d(TAG, "Streams success addon=$addonName count=${streams.size} url=$streamUrl")
                NetworkResult.Success(streams)
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Streams failed addon=$addonName code=${result.code} message=${result.message} url=$streamUrl"
                )
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Refresh/Retry must reach the addon: the addon client's HTTP cache honours the addon's own
     * max-age (hours, for Torrentio and similar), so a plain GET would get the same stale list.
     */
    private fun cacheControlFor(forceRefresh: Boolean): String? = if (forceRefresh) "no-cache" else null

    /**
     * Whether an empty /stream answer is worth a /meta request for inline streams: only a meta
     * with per-video entries can carry them (so never a movie), and only an addon that serves
     * meta for this id can answer.
     */
    private fun canHaveInlineMetaStreams(addon: Addon, type: String, videoId: String): Boolean {
        if (type.equals("movie", ignoreCase = true)) return false
        return addon.supportsResource("meta", type, inlineMetaIdFor(videoId))
    }

    /**
     * Fetch meta for the given content and extract inline streams from the
     * matching video entry.  Returns an empty list when the video has no
     * inline streams. Callers check [canHaveInlineMetaStreams] first.
     */
    private suspend fun fetchInlineStreamsFromMeta(
        addon: Addon,
        type: String,
        videoId: String,
        forceRefresh: Boolean
    ): List<Stream> {
        val metaId = inlineMetaIdFor(videoId)
        val cleanBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val encodedType = encodePathSegment(type)
        val encodedMetaId = encodePathSegment(metaId)
        val metaUrl = "$basePath/meta/$encodedType/$encodedMetaId.json$baseQuery"
        Log.d(TAG, "Fetching inline streams via meta type=$type metaId=$metaId videoId=$videoId url=$metaUrl")
        return try {
            when (val result = safeApiCall(context) { api.getMeta(metaUrl, cacheControlFor(forceRefresh)) }) {
                is NetworkResult.Success -> {
                    val metaDto = result.data.meta ?: return emptyList()
                    val matchingVideo = metaDto.videos?.firstOrNull { it.id == videoId }
                    val streams = matchingVideo?.streams
                        ?.mapNotNull { it.toDomain(addon.displayName, addon.logo) }
                        ?: emptyList()
                    Log.d(TAG, "Inline streams from meta: addon=${addon.displayName} videoId=$videoId found=${streams.size}")
                    streams
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch inline streams from meta for ${addon.displayName}: ${e.message}")
            emptyList()
        }
    }

    private fun inlineMetaIdFor(videoId: String): String = metaIdForVideoId(videoId)

    private fun buildMissingStreamFailure(addon: Addon): StreamAttemptFailure {
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.MISSING,
            detail = context.getString(com.fetch.tv.R.string.stream_error_detail_no_streams_for_id)
        )
    }

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): StreamAttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingStreamFailure(addon)
        }
        val normalizedReason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                context.getString(com.fetch.tv.R.string.stream_error_detail_addon_unreachable)
            error.message.contains("Failed to connect", ignoreCase = true) ->
                context.getString(com.fetch.tv.R.string.stream_error_detail_addon_connection_failed)
            error.message.contains("timeout", ignoreCase = true) ->
                context.getString(com.fetch.tv.R.string.stream_error_detail_addon_timeout)
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                context.getString(com.fetch.tv.R.string.stream_error_detail_addon_cleartext_blocked)
            error.message.isBlank() ->
                context.getString(com.fetch.tv.R.string.stream_error_detail_addon_request_failed)
            else -> error.message.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        val httpSuffix = error.code?.let { " (HTTP $it)" } ?: ""
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.REQUEST_FAILED,
            detail = "$normalizedReason$httpSuffix"
        )
    }

    /**
     * The Stream screen's failure text: a plain title ("No sources for S1 E2"), a blank line, then
     * which addons were asked and what went wrong with each. The id and type stay as format
     * arguments of the detail strings so existing translations keep working, but the default
     * text doesn't show them.
     */
    @SuppressLint("StringFormatMatches") // See above: the extra arguments are for the translations.
    private fun buildAggregateFailureMessage(
        type: String,
        id: String,
        season: Int?,
        episode: Int?,
        attemptedAddonNames: List<String>,
        failures: List<StreamAttemptFailure>
    ): String? {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(R.string.error_stream_no_supported_addon, type)
        }

        val title = if (season != null && episode != null) {
            context.getString(R.string.error_stream_no_sources_episode, season, episode)
        } else {
            context.getString(R.string.error_stream_no_sources)
        }
        fun withTitle(detail: String) = "$title\n\n$detail"

        val triedAddons = attemptedAddonNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == StreamFailureKind.MISSING }
        if (failures.isEmpty() || missingOnly) {
            return withTitle(context.getString(R.string.error_stream_tried_none, triedAddons, id, type))
        }

        val issueSummary = failures
            .filter { it.kind == StreamFailureKind.REQUEST_FAILED }
            .distinctBy { it.addonName to it.detail }
            .take(3)
            .joinToString("\n") { "${it.addonName}: ${it.detail}" }

        return if (issueSummary.isBlank()) {
            withTitle(context.getString(R.string.error_stream_tried_generic, triedAddons, id, type))
        } else {
            withTitle(context.getString(R.string.error_stream_tried_issues, triedAddons, id, type, issueSummary))
        }
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
