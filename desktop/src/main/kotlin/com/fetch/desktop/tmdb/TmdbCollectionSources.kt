package com.fetch.desktop.tmdb

import com.fetch.desktop.collections.SourcePage
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import com.fetch.tv.domain.model.TmdbCollectionSort
import com.fetch.tv.domain.model.TmdbCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionSourceType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** A search hit in the collection editor's TMDB pickers. */
data class TmdbSearchHit(val id: Int, val name: String, val subtitle: String? = null, val image: String? = null)

/** The title and cover a TMDB source brings to a folder when it's added (the TV's import metadata). */
data class TmdbSourceMetadata(val title: String?, val coverImageUrl: String?)

/**
 * Collection folder sources from TMDB, resolved as the TV's `TmdbCollectionSourceResolver` does:
 * lists and discover (company, network, filters) page through TMDB; a movie collection and a
 * person's or director's credits come whole. Items carry `tmdb:<id>` ids, as on TV.
 */
class TmdbCollectionSources(private val client: TmdbClient, private val today: () -> LocalDate = LocalDate::now) {

    suspend fun resolve(source: TmdbCollectionSource, page: Int, language: String): SourcePage {
        if (!client.hasKey) error("Add a TMDB API key in Settings › TMDB to load TMDB sources.")
        val lang = TmdbClient.normalizeLanguage(language)
        return when (source.sourceType) {
            TmdbCollectionSourceType.LIST -> list(source, page, lang)
            TmdbCollectionSourceType.COLLECTION -> collection(source, lang)
            TmdbCollectionSourceType.PERSON, TmdbCollectionSourceType.DIRECTOR -> credits(source, lang)
            TmdbCollectionSourceType.COMPANY, TmdbCollectionSourceType.NETWORK, TmdbCollectionSourceType.DISCOVER -> discover(source, page, lang)
        }
    }

    private suspend fun list(source: TmdbCollectionSource, page: Int, lang: String): SourcePage {
        val id = source.tmdbId ?: error("This TMDB list has no id.")
        val body = get(listOf("list", id.toString()), "language" to lang, "page" to page.toString())?.decode<CsListDto>()
            ?: error("TMDB list not found.")
        val items = body.items.mapNotNull { it.toPreview(if (it.mediaType == "tv") TmdbCollectionMediaType.TV else TmdbCollectionMediaType.MOVIE) }
            .sortedFor(source.sortBy)
            .distinctBy { "${it.apiType}:${it.id}" }
        val current = body.page ?: page
        return SourcePage(items, hasMore = current < (body.totalPages ?: current))
    }

    private suspend fun collection(source: TmdbCollectionSource, lang: String): SourcePage {
        val id = source.tmdbId ?: error("This TMDB collection has no id.")
        val body = get(listOf("collection", id.toString()), "language" to lang)?.decode<CsCollectionDto>()
            ?: error("TMDB collection not found.")
        val items = body.parts.mapNotNull { it.toPreview(TmdbCollectionMediaType.MOVIE) }.sortedFor(source.sortBy)
        return SourcePage(items, hasMore = false)
    }

    private suspend fun credits(source: TmdbCollectionSource, lang: String): SourcePage {
        val id = source.tmdbId ?: error("This TMDB person has no id.")
        val body = get(listOf("person", id.toString(), "combined_credits"), "language" to lang)?.decode<CsCreditsDto>()
            ?: error("TMDB credits not found.")
        val wanted = if (source.mediaType == TmdbCollectionMediaType.TV) "tv" else "movie"
        val credits = if (source.sourceType == TmdbCollectionSourceType.DIRECTOR) {
            body.crew.filter { it.job.equals("Director", ignoreCase = true) }
        } else body.cast
        val items = credits.filter { it.mediaType == wanted }
            .mapNotNull { it.toPreview(source.mediaType) }
            .distinctBy { "${it.apiType}:${it.id}" }
            .sortedFor(source.sortBy)
        return SourcePage(items, hasMore = false)
    }

    private suspend fun discover(source: TmdbCollectionSource, page: Int, lang: String): SourcePage {
        val mediaType = if (source.sourceType == TmdbCollectionSourceType.NETWORK) TmdbCollectionMediaType.TV else source.mediaType
        val query = discoverQuery(source, mediaType, page, lang, today())
        val body = get(listOf("discover", mediaType.value), *query.toTypedArray())?.decode<CsResultsDto>()
            ?: error("TMDB discover returned no data.")
        val items = body.results.mapNotNull { it.toPreview(mediaType) }.distinctBy { it.id }
        val current = body.page ?: page
        return SourcePage(items, hasMore = current < (body.totalPages ?: current) && items.isNotEmpty())
    }

    // --- The editor's pickers ---

    suspend fun searchCompanies(query: String): List<TmdbSearchHit> = search("company", query) {
        it.name?.let { n -> TmdbSearchHit(it.id, n, it.originCountry, TmdbClient.image(it.logoPath, "w300")) }
    }

