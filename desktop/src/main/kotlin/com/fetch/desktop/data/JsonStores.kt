package com.fetch.desktop.data

import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.SavedLibraryItem
import com.fetch.tv.domain.model.WatchProgress
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Reads and writes one JSON file in [AppDirs.config], atomically. A missing or broken file reads as [default]. */
class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): T {
        if (!file.exists()) return default()
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (_: Exception) {
            default()
        }
    }

    fun save(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

// --- Watch progress (Continue Watching) ---

@Serializable
data class ProgressEntry(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatched: Long,
    /** Set when the user removed the title from Continue Watching; cleared by watching again. */
    val hidden: Boolean = false,
    /** The next episode after one watched to the end, not started yet ("Next Up"). */
    val upNext: Boolean = false,
    /** 0–100 from Trakt or Simkl, which report a percentage, not a position. */
    val remotePercent: Float? = null,
    /** "trakt" or "simkl" when the entry came from that account. */
    val source: String? = null
) {
    fun toWatchProgress() = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = positionMs,
        duration = durationMs,
        lastWatched = lastWatched
    )
}

@Serializable
data class ProgressFile(
    /** The latest position per title (movie or show), one entry each. */
    val entries: List<ProgressEntry> = emptyList(),
    /** Videos watched to the end, so series know which episode comes next. */
    val watched: List<String> = emptyList()
)

fun progressFile(dir: File = AppDirs.config) =
    JsonFile(File(dir, "progress.json"), ProgressFile.serializer()) { ProgressFile() }

// --- Library ---

@Serializable
data class LibraryEntry(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterShape: String = "poster",
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val addonBaseUrl: String? = null,
    val addedAt: Long = 0L
) {
    fun toSaved() = SavedLibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        posterShape = PosterShape.fromString(posterShape),
        background = background,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl,
        logo = logo,
        addedAt = addedAt
    )

    fun toPreview(): MetaPreview = toSaved().toMetaPreview()

    companion object {
        fun from(preview: MetaPreview, addedAt: Long) = LibraryEntry(
            id = preview.id,
            type = preview.apiType,
            name = preview.name,
            poster = preview.poster,
            posterShape = preview.posterShape.name.lowercase(),
            background = preview.background,
            logo = preview.logo,
            description = preview.description,
            releaseInfo = preview.releaseInfo,
            imdbRating = preview.imdbRating,
            genres = preview.genres,
            addonBaseUrl = preview.sourceAddonBaseUrl,
            addedAt = addedAt
        )
    }
}

@Serializable
data class LibraryFile(val items: List<LibraryEntry> = emptyList())

fun libraryFile(dir: File = AppDirs.config) =
    JsonFile(File(dir, "library.json"), LibraryFile.serializer()) { LibraryFile() }

/** A [MetaPreview] good enough to open a title page from what Continue Watching saved. */
fun ProgressEntry.toPreview(): MetaPreview = MetaPreview(
    id = contentId,
    type = ContentType.fromString(contentType),
    rawType = contentType,
    name = name,
    poster = poster,
    posterShape = PosterShape.POSTER,
    background = backdrop,
    logo = logo,
    description = null,
    releaseInfo = null,
    imdbRating = null,
    genres = emptyList()
)
