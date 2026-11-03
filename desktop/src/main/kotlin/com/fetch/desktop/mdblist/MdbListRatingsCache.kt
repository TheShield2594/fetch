package com.fetch.desktop.mdblist

import com.fetch.tv.domain.model.MDBListRatings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * MDBList ratings saved between launches (`mdblist-ratings.json` in the config folder), as the
 * TV's `MdbListRatingsDiskCache`: keyed `imdb:{movie|show}:tt…`, kept [ttlMs] (12 hours, as on
 * TV), and only titles MDBList had ratings for are stored. Thread-safe; [put] updates memory and
 * [save] writes the file atomically.
 */
class MdbListRatingsCache(
    private val file: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Serializable
    internal data class Stored(
        val t: Long = 0,
        val trakt: Double? = null,
        val imdb: Double? = null,
        val tmdb: Double? = null,
        val letterboxd: Double? = null,
        val tomatoes: Double? = null,
        val audience: Double? = null,
        val metacritic: Double? = null,
        val mal: Double? = null,
        val tc: Boolean = false,
        val ac: Boolean = false
    ) {
        fun ratings() = MDBListRatings(trakt, imdb, tmdb, letterboxd, tomatoes, audience, metacritic, mal, tc, ac)
    }

    private val lock = Any()
    private var entries: MutableMap<String, Stored>? = null
    private var dirty = false

    fun get(key: String): MDBListRatings? = synchronized(lock) {
        val stored = loaded()[key] ?: return null
        if (expired(stored)) return null
        stored.ratings()
    }

    fun put(key: String, ratings: MDBListRatings): Unit = synchronized(lock) {
        if (ratings.isEmpty()) return
        with(ratings) {
            loaded()[key] = Stored(clock(), trakt, imdb, tmdb, letterboxd, tomatoes, audience, metacritic, mal, tomatoesCertified, audienceCertified)
        }
        dirty = true
    }

    /** Writes the cache if anything changed, dropping expired entries. */
    fun save(): Unit = synchronized(lock) {
        if (!dirty) return
        val live = loaded().filterValues { !expired(it) }
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(serializer, live))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        dirty = false
    }

    private fun loaded(): MutableMap<String, Stored> = entries ?: run {
        val read = runCatching {
            if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyMap()
        }.getOrDefault(emptyMap())
        read.toMutableMap().also { entries = it }
    }

    private fun expired(stored: Stored) = clock() - stored.t >= ttlMs || stored.t > clock() + ttlMs

    companion object {
        const val DEFAULT_TTL_MS = 12L * 60 * 60 * 1000

        fun key(mediaType: String, imdbId: String) = "imdb:${MdbListRatingsClient.normalizeMediaType(mediaType)}:$imdbId"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val serializer = MapSerializer(String.serializer(), Stored.serializer())
    }
}
