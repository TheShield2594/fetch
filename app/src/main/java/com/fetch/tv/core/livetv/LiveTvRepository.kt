package com.fetch.tv.core.livetv

import android.util.Log
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.data.mediaserver.ServerRepository
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.enabledAddons
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.domain.repository.CatalogRepository
import com.fetch.tv.core.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Where Live TV channels come from (#660): an addon's `tv` catalog, or a media server. */
sealed interface LiveTvSource {
    val key: String
    val label: String

    data class AddonCatalog(val addon: Addon, val catalog: CatalogDescriptor) : LiveTvSource {
        override val key: String get() = "addon:${addon.baseUrl}:${catalog.apiType}:${catalog.id}"
        override val label: String
            get() = if (catalog.name.isBlank() || catalog.name.equals(addon.displayName, ignoreCase = true)) {
                addon.displayName
            } else {
                "${addon.displayName} · ${catalog.name}"
            }
    }

    data class MediaServer(val connectionId: String, override val label: String) : LiveTvSource {
        override val key: String get() = "server:$connectionId"
    }
}

/** A channel in the guide. [videoId] and [type] are what the stream screen is opened with. */
data class LiveTvChannel(
    val sourceKey: String,
    val videoId: String,
    val type: String,
    val name: String,
    val number: String? = null,
    val logo: String? = null,
    val description: String? = null
) {
    val key: String get() = "$sourceKey|$videoId"
}

/** A programme in the guide, in epoch milliseconds. */
data class LiveTvProgram(
    val channelKey: String,
    val title: String,
    val startMs: Long,
    val endMs: Long
)

/** A recording on a media server, played like a film. */
data class LiveTvRecording(
    val videoId: String,
    val title: String,
    val image: String?,
    val description: String?,
    val sourceLabel: String
)

/**
 * Live TV from the sources a profile already has (#660): addon catalogs of the Stremio `tv` type
 * (IPTV and live-TV addons), and Live TV on Jellyfin and Emby servers. Addons have no guide in
 * the Stremio protocol, so their channels show as live without programmes; a server's guide
 * comes with it.
 */
@Singleton
class LiveTvRepository @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val serverRepository: ServerRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every enabled addon catalog that lists live channels. */
    val addonSources: Flow<List<LiveTvSource.AddonCatalog>> = addonRepository.getInstalledAddons()
        .map { addons -> liveCatalogs(addons) }
        .distinctUntilChanged()

    private val serversWithChannels = MutableStateFlow<Set<String>>(emptySet())
    @Volatile
    private var probedServers: Set<String>? = null

    /**
     * Whether there is anything to show: a live addon catalog, or a server that answered with
     * channels when last asked ([probeServers]). Drives the sidebar entry.
     */
    val hasSources: Flow<Boolean> = combine(addonSources, serversWithChannels) { addons, servers ->
        addons.isNotEmpty() || servers.isNotEmpty()
    }.distinctUntilChanged()

    /** Media servers with Live TV channels, as found by [probeServers]. */
    val serverSources: StateFlow<Set<String>> = serversWithChannels.asStateFlow()

    /**
     * Asks each signed-in Jellyfin and Emby server whether it has Live TV, once per set of
     * connections. Quiet on failure: a server that can't be reached just isn't a source.
     */
    fun probeServers() {
        // Reading the connections can touch storage, so even that is off the caller's thread.
        scope.launch {
            val connections = serverRepository.enabledConnections()
            val ids = connections.mapTo(HashSet()) { it.id }
            synchronized(this@LiveTvRepository) {
                if (ids == probedServers) return@launch
                probedServers = ids
            }
            val found = connections.mapNotNull { connection ->
                val channels = runSuspendCatching {
                    withTimeoutOrNull(SERVER_PROBE_TIMEOUT_MS) {
                        serverRepository.call(connection.id) { provider, session -> provider.liveTvChannels(session) }
                    }
                }.onFailure { Log.d(TAG, "No Live TV from ${connection.id}: ${it.message}") }.getOrNull()
                connection.id.takeIf { !channels.isNullOrEmpty() }
            }.toSet()
            serversWithChannels.value = found
        }
    }

    /** The addon catalogs and servers to show, addon ones first, in addon order. */
    suspend fun sources(): List<LiveTvSource> {
        val addons = addonSources.first()
        val servers = serverRepository.enabledConnections()
            .filter { it.id in serversWithChannels.value }
            .map { LiveTvSource.MediaServer(it.id, serverRepository.sourceLabel(it)) }
        return addons + servers
    }

    suspend fun channels(source: LiveTvSource): List<LiveTvChannel> = when (source) {
        is LiveTvSource.AddonCatalog -> addonChannels(source)
        is LiveTvSource.MediaServer -> serverRepository.call(source.connectionId) { provider, session ->
            provider.liveTvChannels(session)
        }.map { channel ->
            LiveTvChannel(
                sourceKey = source.key,
                videoId = ServerItemRef(source.connectionId, channel.itemId).encode(),
                type = LIVE_TYPE,
                name = channel.name,
                number = channel.number,
                logo = channel.logo,
                description = channel.currentProgram?.title
            )
        }
    }

    /** The guide for [channels] between [fromMs] and [toMs]. Addon channels have none. */
    suspend fun programs(
        source: LiveTvSource,
        channels: List<LiveTvChannel>,
        fromMs: Long,
        toMs: Long
    ): List<LiveTvProgram> {
        if (source !is LiveTvSource.MediaServer || channels.isEmpty()) return emptyList()
        val byItemId = channels.associateBy { ServerItemRef.parse(it.videoId)?.itemId }
        val programs = serverRepository.call(source.connectionId) { provider, session ->
            provider.liveTvPrograms(session, byItemId.keys.filterNotNull(), fromMs, toMs)
        }
        return programs.mapNotNull { program ->
            val channel = byItemId[program.channelItemId] ?: return@mapNotNull null
            LiveTvProgram(
                channelKey = channel.key,
                title = program.episodeTitle?.takeIf { it.isNotBlank() }?.let { "${program.title}: $it" } ?: program.title,
                startMs = program.startMs,
                endMs = program.endMs
            )
        }
    }

    /** Recordings from every server with Live TV, newest first per server. */
    suspend fun recordings(): List<LiveTvRecording> = coroutineScope {
        serverRepository.enabledConnections()
            .filter { it.id in serversWithChannels.value }
            .map { connection ->
                async {
                    runSuspendCatching {
                        serverRepository.call(connection.id) { provider, session -> provider.liveTvRecordings(session, RECORDINGS_LIMIT) }
                    }.getOrDefault(emptyList()).map { title ->
                        LiveTvRecording(
                            videoId = title.preview.id,
                            title = title.preview.name,
                            image = title.preview.poster,
                            description = title.preview.description,
                            sourceLabel = serverRepository.sourceLabel(connection)
                        )
                    }
                }
            }
            .awaitAll()
            .flatten()
    }

    private suspend fun addonChannels(source: LiveTvSource.AddonCatalog): List<LiveTvChannel> {
        val addon = source.addon
        val catalog = source.catalog
        val result = catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            supportsSkip = catalog.extra.any { it.name.equals("skip", ignoreCase = true) },
            skipStep = catalog.pageSize ?: DEFAULT_PAGE_SIZE
        ).first { it !is NetworkResult.Loading }
        val row = when (result) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> throw IllegalStateException(result.message)
            NetworkResult.Loading -> return emptyList()
        }
        return row.items.map { item ->
            LiveTvChannel(
                sourceKey = source.key,
                videoId = item.id,
                type = item.rawType.ifBlank { catalog.apiType },
                name = item.name,
                logo = item.logo ?: item.poster,
                description = item.description
            )
        }
    }

    companion object {
        private const val TAG = "LiveTvRepository"

        /** The Stremio type of live TV, and what a server channel is opened as. */
        const val LIVE_TYPE = "tv"
        private const val DEFAULT_PAGE_SIZE = 100
        private const val SERVER_PROBE_TIMEOUT_MS = 8_000L
        private const val RECORDINGS_LIMIT = 30

        /**
         * Catalogs of the Stremio `tv` type that can be browsed: not search-only, and not needing
         * an extra (a genre, say) the guide has no way to choose.
         */
        internal fun liveCatalogs(addons: List<Addon>): List<LiveTvSource.AddonCatalog> =
            addons.enabledAddons().flatMap { addon ->
                addon.catalogs
                    .filter { catalog ->
                        catalog.apiType.equals(LIVE_TYPE, ignoreCase = true) &&
                            !catalog.isSearchOnlyCatalog() &&
                            catalog.extraRequired.none { !it.equals("skip", ignoreCase = true) } &&
                            catalog.extra.none { it.isRequired && !it.name.equals("skip", ignoreCase = true) }
                    }
                    .map { LiveTvSource.AddonCatalog(addon, it) }
            }
    }
}
