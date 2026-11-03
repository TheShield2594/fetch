package com.fetch.desktop.tmdb

import com.fetch.desktop.data.TmdbSettings
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.Video

/**
 * Merges TMDB's details into an addon's [Meta] the way the TV detail page does
 * (`MetaDetailsViewModel.enrichMeta`): each part only when its setting is on, and TMDB's
 * value only where it has one.
 */
object TmdbEnrich {

    fun apply(meta: Meta, title: TmdbTitle?, episodes: Map<Pair<Int, Int>, TmdbEpisode>, settings: TmdbSettings): Meta {
        if (!settings.enabled) return meta
        var m = meta
        if (title != null) {
            if (settings.useArtwork) {
                m = m.copy(background = title.backdrop ?: m.background, logo = title.logo ?: m.logo, poster = m.poster ?: title.poster)
            }
            if (settings.useBasicInfo) {
                m = m.copy(
                    name = title.title ?: m.name,
                    description = title.overview ?: m.description,
                    genres = title.genres.ifEmpty { m.genres }
                )
            }
            if (settings.useDetails) {
                m = m.copy(
                    runtime = title.runtimeMinutes?.let { "$it min" } ?: m.runtime,
                    status = title.status ?: m.status,
                    releaseInfo = m.releaseInfo ?: title.releaseInfo,
                    released = m.released ?: title.releaseDate,
                    country = title.countries.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: m.country,
                    language = title.language ?: m.language
                )
            }
            if (settings.useCredits) {
                val people = (title.directors + title.writers + title.cast)
                    .filter { it.name.isNotBlank() }
                    .distinctBy { it.tmdbId?.toString() ?: (it.name.lowercase() + "|" + it.character.orEmpty()) }
                if (people.isNotEmpty()) {
                    m = m.copy(castMembers = people, cast = title.cast.map { it.name }.ifEmpty { m.cast })
                }
                m = m.copy(
                    director = title.directors.map { it.name }.ifEmpty { m.director },
                    writer = title.writers.map { it.name }.ifEmpty { m.writer }
                )
            }
            if (settings.useProductions && title.companies.isNotEmpty()) m = m.copy(productionCompanies = title.companies)
            if (settings.useNetworks && title.networks.isNotEmpty()) m = m.copy(networks = title.networks)
            if (settings.useTrailers && title.trailers.isNotEmpty()) {
                m = m.copy(trailerYtIds = (m.trailerYtIds + title.trailers).distinct())
            }
            if (m.imdbId == null && title.imdbId != null) m = m.copy(imdbId = title.imdbId)
        }
        if (settings.useEpisodes && episodes.isNotEmpty()) {
            m = m.copy(videos = m.videos.map { v ->
                val ep = if (v.season != null && v.episode != null) episodes[v.season!! to v.episode!!] else null
                if (ep == null) v else v.copy(
                    title = ep.title ?: v.title,
                    overview = ep.overview ?: v.overview,
                    thumbnail = ep.still ?: v.thumbnail,
                    runtime = ep.runtimeMinutes ?: v.runtime,
                    released = v.released ?: ep.airDate,
                    rating = v.rating ?: ep.rating?.toDouble()
                )
            })
        }
        return m
    }

    /**
     * A title page for something only TMDB knows (no IMDb id): its details, and for a show its
     * episodes with ids `tmdb:<id>:<season>:<episode>`, which addons that take TMDB ids answer.
     */
    fun metaFor(title: TmdbTitle, episodes: Map<Pair<Int, Int>, TmdbEpisode>): Meta {
        val id = "tmdb:${title.tmdbId}"
        val videos = episodes.values.sortedWith(compareBy({ it.season }, { it.episode })).map { ep ->
            Video(
                id = "$id:${ep.season}:${ep.episode}",
                title = ep.title ?: "Episode ${ep.episode}",
                released = ep.airDate,
                thumbnail = ep.still,
                season = ep.season,
                episode = ep.episode,
                overview = ep.overview,
                runtime = ep.runtimeMinutes,
                rating = ep.rating?.toDouble()
            )
        }
        return Meta(
            id = id,
            type = if (title.isSeries) ContentType.SERIES else ContentType.MOVIE,
            name = title.title ?: title.originalTitle ?: id,
            poster = title.poster,
            posterShape = PosterShape.POSTER,
            background = title.backdrop,
            logo = title.logo,
            description = title.overview,
            releaseInfo = title.releaseInfo,
            status = title.status,
            imdbRating = null,
            genres = title.genres,
            runtime = title.runtimeMinutes?.let { "$it min" },
            director = title.directors.map { it.name },
            writer = title.writers.map { it.name },
            cast = title.cast.map { it.name },
            castMembers = (title.directors + title.writers + title.cast).distinctBy { it.tmdbId ?: it.name.hashCode() },
            videos = videos,
            productionCompanies = title.companies,
            networks = title.networks,
            country = title.countries.joinToString(", ").ifEmpty { null },
            awards = null,
            language = title.language,
            links = emptyList(),
            trailerYtIds = title.trailers,
            imdbId = title.imdbId,
            released = title.releaseDate
        )
    }
}
