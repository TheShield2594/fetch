package com.fetch.desktop.tmdb

import com.fetch.desktop.data.TmdbSettings
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException

/**
 * TMDB over Home's cards, as the TV's `core/tmdb/MetaPreviewEnricher.kt`: artwork, the title and
 * description in your language, and details, each when its TMDB setting is on. Used for the hero
 * ("Enable on Modern Home") and Continue Watching ("Enrich Continue Watching").
 */
class MetaPreviewEnricher(private val client: TmdbClient, private val language: () -> String) {
    val available: Boolean get() = client.isConfigured

    /** What a Continue Watching card takes from TMDB. */
    data class CardArt(val name: String?, val backdrop: String?, val logo: String?)

    private suspend fun title(id: String, isSeries: Boolean): TmdbTitle? = try {
        val tmdbId = client.tmdbId(id, isSeries) ?: return null
        client.title(tmdbId, isSeries, language())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** [item] with TMDB's parts applied, or null when TMDB doesn't know it. */
    suspend fun enrich(item: MetaPreview, settings: TmdbSettings): MetaPreview? {
        val isSeries = item.type == ContentType.SERIES || item.apiType == "series" || item.apiType == "tv"
        val t = title(item.imdbId ?: item.id, isSeries) ?: return null
        return apply(item, t, settings)
    }

    /** A Continue Watching title's artwork, logo and localized name. */
    suspend fun enrichContinueWatching(contentId: String, contentType: String): CardArt? {
        val isSeries = contentType != "movie"
        val t = title(contentId, isSeries) ?: return null
        return CardArt(t.title, t.backdrop, t.logo)
    }

    /** TMDB's still for an episode. */
    suspend fun episodeStill(contentId: String, season: Int, episode: Int): String? = try {
        val tmdbId = client.tmdbId(contentId, true) ?: null
        tmdbId?.let { client.season(it, season, language()).firstOrNull { e -> e.episode == episode }?.still }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** The TV's merge: artwork, basic info and details, each only where TMDB has a value. */
        fun apply(item: MetaPreview, t: TmdbTitle, settings: TmdbSettings): MetaPreview {
            var out = item
            if (settings.useArtwork) out = out.copy(background = t.backdrop ?: out.background, logo = t.logo ?: out.logo, poster = t.poster ?: out.poster)
            if (settings.useBasicInfo) {
                out = out.copy(name = t.title ?: out.name, description = t.overview ?: out.description, genres = t.genres.ifEmpty { out.genres })
            }
            if (settings.useDetails) {
                out = out.copy(
                    runtime = t.runtimeMinutes?.let { "$it min" } ?: out.runtime,
                    status = t.status ?: out.status,
                    country = t.countries.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: out.country,
                    language = t.language ?: out.language,
                    released = out.released ?: t.releaseDate
                )
            }
            return out
        }
    }
}
