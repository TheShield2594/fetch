package com.fetch.tv.data.simkl

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Episode air times from Simkl's public TV calendar, for a profile signed in to Simkl: TMDB, which
 * the TV's own calendar is built from, gives only dates.
 *
 * The calendar is one global file (yesterday and the next 33 days, every show Simkl tracks, about
 * 2.4 MB), regenerated every six hours; it's read at most that often. Times are keyed like the
 * calendar's entries: `<imdb id>:season:episode` and `tmdb:<id>:season:episode`.
 */
@Singleton
class SimklCalendarAirTimes internal constructor(
    private val client: OkHttpClient,
    private val configuration: SimklApiConfiguration,
    private val isSignedIn: () -> Boolean,
    private val nowMs: () -> Long
) {
    @Inject
    constructor(
        @Named("simkl") client: OkHttpClient,
        configuration: SimklApiConfiguration,
        auth: SimklAuthRepository
    ) : this(client, configuration, { auth.state.value.isAuthenticated }, System::currentTimeMillis)

    private val mutex = Mutex()
    private var cached: Map<String, String>? = null
    private var cachedAtMs = 0L

    /** Air times by episode key; empty when the profile isn't on Simkl or the file can't be read. */
    suspend fun airTimes(): Map<String, String> {
        if (!isSignedIn() || configuration.clientId.isBlank()) return emptyMap()
        return mutex.withLock {
            cached?.takeIf { nowMs() - cachedAtMs in 0 until CACHE_TTL_MS }?.let { return@withLock it }
            val fresh = withContext(Dispatchers.IO) { download() } ?: return@withLock cached.orEmpty()
            cached = fresh
            cachedAtMs = nowMs()
            fresh
        }
    }

    private fun download(): Map<String, String>? {
        // The CDN ignores query strings for caching, but Simkl asks every request to name the app.
        val url = buildSimklApiUrl(configuration.copy(baseUrl = CALENDAR_BASE_URL), CALENDAR_PATH)
        val request = Request.Builder().url(url).apply {
            simklRequestHeaders(configuration).forEach { (name, value) -> header(name, value) }
        }.build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                simklCalendarAirTimes(json.decodeFromString(SimklCalendarFile.serializer(), body))
            }
        }.getOrNull()
    }

    private companion object {
        const val CALENDAR_BASE_URL = "https://data.simkl.in"
        const val CALENDAR_PATH = "/calendar/v2/tv.json"
        const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class SimklCalendarFile(
    val calendar: List<SimklCalendarItem> = emptyList(),
    /** Each show's record, keyed by its Simkl id as text. */
    val metadata: Map<String, SimklCalendarShow> = emptyMap()
)

@Serializable
internal data class SimklCalendarItem(
    @SerialName("simkl_id") val simklId: Long? = null,
    /** ISO 8601 in UTC. */
    val date: String? = null,
    val episode: SimklCalendarEpisode? = null
)

@Serializable
internal data class SimklCalendarEpisode(val season: Int? = null, val episode: Int? = null)

@Serializable
internal data class SimklCalendarShow(val ids: SimklCalendarIds? = null)

@Serializable
internal data class SimklCalendarIds(val imdb: String? = null, val tmdb: String? = null)

private val SimklPlaceholderZone: ZoneId = ZoneId.of("America/New_York")

/**
 * Air times by episode key from [file]. A time of exactly midnight US Eastern is left out: it's by
 * far the most common time in the file, the date with no time known, and a "12:00 AM" would be
 * wrong far more often than right.
 */
internal fun simklCalendarAirTimes(file: SimklCalendarFile): Map<String, String> = buildMap {
    file.calendar.forEach { item ->
        val date = item.date?.takeIf { it.isNotBlank() } ?: return@forEach
        val season = item.episode?.season ?: return@forEach
        val number = item.episode.episode ?: return@forEach
        val instant = try {
            OffsetDateTime.parse(date)
        } catch (_: DateTimeParseException) {
            return@forEach
        }
        val eastern = instant.atZoneSameInstant(SimklPlaceholderZone)
        if (eastern.hour == 0 && eastern.minute == 0) return@forEach
        val ids = file.metadata[item.simklId?.toString()]?.ids ?: return@forEach
        ids.imdb?.takeIf { it.isNotBlank() }?.let { put("$it:$season:$number", date) }
        ids.tmdb?.takeIf { it.isNotBlank() }?.let { put("tmdb:$it:$season:$number", date) }
    }
}