    suspend fun searchCollections(query: String, language: String): List<TmdbSearchHit> =
        search("collection", query, "language" to TmdbClient.normalizeLanguage(language)) {
            it.name?.let { n -> TmdbSearchHit(it.id, n, null, TmdbClient.image(it.posterPath, "w185")) }
        }

    suspend fun searchPeople(query: String, language: String): List<TmdbSearchHit> =
        search("person", query, "language" to TmdbClient.normalizeLanguage(language)) {
            it.name?.let { n -> TmdbSearchHit(it.id, n, it.knownForDepartment, TmdbClient.image(it.profilePath, "w185")) }
        }

    suspend fun searchKeywords(query: String): List<TmdbSearchHit> = search("keyword", query) {
        it.name?.let { n -> TmdbSearchHit(it.id, n) }
    }

    /** TMDB's genres for movies or shows, id to name. */
    suspend fun genres(mediaType: TmdbCollectionMediaType, language: String): Map<Int, String> =
        get(listOf("genre", mediaType.value, "list"), "language" to TmdbClient.normalizeLanguage(language))
            ?.decode<CsGenresDto>()?.genres.orEmpty().filter { it.name != null }.associate { it.id to it.name!! }

    /** The title and cover a source of [type] with [id] brings to its folder. */
    suspend fun metadata(type: TmdbCollectionSourceType, id: Int, language: String): TmdbSourceMetadata? {
        val lang = TmdbClient.normalizeLanguage(language)
        return when (type) {
            TmdbCollectionSourceType.LIST -> get(listOf("list", id.toString()), "language" to lang)?.decode<CsListDto>()
                ?.let { TmdbSourceMetadata(it.name?.takeIf { n -> n.isNotBlank() }, null) }
            TmdbCollectionSourceType.COLLECTION -> get(listOf("collection", id.toString()), "language" to lang)?.decode<CsCollectionDto>()
                ?.let { TmdbSourceMetadata(it.name, TmdbClient.image(it.posterPath, "w500") ?: TmdbClient.image(it.backdropPath, "w1280")) }
            TmdbCollectionSourceType.COMPANY -> get(listOf("company", id.toString()))?.decode<CsNamedDto>()
                ?.let { TmdbSourceMetadata(it.name, TmdbClient.image(it.logoPath, "w500")) }
            TmdbCollectionSourceType.NETWORK -> get(listOf("network", id.toString()))?.decode<CsNamedDto>()
                ?.let { TmdbSourceMetadata(it.name, TmdbClient.image(it.logoPath, "w500")) }
            TmdbCollectionSourceType.PERSON, TmdbCollectionSourceType.DIRECTOR -> get(listOf("person", id.toString()), "language" to lang)?.decode<CsNamedDto>()
                ?.let { TmdbSourceMetadata(it.name, TmdbClient.image(it.profilePath, "w500")) }
            TmdbCollectionSourceType.DISCOVER -> null
        }
    }

    private suspend fun search(kind: String, query: String, vararg extra: Pair<String, String>, map: (CsNamedDto) -> TmdbSearchHit?): List<TmdbSearchHit> {
        if (query.isBlank()) return emptyList()
        return get(listOf("search", kind), "query" to query.trim(), *extra)?.decode<CsSearchDto>()?.results.orEmpty().mapNotNull(map)
    }

    private suspend fun get(segments: List<String>, vararg query: Pair<String, String>): String? = client.rawGet(segments, query.toList())

    private inline fun <reified T> String.decode(): T = json.decodeFromString(this)

