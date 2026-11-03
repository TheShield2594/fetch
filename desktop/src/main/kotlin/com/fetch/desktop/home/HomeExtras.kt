package com.fetch.desktop.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.AddonClient
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.tmdb.MetaPreviewEnricher
import com.fetch.tv.core.streams.ranking.RankedStream
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.EpisodeShuffle
import com.fetch.tv.domain.model.EpisodeShuffleSettings
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A Continue Watching card's artwork after Layout's and TMDB's options. */
data class ContinueWatchingArt(
    val name: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    /** The episode's still, for "Use Episode Thumbnails". */
    val still: String? = null
)

/**
 * Home's extras over [com.fetch.desktop.AppState]: the Best match cache and the background ready
 * check behind Ready tonight, Continue Watching artwork (episode stills, TMDB enrichment),
 * TMDB enrichment of Home's hero (the TV's modern Home), and the shuffle sessions.
 */
class HomeExtras(
    private val scope: CoroutineScope,
    private val client: AddonClient,
    private val addons: () -> List<Addon>,
    private val enricher: MetaPreviewEnricher,
    private val settings: () -> DesktopSettings,
    /** The profile's folder: `best-match.json` and `shuffle.json` live here. */
    dir: java.io.File,
    /** Every source for a video (addons and servers), ranked by Stream Selection's rules. */
    private val rankedSources: suspend (type: String, videoId: String, name: String) -> List<RankedStream>,
    private val autoPlayIsBestMatch: () -> Boolean
) {
    // --- Best match cache and Ready tonight ---

    private val store: JsonFile<BestMatchFile> = bestMatchFile(dir)
    var bestMatches: Map<String, CachedBestMatch> by mutableStateOf(store.load().entries)
        private set

    /** Remembers [best] as [videoId]'s Best match, from the title page or a background check. */
    fun saveBestMatch(type: String, videoId: String, best: RankedStream?) {
        if (best == null || type.isBlank() || videoId.isBlank()) return
        val labels = best.descriptor.summaryLabels()
        val cached = best.descriptor.isDebridCached
        if (labels.isEmpty() && !cached) return
        val merged = ReadyTonight.merge(bestMatches, ReadyTonight.contentKey(type, videoId), CachedBestMatch(labels, cached, System.currentTimeMillis())) ?: return
        bestMatches = merged
        runCatching { store.save(BestMatchFile(merged)) }
    }

    fun readyKeys(nowMs: Long = System.currentTimeMillis()): Set<String> = ReadyTonight.readyKeys(bestMatches, nowMs)

    private val lastAttemptMs = mutableMapOf<String, Long>()
    private var checkJob: Job? = null

    /**
     * Asks the addons about a few Continue Watching titles, then watchlist movies, one at a time,
     * so Ready tonight can show the ones that start at once. As on TV: only with Best match as
     * the Play mode and "Check what's ready" on, on Wi-Fi or Ethernet that isn't metered
     * ([NetworkMetering]); each title at most every 12 hours.
     */
    fun checkReady(entries: List<ProgressEntry>, watchlist: List<LibraryEntry>) {
        if (!settings().layout.readyCheckEnabled || !autoPlayIsBestMatch()) return
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            delay(ReadyTonight.CHECK_SETTLE_MS)
            awaitUnmetered()
            val requests = ReadyTonight.candidates(entries, bestMatches, lastAttemptMs, System.currentTimeMillis(), watchlist)
            for (r in requests) {
                // As on TV: no lookups while the connection is metered; Home staying open picks up again once it isn't.
                awaitUnmetered()
                try {
                    val ranked = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { rankedSources(r.type, r.videoId, r.name) }
                    saveBestMatch(r.type, r.videoId, ranked?.firstOrNull { it.isEligible })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Counted as an attempt: a broken addon shouldn't be asked again at once.
                }
                lastAttemptMs[r.key] = System.currentTimeMillis()
            }
        }
    }

    /** Returns once the connection isn't metered, checking every [METERED_RECHECK_MS] (leaving Home cancels the wait). */
    private suspend fun awaitUnmetered() {
        while (!withContext(Dispatchers.IO) { NetworkMetering.isUnmetered() }) delay(METERED_RECHECK_MS)
    }

    /** Leaving Home stops a check; what it finished is kept. */
    fun stopReadyCheck() {
        checkJob?.cancel()
    }

    // --- Continue Watching artwork ---

    private val art = mutableStateMapOf<String, ContinueWatchingArt>()
    private val artRequested = mutableSetOf<String>()

    private fun artKey(entry: ProgressEntry) = "${entry.contentId}|${entry.videoId}"

    /** What [entry]'s card shows beyond its own art, once looked up; see [requestArt]. */
    fun artFor(entry: ProgressEntry): ContinueWatchingArt? = art[artKey(entry)]

    /**
     * Looks up the episode still (from the addon's episode list, else TMDB's) when "Use Episode
     * Thumbnails" is on, and TMDB's artwork, logo and title when TMDB's "Enrich Continue Watching" is.
     */
    fun requestArt(entry: ProgressEntry) {
        val s = settings()
        val wantStill = s.layout.episodeThumbnailsInContinueWatching && entry.season != null && entry.episode != null
        val wantTmdb = s.tmdb.enabled && s.tmdb.enrichContinueWatching && enricher.available
        if (!wantStill && !wantTmdb) return
        val key = artKey(entry) + "|$wantStill|$wantTmdb|${s.tmdb.language}"
        if (!artRequested.add(key)) return
        scope.launch {
            var result = ContinueWatchingArt()
            if (wantTmdb) {
                enricher.enrichContinueWatching(entry.contentId, entry.contentType)?.let { t ->
                    result = result.copy(name = t.name, backdrop = t.backdrop, logo = t.logo)
                }
            }
            if (wantStill) {
                val video = runCatching {
                    withContext(Dispatchers.IO) { client.meta(addons(), entry.contentType, entry.contentId) }
                }.getOrNull()?.videos?.firstOrNull { it.id == entry.videoId || (it.season == entry.season && it.episode == entry.episode) }
                val still = video?.thumbnail ?: enricher.episodeStill(entry.contentId, entry.season!!, entry.episode!!)
                result = result.copy(still = still)
            }
            art[artKey(entry)] = result
        }
    }

    // --- TMDB on Home's hero (the TV's "Enable on Modern Home") ---

    private val enriched = mutableStateMapOf<String, MetaPreview>()
    private val enrichRequested = mutableSetOf<String>()

    /** [preview] with TMDB's artwork and details once they're in, when TMDB's "Enable on Modern Home" is on. */
    fun enrichedForHome(preview: MetaPreview): MetaPreview {
        val s = settings()
        if (!s.tmdb.enabled || !s.tmdb.modernHomeEnabled || !enricher.available) return preview
        val key = "${preview.apiType}|${preview.id}|${s.tmdb.language}"
        enriched[key]?.let { return it }
        if (enrichRequested.add(key)) {
            scope.launch { enricher.enrich(preview, s.tmdb)?.let { enriched[key] = it } }
        }
        return preview
    }

    // --- Shuffle ---

    /** The shared shuffle sessions (the TV's EpisodeShuffle): picks stay put until played or reshuffled. */
    val shuffle = EpisodeShuffle()

    private val shuffleStore = shuffleFile(dir)

    /** Shuffle per show, as the TV's `episode_shuffle` store: on or off, and whether watched episodes count. */
    var shuffleShows: Map<String, EpisodeShuffleSettings> by mutableStateOf(shuffleStore.load().shows.mapValues { it.value.toSettings() })
        private set

    /** [contentId]'s shuffle, off unless Layout's Shuffle is on. */
    fun shuffleFor(contentId: String): EpisodeShuffleSettings {
        val saved = shuffleShows[contentId] ?: EpisodeShuffleSettings()
        return saved.copy(enabled = saved.enabled && settings().layout.randomEpisodeEnabled)
    }

    fun saveShuffle(contentId: String, value: EpisodeShuffleSettings) {
        if (contentId.isBlank()) return
        shuffleShows = shuffleShows + (contentId to value)
        runCatching { shuffleStore.save(ShuffleFile(shuffleShows.mapValues { ShuffleEntry(it.value.enabled, it.value.includeWatched) })) }
    }

    /** The videos of [contentId], for shuffling from Home. */
    suspend fun videos(type: String, contentId: String): List<Video> =
        runCatching { withContext(Dispatchers.IO) { client.meta(addons(), type, contentId) } }.getOrNull()?.videos.orEmpty()

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 60_000L
        const val METERED_RECHECK_MS = 60_000L
    }
}

@kotlinx.serialization.Serializable
data class ShuffleEntry(val enabled: Boolean = false, val includeWatched: Boolean = false) {
    fun toSettings() = EpisodeShuffleSettings(enabled, includeWatched)
}

@kotlinx.serialization.Serializable
data class ShuffleFile(val shows: Map<String, ShuffleEntry> = emptyMap())

fun shuffleFile(dir: java.io.File) = JsonFile(java.io.File(dir, "shuffle.json"), ShuffleFile.serializer()) { ShuffleFile() }
