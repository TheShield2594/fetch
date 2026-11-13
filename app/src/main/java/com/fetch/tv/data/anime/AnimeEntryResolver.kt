package com.fetch.tv.data.anime

import com.fetch.tv.core.tracking.TrackingMediaKind
import com.fetch.tv.core.tracking.TrackingMediaReference
import com.fetch.tv.data.anilist.AniListApi
import com.fetch.tv.data.repository.SimklIdResolver
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** An anime entry (one season, cour, film or OVA) by whichever ids are known, and the episode of it. */
data class AnimeTarget(val aniListId: Long?, val malId: Long?, val episode: Int)

/**
 * Finds the AniList and MyAnimeList entry for what is playing. Both keep each season as its own
 * entry with its own episode numbers, so:
 *
 * - anime catalog ids (`anilist:`, `mal:`, `kitsu:`) already name that entry, and their video
 *   ids (`kitsu:1376:7`) carry the entry's own episode number;
 * - an IMDb series episode goes through Simkl's anime mapping, which picks the entry that owns
 *   the season and renumbers the episode (the same mapping skip markers use);
 * - anything Simkl doesn't know as anime isn't anime, and is left alone.
 *
 * A missing AniList or MyAnimeList id is filled in from the other through AniList's public API.
 */
@Singleton
class AnimeEntryResolver @Inject constructor(
    private val aniList: AniListApi,
    private val simkl: SimklIdResolver
) {
    private val aniListByMal = ConcurrentHashMap<Long, Long>()
    private val malByAniList = ConcurrentHashMap<Long, Long>()

    /** The entry with its AniList id, for AniList. */
    suspend fun forAniList(media: TrackingMediaReference, token: String?): AnimeTarget? {
        val target = resolve(media) ?: return null
        if (target.aniListId != null) return target
        val malId = target.malId ?: return null
        val id = aniListByMal[malId] ?: aniList.mediaIdForMal(token, malId)?.also { aniListByMal[malId] = it }
        return id?.let { target.copy(aniListId = it) }
    }

    /** The entry with its MyAnimeList id, for MyAnimeList. */
    suspend fun forMal(media: TrackingMediaReference): AnimeTarget? {
        val target = resolve(media) ?: return null
        if (target.malId != null) return target
        val aniListId = target.aniListId ?: return null
        val id = malByAniList[aniListId] ?: aniList.malIdForMedia(aniListId)?.also { malByAniList[aniListId] = it }
        return id?.let { target.copy(malId = it) }
    }

    private suspend fun resolve(media: TrackingMediaReference): AnimeTarget? {
        val flat = flatAnimeEpisode(media)
        val isMovie = media.kind == TrackingMediaKind.MOVIE ||
            media.catalog?.contentType?.trim()?.lowercase() in MOVIE_TYPES
        // An anime catalog numbers episodes within the entry, whatever season it reports.
        val episode = flat?.episode ?: media.episode?.number ?: if (isMovie) 1 else null

        val aniListId = flat?.takeIf { it.source == "anilist" }?.id ?: media.ids.anilist
        val malId = flat?.takeIf { it.source == "mal" }?.id ?: media.ids.mal
        val kitsu = flat?.takeIf { it.source == "kitsu" }?.id ?: media.ids.kitsu
        if (episode != null && (aniListId != null || malId != null || kitsu != null)) {
            if (aniListId != null || malId != null) return AnimeTarget(aniListId, malId, episode)
            val ids = simkl.resolveIds("kitsu", kitsu.toString()) ?: return null
            return target(ids, episode)
        }

        val imdb = media.ids.imdb?.takeIf(String::isNotBlank) ?: return null
        return if (isMovie) {
            val ids = simkl.resolveIds("imdb", imdb, "movie")?.takeIf { it.type == "anime" } ?: return null
            target(ids, 1)
        } else {
            val season = media.episode?.season ?: return null
            val number = media.episode?.number ?: return null
            val ids = simkl.resolveIdsForImdbEpisode(imdb, season, number)?.takeIf { it.type == "anime" } ?: return null
            val animeEpisode = simkl.getEpisodeMapping(ids.simklId, ids.type)
                .firstOrNull { it.tvdbSeason == season && it.tvdbEpisode == number }
                ?.animeEpisode
                ?: number
            target(ids, animeEpisode)
        }
    }

    private fun target(ids: SimklIdResolver.ResolvedIds, episode: Int): AnimeTarget? {
        val aniListId = ids.anilist?.toLongOrNull()
        val malId = ids.mal?.toLongOrNull()
        if (aniListId == null && malId == null) return null
        return AnimeTarget(aniListId, malId, episode)
    }

    private companion object {
        val MOVIE_TYPES = setOf("movie", "film")
    }
}

internal data class FlatAnimeEpisode(val source: String, val id: Long, val episode: Int)

/**
 * The entry and episode an anime catalog's video id names: `kitsu:1376:7` is episode 7 of
 * kitsu:1376. Only anime-database prefixes count; IMDb video ids carry season and episode.
 */
internal fun flatAnimeEpisode(media: TrackingMediaReference): FlatAnimeEpisode? {
    val videoId = media.catalog?.videoId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val parts = videoId.split(':')
    if (parts.size != 3) return null
    val source = parts[0].lowercase()
    if (source !in ANIME_SOURCES) return null
    val id = parts[1].toLongOrNull() ?: return null
    val episode = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return FlatAnimeEpisode(source, id, episode)
}

private val ANIME_SOURCES = setOf("anilist", "mal", "kitsu")
