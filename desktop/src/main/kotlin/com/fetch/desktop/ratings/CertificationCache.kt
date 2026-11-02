package com.fetch.desktop.ratings

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * TMDB certifications saved between launches, keyed `"type:imdb"` (type `movie` or `series`).
 * An entry with no rating marks a title TMDB has no usable rating for, so it isn't asked again
 * every launch. Entries older than [ttlMs] read as missing and are dropped on save.
 * Thread-safe; [put] only updates memory, [save] writes the file atomically.
 */
class CertificationCache(
    private val file: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A cached lookup; [certification] null means TMDB has none ("none" marker). */
    data class Entry(val certification: Certification?, val savedAt: Long)

    @Serializable
    private data class Stored(val rating: String? = null, val country: String? = null, val at: Long = 0) {
        val none: Boolean get() = rating.isNullOrBlank()
    }

    private val lock = Any()
    private var entries: MutableMap<String, Stored>? = null
    private var dirty = false

    /** The cached entry for this title, or null when there is none or it has expired. */
    fun get(type: String, imdbId: String): Entry? = synchronized(lock) {
        val stored = loaded()[key(type, imdbId)] ?: return null
        if (expired(stored)) return null
        Entry(if (stored.none) null else Certification(stored.rating!!, stored.country.orEmpty()), stored.at)
    }

    /** Records a lookup result; null [certification] stores the "none" marker. */
    fun put(type: String, imdbId: String, certification: Certification?) = synchronized(lock) {
        loaded()[key(type, imdbId)] = Stored(certification?.rating, certification?.country, clock())
        dirty = true
    }

    /** Writes the cache if anything changed since the last save, dropping expired entries. */
    fun save() = synchronized(lock) {
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

    private fun expired(stored: Stored) = clock() - stored.at >= ttlMs || stored.at > clock() + ttlMs

    companion object {
        const val DEFAULT_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** `movie:tt…` or `series:tt…`, the episode suffix of an id dropped. */
        fun key(type: String, imdbId: String): String =
            (if (TmdbCertificationClient.isSeries(type)) "series" else "movie") + ":" +
                imdbId.trim().substringBefore(':')

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), Stored.serializer())
    }
}
