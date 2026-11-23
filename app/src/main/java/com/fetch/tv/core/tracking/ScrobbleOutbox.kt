package com.fetch.tv.core.tracking

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.fetch.tv.core.util.runSuspendCatching
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * "Watched" marks a tracker didn't take, kept on disk and sent again later (#619).
 *
 * A stop scrobble that records a play is sent once while the player closes. If the tracker was
 * unreachable, overloaded or the app died before it answered, the play was lost for good: the
 * TV showed the episode as watched and the tracker didn't. Failed marks now wait here and are
 * replayed when a later scrobble gets through and when the app starts.
 *
 * A replay goes through the provider's history writer where it has one (Trakt, Simkl, MDBList,
 * the Fetch website), which keeps the time the title was watched; otherwise (AniList,
 * MyAnimeList) the stop is sent again, which those providers apply as "at least this episode".
 */
@Singleton
class ScrobbleOutbox internal constructor(
    private val prefsProvider: () -> SharedPreferences,
    private val providers: TrackingProviderRegistry,
    private val historyWriters: TrackingHistoryWriterRegistry,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        providers: TrackingProviderRegistry,
        historyWriters: TrackingHistoryWriterRegistry
    ) : this({ context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }, providers, historyWriters)

    private val prefs by lazy(prefsProvider)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val drainMutex = Mutex()

    data class Entry(
        val provider: TrackingProviderId,
        val event: TrackingScrobbleEvent,
        val watchedAtMs: Long,
        val attempts: Int = 0
    )

    /** Keeps [event]'s play for [provider] until a replay gets it through. */
    fun enqueue(provider: TrackingProviderId, event: TrackingScrobbleEvent) {
        val entry = Entry(provider, event, watchedAtMs = clock())
        update { entries ->
            // One mark per title and provider: a second failure for the same play replaces the first.
            (entries.filterNot { it.provider == provider && it.event.sameTitleAs(event) } + entry).takeLast(MAX_ENTRIES)
        }
        Log.i(TAG, "Kept a ${provider.storageId} watched mark to send again: ${event.media.stableKey}")
    }

    fun entries(): List<Entry> = synchronized(lock) { read() }

    /** Starts a replay in the background, unless one is running or nothing waits. */
    fun requestDrain() {
        scope.launch { if (entries().isNotEmpty()) drain() }
    }

    /** Sends every waiting mark once; what fails again waits for the next drain, up to a limit. */
    suspend fun drain() {
        if (!drainMutex.tryLock()) return
        try {
            val now = clock()
            entries().forEach { entry ->
                val sent = when {
                    now - entry.watchedAtMs > MAX_AGE_MS || entry.attempts >= MAX_ATTEMPTS -> {
                        Log.w(TAG, "Gave up on a ${entry.provider.storageId} watched mark after ${entry.attempts} attempts")
                        true
                    }
                    // Signed out meanwhile: the mark has no account to go to.
                    !providers.isAuthenticated(entry.provider) -> true
                    else -> runSuspendCatching { replay(entry) }
                        .onFailure { Log.w(TAG, "Replaying a ${entry.provider.storageId} watched mark failed", it) }
                        .isSuccess
                }
                update { entries ->
                    if (sent) {
                        entries.filterNot { it.matches(entry) }
                    } else {
                        entries.map { if (it.matches(entry)) it.copy(attempts = it.attempts + 1) else it }
                    }
                }
            }
        } finally {
            drainMutex.unlock()
        }
    }

    private suspend fun replay(entry: Entry) {
        val writer = historyWriters.writer(entry.provider)
        val profileId = entry.event.profileId
        if (writer != null && profileId != null) {
            writer.addToHistory(profileId, listOf(TrackingHistoryItem(entry.event.media, watchedAtEpochMs = entry.watchedAtMs)))
            return
        }
        val scrobbler = providers.provider(entry.provider)?.scrobbler ?: return
        scrobbler.scrobble(TrackingScrobbleAction.STOP, entry.event)
    }

    private fun Entry.matches(other: Entry) =
        provider == other.provider && watchedAtMs == other.watchedAtMs && event.sameTitleAs(other.event)

    private fun TrackingScrobbleEvent.sameTitleAs(other: TrackingScrobbleEvent) =
        profileId == other.profileId && media.stableKey == other.media.stableKey && media.episode == other.media.episode

    private fun update(transform: (List<Entry>) -> List<Entry>) {
        synchronized(lock) {
            prefs.edit().putString(KEY_ENTRIES, encode(transform(read())).toString()).commit()
        }
    }

    private fun read(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { (Json.parseToJsonElement(raw) as JsonArray).mapNotNull { decodeEntry(it.jsonObject) } }
            .onFailure { Log.w(TAG, "Unreadable scrobble outbox, starting empty", it) }
            .getOrDefault(emptyList())
    }

    internal companion object {
        private const val TAG = "ScrobbleOutbox"
        private const val PREFS_NAME = "scrobble_outbox"
        private const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 200
        const val MAX_ATTEMPTS = 20
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        fun encode(entries: List<Entry>): JsonArray = buildJsonArray {
            entries.forEach { entry ->
                add(buildJsonObject {
                    put("provider", entry.provider.storageId)
                    put("watchedAt", entry.watchedAtMs)
                    put("attempts", entry.attempts)
                    put("progress", entry.event.progressPercent)
                    entry.event.profileId?.let { put("profileId", it) }
                    put("media", encodeMedia(entry.event.media))
                })
            }
        }

        fun decodeEntry(json: JsonObject): Entry? {
            val provider = TrackingProviderId.fromStorage(json.string("provider")) ?: return null
            val media = (json["media"] as? JsonObject)?.let(::decodeMedia) ?: return null
            return Entry(
                provider = provider,
                event = TrackingScrobbleEvent(
                    media = media,
                    progressPercent = json["progress"]?.jsonPrimitive?.doubleOrNull ?: 100.0,
                    profileId = json["profileId"]?.jsonPrimitive?.intOrNull
                ),
                watchedAtMs = json["watchedAt"]?.jsonPrimitive?.longOrNull ?: return null,
                attempts = json["attempts"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }

        private fun encodeMedia(media: TrackingMediaReference) = buildJsonObject {
            put("kind", media.kind.name)
            media.title?.let { put("title", it) }
            media.year?.let { put("year", it) }
            media.posterUrl?.let { put("poster", it) }
            put("ids", buildJsonObject {
                with(media.ids) {
                    imdb?.let { put("imdb", it) }
                    tmdb?.let { put("tmdb", it) }
                    tvdb?.let { put("tvdb", it) }
                    trakt?.let { put("trakt", it) }
                    simkl?.let { put("simkl", it) }
                    mal?.let { put("mal", it) }
                    anidb?.let { put("anidb", it) }
                    anilist?.let { put("anilist", it) }
                    kitsu?.let { put("kitsu", it) }
                    mdblist?.let { put("mdblist", it) }
                }
            })
            media.episode?.let { episode ->
                put("episode", buildJsonObject {
                    episode.season?.let { put("season", it) }
                    put("number", episode.number)
                    episode.title?.let { put("title", it) }
                    episode.tvdbId?.let { put("tvdbId", it) }
                    put("usesTvdbSeasonMapping", episode.usesTvdbSeasonMapping)
                    episode.tmdbId?.let { put("tmdbId", it) }
                })
            }
            media.catalog?.let { catalog ->
                put("catalog", buildJsonObject {
                    put("contentId", catalog.contentId)
                    put("contentType", catalog.contentType)
                    catalog.videoId?.let { put("videoId", it) }
                })
            }
        }

        private fun decodeMedia(json: JsonObject): TrackingMediaReference? {
            val kind = runCatching { TrackingMediaKind.valueOf(json.string("kind").orEmpty()) }.getOrNull() ?: return null
            val ids = (json["ids"] as? JsonObject) ?: JsonObject(emptyMap())
            val episode = (json["episode"] as? JsonObject)?.let { e ->
                TrackingEpisode(
                    season = e["season"]?.jsonPrimitive?.intOrNull,
                    number = e["number"]?.jsonPrimitive?.intOrNull ?: return null,
                    title = e.string("title"),
                    tvdbId = e["tvdbId"]?.jsonPrimitive?.longOrNull,
                    usesTvdbSeasonMapping = e["usesTvdbSeasonMapping"]?.jsonPrimitive?.booleanOrNull ?: false,
                    tmdbId = e["tmdbId"]?.jsonPrimitive?.longOrNull
                )
            }
            val catalog = (json["catalog"] as? JsonObject)?.let { c ->
                TrackingCatalogReference(
                    contentId = c.string("contentId") ?: return null,
                    contentType = c.string("contentType") ?: return null,
                    videoId = c.string("videoId")
                )
            }
            return TrackingMediaReference(
                kind = kind,
                title = json.string("title"),
                year = json["year"]?.jsonPrimitive?.intOrNull,
                ids = TrackingExternalIds(
                    imdb = ids.string("imdb"),
                    tmdb = ids["tmdb"]?.jsonPrimitive?.longOrNull,
                    tvdb = ids.string("tvdb"),
                    trakt = ids["trakt"]?.jsonPrimitive?.longOrNull,
                    simkl = ids["simkl"]?.jsonPrimitive?.longOrNull,
                    mal = ids["mal"]?.jsonPrimitive?.longOrNull,
                    anidb = ids["anidb"]?.jsonPrimitive?.longOrNull,
                    anilist = ids["anilist"]?.jsonPrimitive?.longOrNull,
                    kitsu = ids["kitsu"]?.jsonPrimitive?.longOrNull,
                    mdblist = ids.string("mdblist")
                ),
                episode = episode,
                catalog = catalog,
                posterUrl = json.string("poster")
            )
        }

        private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    }
}