    private fun CsItem.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        val name = (title ?: name ?: originalTitle ?: originalName)?.takeIf { it.isNotBlank() } ?: return null
        if (id <= 0) return null
        val series = mediaType == TmdbCollectionMediaType.TV
        val date = (if (series) firstAirDate ?: releaseDate else releaseDate ?: firstAirDate)?.takeIf { it.isNotBlank() }
        val poster = TmdbClient.image(posterPath, "w500") ?: TmdbClient.image(backdropPath, "w780")
        return MetaPreview(
            id = "tmdb:$id",
            type = if (series) ContentType.SERIES else ContentType.MOVIE,
            rawType = if (series) "series" else "movie",
            name = name,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = TmdbClient.image(backdropPath, "w1280"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4),
            imdbRating = voteAverage?.toFloat(),
            genres = emptyList(),
            released = date,
            voteCount = voteCount,
            landscapePoster = TmdbClient.image(backdropPath, "w780"),
            rawPosterUrl = poster
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

        /** `123`, or a TMDB address with `/list/123`, `/collection/123-name`, `?id=123`… (the TV's parser). */
        fun parseTmdbId(input: String): Int? {
            val trimmed = input.trim()
            trimmed.toIntOrNull()?.let { return it }
            return Regex("""(?:list|collection|company|network|person)/(\d+)""").find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""[?&]id=(\d+)""").find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        /** The discover query the TV sends for [source], parameter for parameter. */
        fun discoverQuery(
            source: TmdbCollectionSource,
            mediaType: TmdbCollectionMediaType,
            page: Int,
            language: String,
            today: LocalDate
        ): List<Pair<String, String>> {
            val f = source.filters
            val isNetwork = source.sourceType == TmdbCollectionSourceType.NETWORK
            val company = if (source.sourceType == TmdbCollectionSourceType.COMPANY) source.tmdbId?.toString() else f.withCompanies
            val providers = !f.withWatchProviders.isNullOrBlank() || !f.withoutWatchProviders.isNullOrBlank()
            val q = mutableListOf<Pair<String, String?>>(
                "language" to language,
                "page" to page.toString()
            )
            if (mediaType == TmdbCollectionMediaType.MOVIE) {
                q += "sort_by" to movieSort(source.sortBy)
                q += "with_companies" to company
                q += "primary_release_date.lte" to f.releaseDateLte
                q += "primary_release_date.gte" to f.releaseDateGte
                q += "year" to f.year?.toString()
            } else {
                q += "sort_by" to tvSort(source.sortBy)
                q += "with_companies" to company
                q += "with_networks" to (if (isNetwork) source.tmdbId?.toString() else f.withNetworks)
                q += "first_air_date.lte" to (f.releaseDateLte ?: if (isNetwork) today.toString() else null)
                q += "first_air_date.gte" to f.releaseDateGte
                q += "first_air_date_year" to f.year?.toString()
                q += "with_status" to (if (isNetwork) "0|3|4" else null)
            }
            q += "vote_count.gte" to f.voteCountGte?.toString()
            q += "with_genres" to f.withGenres
            q += "vote_average.gte" to f.voteAverageGte?.toString()
            q += "vote_average.lte" to f.voteAverageLte?.toString()
            q += "with_original_language" to f.withOriginalLanguage
            q += "with_origin_country" to f.withOriginCountry
            q += "with_keywords" to f.withKeywords
            q += "without_keywords" to f.withoutKeywords
            q += "watch_region" to (if (providers) f.watchRegion?.takeIf { it.isNotBlank() } ?: "US" else null)
            q += "with_watch_providers" to f.withWatchProviders
            q += "with_watch_monetization_types" to (if (!f.withWatchProviders.isNullOrBlank()) "flatrate|free|ads|rent|buy" else null)
            q += "without_companies" to f.withoutCompanies
            q += "without_genres" to f.withoutGenres
            q += "without_watch_providers" to f.withoutWatchProviders
            return q.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }
        }

        private fun movieSort(sortBy: String): String = when (sortBy) {
            "first_air_date.desc" -> "primary_release_date.desc"
            else -> sortBy.ifBlank { "popularity.desc" }.let { if (it == TmdbCollectionSort.ORIGINAL.value) "popularity.desc" else it }
        }

        private fun tvSort(sortBy: String): String = when (sortBy) {
            "primary_release_date.desc" -> "first_air_date.desc"
            else -> sortBy.ifBlank { "popularity.desc" }.let { if (it == TmdbCollectionSort.ORIGINAL.value) "popularity.desc" else it }
        }

        /** The TV's in-memory sorts for lists, collections and credits, which TMDB doesn't sort. */
        fun List<MetaPreview>.sortedFor(sortBy: String): List<MetaPreview> = when (sortBy) {
            TmdbCollectionSort.VOTE_AVERAGE_DESC.value -> sortedWith(
                compareByDescending<MetaPreview> { it.imdbRating ?: -1f }.thenByDescending { it.releaseInfo ?: "" }
            )
            TmdbCollectionSort.VOTE_COUNT_DESC.value -> sortedWith(
                compareByDescending<MetaPreview> { it.voteCount ?: -1 }.thenByDescending { it.imdbRating ?: -1f }
            )
            TmdbCollectionSort.RELEASE_DATE_DESC.value, TmdbCollectionSort.FIRST_AIR_DATE_DESC.value -> sortedByDescending { it.releaseInfo ?: "" }
            else -> this
        }
    }
}

@Serializable
private data class CsItem(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val job: String? = null
)

@Serializable
private data class CsListDto(
    val name: String? = null,
    val items: List<CsItem> = emptyList(),
    val page: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null
)

@Serializable
private data class CsCollectionDto(
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val parts: List<CsItem> = emptyList()
)

@Serializable
private data class CsCreditsDto(val cast: List<CsItem> = emptyList(), val crew: List<CsItem> = emptyList())

@Serializable
private data class CsResultsDto(
    val results: List<CsItem> = emptyList(),
    val page: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null
)

@Serializable
private data class CsNamedDto(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null
)

@Serializable
private data class CsSearchDto(val results: List<CsNamedDto> = emptyList())

@Serializable
private data class CsGenreDto(val id: Int = 0, val name: String? = null)

@Serializable
private data class CsGenresDto(val genres: List<CsGenreDto> = emptyList())
