package com.fetch.desktop.collections

import com.fetch.tv.domain.model.AddonCatalogCollectionSource
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.CollectionSource
import com.fetch.tv.domain.model.FetchListCollectionSource
import com.fetch.tv.domain.model.FolderViewMode
import com.fetch.tv.domain.model.LocalListCollectionSource
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.TmdbCollectionFilters
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import com.fetch.tv.domain.model.TmdbCollectionSort
import com.fetch.tv.domain.model.TmdbCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionSourceType
import com.fetch.tv.domain.model.TraktCollectionSource
import com.fetch.tv.domain.model.TraktListSort
import com.fetch.tv.domain.model.TraktSortHow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Collections as JSON, in the TV app's format (`CollectionsDataStore`): the same field names,
 * `provider` tags (`addon`, `tmdb`, `trakt`, `fetch`, `local`) and the legacy `catalogSources`
 * list beside `sources`, so the file, the Fetch website's collections and the TV's
 * import/export all read the same. Nulls are left out, as Gson does.
 */
object CollectionsCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    private val listSerializer = ListSerializer(SerializableCollection.serializer())

    fun encode(collections: List<Collection>): String =
        json.encodeToString(listSerializer, collections.map { it.toSerializable() })

    fun encodeElement(collections: List<Collection>): JsonElement =
        json.encodeToJsonElement(listSerializer, collections.map { it.toSerializable() })

    /** Reads [text]; anything unreadable is an empty list, and duplicate ids keep one (as on TV). */
    fun parse(text: String?): List<Collection> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            fromSerializable(json.decodeFromString(listSerializer, text))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parse(element: JsonElement?): List<Collection> {
        if (element !is JsonArray) return emptyList()
        return try {
            fromSerializable(json.decodeFromJsonElement(listSerializer, element))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Checks pasted JSON the way the TV's import does; null when it's fine, else why not.
     */
    fun validate(text: String): String? {
        if (text.isBlank()) return "Paste the collections JSON first."
        val element = runCatching { json.parseToJsonElement(text) }.getOrElse { return "That isn't JSON (${it.message})." }
        if (element !is JsonArray) return "Expected a JSON array of collections."
        if (element.isEmpty()) return "That list has no collections."
        val parsed = parse(element)
        if (parsed.isEmpty()) return "No collection in it could be read: each needs an id, a title and folders."
        return null
    }

    private fun fromSerializable(list: List<SerializableCollection>): List<Collection> =
        list.filter { it.id.isNotBlank() }.map { it.toDomain() }.associateBy { it.id }.values.toList()

    // --- Wire format ---

    @Serializable
    internal data class SerializableCollection(
        val id: String,
        val title: String = "",
        val backdropImageUrl: String? = null,
        val pinToTop: Boolean = false,
        val viewMode: String = "TABBED_GRID",
        val showAllTab: Boolean = true,
        val folders: List<SerializableFolder> = emptyList()
    )

    @Serializable
    internal data class SerializableFolder(
        val id: String,
        val title: String = "",
        val coverImageUrl: String? = null,
        val focusGifUrl: String? = null,
        val focusGifEnabled: Boolean? = null,
        val coverEmoji: String? = null,
        val tileShape: String = "SQUARE",
        val hideTitle: Boolean = false,
        val sources: List<SerializableSource>? = null,
        val catalogSources: List<SerializableCatalogSource> = emptyList(),
        val heroBackdropUrl: String? = null,
        val heroVideoUrl: String? = null,
        val titleLogoUrl: String? = null
    )

    @Serializable
    internal data class SerializableSource(
        val provider: String = "addon",
        val addonId: String? = null,
        val addonUrl: String? = null,
        val type: String? = null,
        val catalogId: String? = null,
        val genre: String? = null,
        val tmdbSourceType: String? = null,
        val title: String? = null,
        val tmdbId: Int? = null,
        val traktListId: Long? = null,
        val listId: String? = null,
        val mediaType: String? = null,
        val sortBy: String? = null,
        val sortHow: String? = null,
        val filters: SerializableTmdbFilters? = null
    )

    @Serializable
    internal data class SerializableTmdbFilters(
        val withGenres: String? = null,
        val withoutGenres: String? = null,
        val releaseDateGte: String? = null,
        val releaseDateLte: String? = null,
        val voteAverageGte: Double? = null,
        val voteAverageLte: Double? = null,
        val voteCountGte: Int? = null,
        val withOriginalLanguage: String? = null,
        val withOriginCountry: String? = null,
        val withKeywords: String? = null,
        val withoutKeywords: String? = null,
        val withCompanies: String? = null,
        val withoutCompanies: String? = null,
        val withNetworks: String? = null,
        val year: Int? = null,
        val watchRegion: String? = null,
        val withWatchProviders: String? = null,
        val withoutWatchProviders: String? = null
    )

    @Serializable
    internal data class SerializableCatalogSource(
        val addonId: String,
        val type: String,
        val catalogId: String,
        val genre: String? = null
    )

    // --- Domain → wire ---

    private fun Collection.toSerializable() = SerializableCollection(
        id = id,
        title = title,
        backdropImageUrl = backdropImageUrl,
        pinToTop = pinToTop,
        viewMode = viewMode.name,
        showAllTab = showAllTab,
        folders = folders.map { folder ->
            SerializableFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                focusGifUrl = folder.focusGifUrl,
                focusGifEnabled = folder.focusGifEnabled,
                coverEmoji = folder.coverEmoji,
                tileShape = folder.tileShape.name,
                hideTitle = folder.hideTitle,
                heroBackdropUrl = folder.heroBackdropUrl,
                heroVideoUrl = folder.heroVideoUrl,
                titleLogoUrl = folder.titleLogoUrl,
                sources = folder.sources.map { it.toSerializable() },
                catalogSources = folder.catalogSources.map { SerializableCatalogSource(it.addonId, it.type, it.catalogId, it.genre) }
            )
        }
    )

    private fun CollectionSource.toSerializable(): SerializableSource = when (this) {
        is AddonCatalogCollectionSource -> SerializableSource(
            provider = "addon", addonId = addonId, addonUrl = addonUrl, type = type, catalogId = catalogId, genre = genre
        )
        is TmdbCollectionSource -> SerializableSource(
            provider = "tmdb", tmdbSourceType = sourceType.name, title = title, tmdbId = tmdbId,
            mediaType = mediaType.name, sortBy = sortBy, filters = filters.toSerializable()
        )
        is TraktCollectionSource -> SerializableSource(
            provider = "trakt", title = title, traktListId = traktListId, mediaType = mediaType.name, sortBy = sortBy, sortHow = sortHow
        )
        is FetchListCollectionSource -> SerializableSource(provider = "fetch", title = title, listId = listId, mediaType = mediaType?.name)
        is LocalListCollectionSource -> SerializableSource(provider = "local", title = title, listId = listId, mediaType = mediaType?.name)
    }

    private fun TmdbCollectionFilters.toSerializable() = SerializableTmdbFilters(
        withGenres, withoutGenres, releaseDateGte, releaseDateLte, voteAverageGte, voteAverageLte, voteCountGte,
        withOriginalLanguage, withOriginCountry, withKeywords, withoutKeywords, withCompanies, withoutCompanies,
        withNetworks, year, watchRegion, withWatchProviders, withoutWatchProviders
    )

    // --- Wire → domain (the TV's rules) ---

    private fun SerializableCollection.toDomain() = Collection(
        id = id,
        title = title,
        backdropImageUrl = backdropImageUrl,
        pinToTop = pinToTop,
        viewMode = FolderViewMode.fromString(viewMode),
        showAllTab = showAllTab,
        folders = folders.map { folder ->
            CollectionFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                focusGifUrl = folder.focusGifUrl,
                focusGifEnabled = folder.focusGifEnabled ?: true,
                coverEmoji = folder.coverEmoji,
                tileShape = tileShape(folder.tileShape),
                hideTitle = folder.hideTitle,
                heroBackdropUrl = folder.heroBackdropUrl,
                heroVideoUrl = folder.heroVideoUrl,
                titleLogoUrl = folder.titleLogoUrl,
                sources = folder.sources?.mapNotNull { it.toDomain() }
                    ?: folder.catalogSources.map { AddonCatalogCollectionSource(it.addonId, it.type, it.catalogId, it.genre) }
            )
        }
    )

    /** The TV's names plus the website's (`wide`, lower case). */
    private fun tileShape(value: String?): PosterShape = when (value?.lowercase()) {
        "wide" -> PosterShape.LANDSCAPE
        else -> PosterShape.fromString(value)
    }

    private fun mediaType(raw: String?): TmdbCollectionMediaType? =
        raw?.let { runCatching { TmdbCollectionMediaType.valueOf(it.uppercase()) }.getOrNull() }

    private fun SerializableSource.toDomain(): CollectionSource? = when (provider.lowercase()) {
        "tmdb" -> {
            val type = tmdbSourceType?.let { runCatching { TmdbCollectionSourceType.valueOf(it.uppercase()) }.getOrNull() }
            if (type == null) null else {
                val sourceSortBy = sortBy?.takeIf { it.isNotBlank() } ?: TmdbCollectionSort.POPULAR_DESC.value
                // Lists and collections keep their own order unless a sort was picked.
                val normalized = if (
                    type in setOf(TmdbCollectionSourceType.LIST, TmdbCollectionSourceType.COLLECTION) &&
                    sourceSortBy == TmdbCollectionSort.POPULAR_DESC.value
                ) TmdbCollectionSort.ORIGINAL.value else sourceSortBy
                TmdbCollectionSource(
                    sourceType = type,
                    title = title?.takeIf { it.isNotBlank() } ?: type.name.lowercase().replaceFirstChar { it.uppercase() },
                    tmdbId = tmdbId,
                    mediaType = mediaType(mediaType) ?: TmdbCollectionMediaType.MOVIE,
                    sortBy = normalized,
                    filters = filters?.toDomain() ?: TmdbCollectionFilters()
                )
            }
        }
        "trakt" -> traktListId?.takeIf { it > 0L }?.let { id ->
            TraktCollectionSource(
                title = title?.takeIf { it.isNotBlank() } ?: "Trakt List $id",
                traktListId = id,
                mediaType = mediaType(mediaType) ?: TmdbCollectionMediaType.MOVIE,
                sortBy = TraktListSort.normalize(sortBy),
                sortHow = TraktSortHow.normalize(sortHow)
            )
        }
        "fetch" -> listId?.takeIf { it.isNotBlank() }?.let { id ->
            FetchListCollectionSource(id, title?.takeIf { it.isNotBlank() } ?: "Website list", mediaType(mediaType))
        }
        "local" -> listId?.takeIf { it.isNotBlank() }?.let { id ->
            LocalListCollectionSource(id, title?.takeIf { it.isNotBlank() } ?: "TV list", mediaType(mediaType))
        }
        else -> {
            val a = addonId?.takeIf { it.isNotBlank() }
            val t = type?.takeIf { it.isNotBlank() }
            val c = catalogId?.takeIf { it.isNotBlank() }
            if (a == null || t == null || c == null) null else AddonCatalogCollectionSource(
                addonId = a,
                type = t,
                catalogId = c,
                genre = genre,
                // Only a web address: it is fetched from, and it can arrive by sync.
                addonUrl = addonUrl?.trim()?.trimEnd('/')?.takeIf {
                    it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
                }
            )
        }
    }

    private fun SerializableTmdbFilters.toDomain() = TmdbCollectionFilters(
        withGenres, withoutGenres, releaseDateGte, releaseDateLte, voteAverageGte, voteAverageLte, voteCountGte,
        withOriginalLanguage, withOriginCountry, withKeywords, withoutKeywords, withCompanies, withoutCompanies,
        withNetworks, year, watchRegion, withWatchProviders, withoutWatchProviders
    )
}
