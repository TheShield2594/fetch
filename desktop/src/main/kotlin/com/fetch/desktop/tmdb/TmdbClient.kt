package com.fetch.desktop.tmdb

import com.fetch.desktop.ratings.TmdbAuth
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaCastMember
import com.fetch.tv.domain.model.MetaCompany
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A title's details from TMDB, as the TV app's `TmdbEnrichment` carries them. */
data class TmdbTitle(
    val tmdbId: Int,
    val isSeries: Boolean,
    val title: String?,
    val originalTitle: String?,
    val overview: String?,
    val genres: List<String>,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    /** "2008" for a movie, "2008–2013" or "2019–" for a show. */
    val releaseInfo: String?,
    val releaseDate: String?,
    val status: String?,
    val runtimeMinutes: Int?,
    val rating: Float?,
    val countries: List<String>,
    val language: String?,
    val cast: List<MetaCastMember>,
    val directors: List<MetaCastMember>,
    val writers: List<MetaCastMember>,
    val companies: List<MetaCompany>,
    val networks: List<MetaCompany>,
    val collectionId: Int?,
    val collectionName: String?,
    /** YouTube ids, trailers first. */
    val trailers: List<String>,
    val imdbId: String?,
    val seasons: List<Int>
)

/** One episode as TMDB lists it in a season. */
data class TmdbEpisode(
    val season: Int,
    val episode: Int,
    val title: String?,
    val overview: String?,
    val still: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
    val rating: Float?
)

data class TmdbCollection(val id: Int, val name: String, val overview: String?, val backdrop: String?, val parts: List<MetaPreview>)

data class TmdbPerson(
    val id: Int,
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val birthplace: String?,
    val photo: String?,
    val knownFor: String?,
    /** Movies and shows they're in (or made), most popular first. */
    val credits: List<MetaPreview>
)

/** A production company or TV network, for its own browse page. */
enum class TmdbEntityKind(val label: String) { COMPANY("Studio"), NETWORK("Network") }

data class TmdbEntity(val kind: TmdbEntityKind, val id: Int, val name: String, val logo: String?, val country: String?)

data class TmdbPage(val items: List<MetaPreview>, val page: Int, val totalPages: Int)

/**
 * The TV app's TMDB metadata (`TmdbMetadataService`) for desktop: details, credits, artwork,
 * episodes, recommendations, collections, people and company/network browsing.
 *
 * Titles found only on TMDB get the id `tmdb:<id>`; recommendations and collections are turned
 * into IMDb ids where TMDB knows them, so they open from your addons and match the library.
 * Everything is cached for the session.
 */
class TmdbClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val baseUrl: HttpUrl = "https://api.themoviedb.org/3/".toHttpUrl()
) {
    val isConfigured: Boolean get() = !apiKey().isNullOrBlank()

    private val tmdbIds = ConcurrentHashMap<String, Int>()
    private val imdbIds = ConcurrentHashMap<String, String>()
    private val titles = ConcurrentHashMap<String, TmdbTitle>()
    private val seasonCache = ConcurrentHashMap<String, List<TmdbEpisode>>()
    private val recommendationCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val collectionCache = ConcurrentHashMap<String, TmdbCollection>()
    private val personCache = ConcurrentHashMap<String, TmdbPerson>()

    /** TMDB's id for an IMDb id (`tt…`, or `tt…:s:e`) or a `tmdb:<id>` id; null when unknown. */
    suspend fun tmdbId(id: String, isSeries: Boolean): Int? {
        parseTmdbId(id)?.let { return it }
        val imdb = id.substringBefore(':').takeIf { it.startsWith("tt") } ?: return null
        val key = "$imdb|$isSeries"
        tmdbIds[key]?.let { return it }
        val find = get(listOf("find", imdb), "external_source" to "imdb_id")?.decode<FindDto>() ?: return null
        val found = (if (isSeries) find.tvResults else find.movieResults).firstOrNull()?.id
            ?: (if (isSeries) find.movieResults else find.tvResults).firstOrNull()?.id
            ?: return null
        tmdbIds[key] = found
        imdbIds["$found|$isSeries"] = imdb
        return found
    }

    /** The IMDb id TMDB lists for a title; null when it has none. */
    suspend fun imdbId(tmdbId: Int, isSeries: Boolean): String? {
        val key = "$tmdbId|$isSeries"
        imdbIds[key]?.let { return it }
        val ids = get(listOf(type(isSeries), tmdbId.toString(), "external_ids"))?.decode<ExternalIdsDto>() ?: return null
        val imdb = ids.imdbId?.takeIf { it.startsWith("tt") } ?: return null
        imdbIds[key] = imdb
        tmdbIds["$imdb|$isSeries"] = tmdbId
        return imdb
    }

    /** Details, credits, artwork and trailers in one request (`append_to_response`). */
    suspend fun title(tmdbId: Int, isSeries: Boolean, language: String): TmdbTitle? {
        val lang = normalizeLanguage(language)
        val key = "$tmdbId|$isSeries|$lang"
        titles[key]?.let { return it }
        val appended = if (isSeries) "aggregate_credits,images,videos,external_ids" else "credits,images,videos,external_ids"
        val dto = get(
            listOf(type(isSeries), tmdbId.toString()),
            "language" to lang,
            "append_to_response" to appended,
            "include_image_language" to imageLanguages(lang),
            "include_video_language" to "${lang.substringBefore('-')},en,null"
        )?.decode<DetailsDto>() ?: return null
        val title = dto.toTitle(isSeries, lang)
        titles[key] = title
        title.imdbId?.let { imdbIds["$tmdbId|$isSeries"] = it }
        return title
    }

    /** A season's episodes; empty when TMDB doesn't have it. */
    suspend fun season(tmdbId: Int, season: Int, language: String): List<TmdbEpisode> {
        val lang = normalizeLanguage(language)
        val key = "$tmdbId|$season|$lang"
        seasonCache[key]?.let { return it }
        val dto = get(listOf("tv", tmdbId.toString(), "season", season.toString()), "language" to lang)?.decode<SeasonDto>()
        val episodes = dto?.episodes.orEmpty().mapNotNull { e ->
            val number = e.episodeNumber ?: return@mapNotNull null
            TmdbEpisode(
                season = e.seasonNumber ?: season,
                episode = number,
                title = e.name?.takeIf { it.isNotBlank() },
                overview = e.overview?.takeIf { it.isNotBlank() },
                still = image(e.stillPath, "w780"),
                airDate = e.airDate?.takeIf { it.isNotBlank() },
                runtimeMinutes = e.runtime?.takeIf { it > 0 },
                rating = e.voteAverage?.takeIf { (e.voteCount ?: 0) > 0 && it > 0 }?.toFloat()
            )
        }
        seasonCache[key] = episodes
        return episodes
    }

    /** Several seasons at once, keyed by (season, episode). */
    suspend fun episodes(tmdbId: Int, seasons: Collection<Int>, language: String): Map<Pair<Int, Int>, TmdbEpisode> = coroutineScope {
        seasons.distinct().map { s -> async { runCatching { season(tmdbId, s, language) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten().associateBy { it.season to it.episode }
    }

    /**
     * "More like this": TMDB's recommendations, liked titles in your language and well-rated ones
     * first, as on TV. Landscape cards, as the TV row shows them.
     */
    suspend fun recommendations(tmdbId: Int, isSeries: Boolean, language: String, max: Int = 12): List<MetaPreview> {
        val lang = normalizeLanguage(language)
        val key = "$tmdbId|$isSeries|$lang|$max"
        recommendationCache[key]?.let { return it }
        val results = get(listOf(type(isSeries), tmdbId.toString(), "recommendations"), "language" to lang)
            ?.decode<ResultsDto>()?.results.orEmpty().filter { it.id > 0 }
        val code = lang.substringBefore('-')
        val sorted = results.sortedWith(
            compareByDescending<ResultDto> { it.originalLanguage.equals(code, ignoreCase = true) }
                .thenByDescending { it.voteCount ?: 0 }
                .thenByDescending { it.voteAverage ?: 0.0 }
        )
        val good = sorted.filter { it.originalLanguage.equals(code, ignoreCase = true) || (it.voteCount ?: 0) >= 20 || (it.voteAverage ?: 0.0) >= 6.0 }
        val picked = (good.ifEmpty { sorted }).take(max)
        val previews = withImdbIds(picked.mapNotNull { it.toPreview(defaultSeries = isSeries, shape = PosterShape.LANDSCAPE) })
        recommendationCache[key] = previews
        return previews
    }

    /** A movie collection ("The Lord of the Rings Collection"), its films in release order. */
    suspend fun collection(id: Int, language: String): TmdbCollection? {
        val lang = normalizeLanguage(language)
        val key = "$id|$lang"
        collectionCache[key]?.let { return it }
        val dto = get(listOf("collection", id.toString()), "language" to lang)?.decode<CollectionDto>() ?: return null
        val parts = dto.parts.filter { it.id > 0 }.sortedBy { it.releaseDate?.takeIf { d -> d.isNotBlank() } ?: "9999" }
            .mapNotNull { it.toPreview(defaultSeries = false, shape = PosterShape.POSTER) }
        val collection = TmdbCollection(id, dto.name ?: return null, dto.overview?.takeIf { it.isNotBlank() }, image(dto.backdropPath, "w1280"), withImdbIds(parts))
        collectionCache[key] = collection
        return collection
    }

    /** A person's page: biography and what they're known for. */
    suspend fun person(id: Int, language: String): TmdbPerson? {
        val lang = normalizeLanguage(language)
        val key = "$id|$lang"
        personCache[key]?.let { return it }
        val dto = get(listOf("person", id.toString()), "language" to lang, "append_to_response" to "combined_credits")
            ?.decode<PersonDto>() ?: return null
        // Actors by what they acted in; directors and writers by what they made.
        val crewFirst = dto.knownForDepartment != null && dto.knownForDepartment != "Acting"
        val cast = dto.combinedCredits?.cast.orEmpty()
        val crew = dto.combinedCredits?.crew.orEmpty().filter { it.department == dto.knownForDepartment }
        val credits = (if (crewFirst) crew + cast else cast + crew)
            .filter { it.id > 0 && (it.mediaType == "movie" || it.mediaType == "tv") }
            // Talk shows and award ceremonies list nearly everyone; they crowd out the real work.
            .filterNot { it.mediaType == "tv" && it.genreIds.any { g -> g in TALK_GENRES } }
            .distinctBy { "${it.mediaType}:${it.id}" }
            .sortedByDescending { it.popularity ?: 0.0 }
            .mapNotNull { it.toPreview(defaultSeries = it.mediaType == "tv", shape = PosterShape.POSTER) }
        val person = TmdbPerson(
            id = dto.id,
            name = dto.name ?: return null,
            biography = dto.biography?.takeIf { it.isNotBlank() },
            birthday = dto.birthday?.takeIf { it.isNotBlank() },
            deathday = dto.deathday?.takeIf { it.isNotBlank() },
            birthplace = dto.placeOfBirth?.takeIf { it.isNotBlank() },
            photo = image(dto.profilePath, "h632"),
            knownFor = dto.knownForDepartment,
            credits = credits
        )
        personCache[key] = person
        return person
    }

    /** A studio's or network's name and logo. */
    suspend fun entity(kind: TmdbEntityKind, id: Int): TmdbEntity? {
        val path = if (kind == TmdbEntityKind.COMPANY) "company" else "network"
        val dto = get(listOf(path, id.toString()))?.decode<EntityDto>() ?: return null
        return TmdbEntity(kind, id, dto.name ?: return null, image(dto.logoPath, "w300"), dto.originCountry?.takeIf { it.isNotBlank() })
    }

    /**
     * A page of a studio's or network's titles, most popular first (TMDB discover). Networks
     * only have shows; companies have both, picked with [isSeries].
     */
    suspend fun discover(kind: TmdbEntityKind, id: Int, isSeries: Boolean, page: Int, language: String): TmdbPage {
        val series = isSeries || kind == TmdbEntityKind.NETWORK
        val filter = if (kind == TmdbEntityKind.NETWORK) "with_networks" else "with_companies"
        val dto = get(
            listOf("discover", type(series)),
            filter to id.toString(),
            "sort_by" to "popularity.desc",
            "page" to page.toString(),
            "language" to normalizeLanguage(language)
        )?.decode<ResultsDto>() ?: return TmdbPage(emptyList(), page, page)
        return TmdbPage(
            items = dto.results.filter { it.id > 0 }.mapNotNull { it.toPreview(defaultSeries = series, shape = PosterShape.POSTER) },
            page = dto.page ?: page,
            totalPages = dto.totalPages ?: page
        )
    }

    /** Swaps `tmdb:` ids for IMDb ids where TMDB knows them, in parallel. */
    private suspend fun withImdbIds(previews: List<MetaPreview>): List<MetaPreview> = coroutineScope {
        previews.map { p ->
            async {
                val tmdb = parseTmdbId(p.id) ?: return@async p
                val imdb = runCatching { imdbId(tmdb, p.type == ContentType.SERIES) }.getOrNull()
                if (imdb == null) p else p.copy(id = imdb, imdbId = imdb)
            }
        }.awaitAll()
    }

    // --- HTTP ---

    /** Whether a key is set (from Settings › TMDB or the build). */
    val hasKey: Boolean get() = !apiKey()?.trim().isNullOrEmpty()

    /** Any TMDB GET with the key, for [TmdbCollectionSources]; null on 404 or with no key. */
    internal suspend fun rawGet(segments: List<String>, query: List<Pair<String, String>>): String? = get(segments, *query.toTypedArray())

    private suspend fun get(segments: List<String>, vararg query: Pair<String, String>): String? = withContext(Dispatchers.IO) {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val url = baseUrl.newBuilder().apply {
            segments.forEach(::addPathSegment)
            query.forEach { (name, value) -> addQueryParameter(name, value) }
            if (!TmdbAuth.isBearerToken(key)) addQueryParameter("api_key", key)
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (TmdbAuth.isBearerToken(key)) header("Authorization", "Bearer $key")
        }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("TMDB ${segments.first()} returned HTTP ${response.code}")
            response.body?.string()
        }
    }

    private inline fun <reified T> String.decode(): T = json.decodeFromString(this)

    // --- Mapping ---

    private fun DetailsDto.toTitle(isSeries: Boolean, lang: String): TmdbTitle {
        val crew = if (isSeries) aggregateCredits?.crew.orEmpty().map { it.flatten() } else credits?.crew.orEmpty()
        val castDtos = if (isSeries) aggregateCredits?.cast.orEmpty().map { it.flatten() } else credits?.cast.orEmpty()
        fun person(p: CreditDto, role: String?) = MetaCastMember(name = p.name.orEmpty(), character = role, photo = image(p.profilePath, "w185"), tmdbId = p.id)
        val directors = crew.filter { it.job == "Director" }.distinctBy { it.id }.map { person(it, "Director") }
        val writers = if (isSeries) {
            createdBy.map { MetaCastMember(it.name.orEmpty(), "Creator", image(it.profilePath, "w185"), it.id) }
        } else {
            crew.filter { it.department == "Writing" }.distinctBy { it.id }.take(4).map { person(it, it.job ?: "Writer") }
        }
        val start = (if (isSeries) firstAirDate else releaseDate)?.take(4)?.takeIf { it.length == 4 }
        val releaseInfo = if (isSeries && start != null) {
            val end = lastAirDate?.take(4)?.takeIf { it.length == 4 }
            when {
                status == "Ended" || status == "Canceled" -> if (end != null && end != start) "$start–$end" else start
                else -> "$start–"
            }
        } else start
        val runtime = runtime?.takeIf { it > 0 } ?: episodeRunTime.firstOrNull { it > 0 }
        val logos = images?.logos.orEmpty()
        val backdrops = images?.backdrops.orEmpty()
        val posters = images?.posters.orEmpty()
        return TmdbTitle(
            tmdbId = id,
            isSeries = isSeries,
            title = (title ?: name)?.takeIf { it.isNotBlank() },
            originalTitle = (originalTitle ?: originalName)?.takeIf { it.isNotBlank() },
            overview = overview?.takeIf { it.isNotBlank() },
            genres = genres.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) },
            poster = image(bestImage(posters, lang) ?: posterPath, "w500"),
            // Backdrops without text read best behind the title art, as on TV.
            backdrop = image(backdrops.firstOrNull { it.language == null }?.path ?: backdropPath ?: bestImage(backdrops, lang), "w1280"),
            logo = image(bestImage(logos, lang), "w500"),
            releaseInfo = releaseInfo,
            releaseDate = (if (isSeries) firstAirDate else releaseDate)?.takeIf { it.isNotBlank() },
            status = status?.takeIf { it.isNotBlank() },
            runtimeMinutes = runtime,
            rating = voteAverage?.takeIf { it > 0 && (voteCount ?: 0) > 0 }?.toFloat(),
            countries = (productionCountries.mapNotNull { it.code } + originCountry).distinct(),
            language = originalLanguage,
            cast = castDtos.sortedBy { it.order ?: Int.MAX_VALUE }.distinctBy { it.id }.take(30).map { person(it, it.character?.takeIf { c -> c.isNotBlank() }) },
            directors = directors,
            writers = writers,
            companies = productionCompanies.mapNotNull { c -> c.name?.let { MetaCompany(it, image(c.logoPath, "w300"), c.id) } },
            networks = networks.mapNotNull { c -> c.name?.let { MetaCompany(it, image(c.logoPath, "w300"), c.id) } },
            collectionId = belongsToCollection?.id,
            collectionName = belongsToCollection?.name,
            trailers = videos?.results.orEmpty()
                .filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
                .sortedWith(compareBy({ TRAILER_TYPES.indexOf(it.type).let { i -> if (i < 0) 99 else i } }, { if (it.official == true) 0 else 1 }))
                .mapNotNull { it.key }.distinct(),
            imdbId = (externalIds?.imdbId ?: imdbIdField)?.takeIf { it.startsWith("tt") },
            seasons = seasons.mapNotNull { it.seasonNumber }.sorted()
        )
    }

    private fun ResultDto.toPreview(defaultSeries: Boolean, shape: PosterShape): MetaPreview? {
        val series = when (mediaType) {
            "tv" -> true
            "movie" -> false
            else -> defaultSeries
        }
        val name = (title ?: name ?: originalTitle ?: originalName)?.takeIf { it.isNotBlank() } ?: return null
        val backdrop = image(backdropPath, "w780")
        val poster = image(posterPath, "w342")
        return MetaPreview(
            id = "tmdb:$id",
            type = if (series) ContentType.SERIES else ContentType.MOVIE,
            name = name,
            poster = poster,
            posterShape = shape,
            background = image(backdropPath, "w1280"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = (if (series) firstAirDate else releaseDate)?.take(4)?.takeIf { it.length == 4 },
            imdbRating = voteAverage?.takeIf { it > 0 && (voteCount ?: 0) > 0 }?.toFloat(),
            genres = emptyList(),
            released = (if (series) firstAirDate else releaseDate)?.takeIf { it.isNotBlank() },
            landscapePoster = backdrop,
            rawPosterUrl = poster
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private val TRAILER_TYPES = listOf("Trailer", "Teaser", "Clip", "Featurette")
        /** TMDB genre ids for talk shows and news. */
        private val TALK_GENRES = setOf(10767, 10763)

        fun type(isSeries: Boolean) = if (isSeries) "tv" else "movie"

        fun image(path: String?, size: String): String? = path?.takeIf { it.isNotBlank() }?.let { IMAGE_BASE + size + it }

        /** `tmdb:123` (also `tmdb:123:1:2` for an episode) → 123. */
        fun parseTmdbId(id: String): Int? = id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()

        /** The TV's normalization: `pt-br` → `pt-BR`, `es-419` (unknown to TMDB) → `es-MX`. */
        fun normalizeLanguage(language: String?): String {
            val raw = language?.trim()?.takeIf { it.isNotBlank() }?.replace('_', '-') ?: return "en"
            val parts = raw.split('-')
            val normalized = if (parts.size == 2) "${parts[0].lowercase()}-${parts[1].uppercase()}" else raw.lowercase()
            return if (normalized == "es-419") "es-MX" else normalized
        }

        private fun imageLanguages(lang: String) = "${lang.substringBefore('-')},en,null"

        /** The image in [lang], else English, else one without text, else the first. */
        private fun bestImage(images: List<ImageDto>, lang: String): String? {
            val code = lang.substringBefore('-')
            return (images.firstOrNull { it.language == code } ?: images.firstOrNull { it.language == "en" }
                ?: images.firstOrNull { it.language == null } ?: images.firstOrNull())?.path
        }
    }
}

// --- TMDB's JSON ---

@Serializable
private data class FindDto(
    @SerialName("movie_results") val movieResults: List<IdDto> = emptyList(),
    @SerialName("tv_results") val tvResults: List<IdDto> = emptyList()
)

@Serializable
private data class IdDto(val id: Int)

@Serializable
private data class ExternalIdsDto(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
private data class NamedDto(val id: Int? = null, val name: String? = null)

@Serializable
private data class CountryDto(@SerialName("iso_3166_1") val code: String? = null)

@Serializable
private data class CompanyDto(val id: Int? = null, val name: String? = null, @SerialName("logo_path") val logoPath: String? = null)

@Serializable
private data class CreatorDto(val id: Int? = null, val name: String? = null, @SerialName("profile_path") val profilePath: String? = null)

@Serializable
private data class CreditDto(
    val id: Int? = null,
    val name: String? = null,
    val character: String? = null,
    val job: String? = null,
    val department: String? = null,
    val order: Int? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
private data class AggregateRoleDto(val character: String? = null, val job: String? = null)

/** TV `aggregate_credits`: roles and jobs come as lists. */
@Serializable
private data class AggregateCreditDto(
    val id: Int? = null,
    val name: String? = null,
    val department: String? = null,
    val order: Int? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val roles: List<AggregateRoleDto> = emptyList(),
    val jobs: List<AggregateRoleDto> = emptyList()
) {
    fun flatten() = CreditDto(
        id = id,
        name = name,
        character = roles.firstOrNull()?.character,
        job = jobs.firstOrNull()?.job,
        department = department,
        order = order,
        profilePath = profilePath
    )
}

@Serializable
private data class CreditsDto(val cast: List<CreditDto> = emptyList(), val crew: List<CreditDto> = emptyList())

@Serializable
private data class AggregateCreditsDto(val cast: List<AggregateCreditDto> = emptyList(), val crew: List<AggregateCreditDto> = emptyList())

@Serializable
private data class ImageDto(@SerialName("file_path") val path: String? = null, @SerialName("iso_639_1") val language: String? = null)

@Serializable
private data class ImagesDto(
    val backdrops: List<ImageDto> = emptyList(),
    val logos: List<ImageDto> = emptyList(),
    val posters: List<ImageDto> = emptyList()
)

@Serializable
private data class VideoDto(val key: String? = null, val site: String? = null, val type: String? = null, val official: Boolean? = null)

@Serializable
private data class VideosDto(val results: List<VideoDto> = emptyList())

@Serializable
private data class SeasonRefDto(@SerialName("season_number") val seasonNumber: Int? = null)

@Serializable
private data class DetailsDto(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    val genres: List<NamedDto> = emptyList(),
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    val status: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("production_countries") val productionCountries: List<CountryDto> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("production_companies") val productionCompanies: List<CompanyDto> = emptyList(),
    val networks: List<CompanyDto> = emptyList(),
    @SerialName("created_by") val createdBy: List<CreatorDto> = emptyList(),
    @SerialName("belongs_to_collection") val belongsToCollection: NamedDto? = null,
    @SerialName("imdb_id") val imdbIdField: String? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsDto? = null,
    val credits: CreditsDto? = null,
    @SerialName("aggregate_credits") val aggregateCredits: AggregateCreditsDto? = null,
    val images: ImagesDto? = null,
    val videos: VideosDto? = null,
    val seasons: List<SeasonRefDto> = emptyList()
)

@Serializable
private data class EpisodeDto(
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null
)

@Serializable
private data class SeasonDto(val episodes: List<EpisodeDto> = emptyList())

@Serializable
private data class ResultDto(
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
    @SerialName("original_language") val originalLanguage: String? = null,
    val popularity: Double? = null,
    val department: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList()
)

@Serializable
private data class ResultsDto(
    val results: List<ResultDto> = emptyList(),
    val page: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null
)

@Serializable
private data class CollectionDto(
    val name: String? = null,
    val overview: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val parts: List<ResultDto> = emptyList()
)

@Serializable
private data class CombinedCreditsDto(val cast: List<ResultDto> = emptyList(), val crew: List<ResultDto> = emptyList())

@Serializable
private data class PersonDto(
    val id: Int,
    val name: String? = null,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("combined_credits") val combinedCredits: CombinedCreditsDto? = null
)

@Serializable
private data class EntityDto(
    val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null
)
