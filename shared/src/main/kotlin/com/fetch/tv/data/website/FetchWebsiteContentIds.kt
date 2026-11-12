package com.fetch.tv.data.website

import com.fetch.tv.core.tracking.TrackingExternalIds

// The ids the Fetch website keys titles by, as the website's lib/content-id.ts defines them: an
// IMDb id (`tt0111161`) where the title has one, otherwise the prefixed id these apps already use
// (`tmdb:603`, `kitsu:1376`, `mal:1`, …). One title has one key, so an IMDb id always wins when
// it's known, and an episode is always sent under its series' id.

private val IMDB_ID = Regex("^tt\\d+$", RegexOption.IGNORE_CASE)
private val PREFIXED_ID = Regex("^([a-z]+):(\\d{1,12})$")

/** The sources a prefixed website id can name, besides IMDb. */
val FETCH_WEBSITE_ID_SOURCES: Set<String> = setOf("tmdb", "tvdb", "trakt", "simkl", "mal", "anilist", "anidb", "kitsu")

/**
 * The website's id for the title [raw] names, or null when the website can't key it. [raw] is a
 * content id or Stremio id, so an episode's video id (`tt0903747:2:5`, `kitsu:1376:5`) gives its
 * title's id. `TMDB:0603` becomes `tmdb:603`, and `imdb:tt1` becomes `tt1`.
 */
fun fetchWebsiteContentId(raw: String?): String? {
    var value = raw?.trim().orEmpty()
    if (value.startsWith("imdb:", ignoreCase = true)) value = value.substring("imdb:".length)
    if (value.isEmpty()) return null
    if (value.startsWith("tt", ignoreCase = true)) {
        val head = value.substringBefore(':')
        return if (IMDB_ID.matches(head)) "tt" + head.substring(2) else null
    }
    val parts = value.lowercase().split(':')
    if (parts.size < 2) return null
    val match = PREFIXED_ID.matchEntire("${parts[0]}:${parts[1]}") ?: return null
    val source = match.groupValues[1].takeIf { it in FETCH_WEBSITE_ID_SOURCES } ?: return null
    return "$source:${match.groupValues[2].toLong()}"
}

/** Whether [id] is already a website id, spelled as the website spells it. */
fun isFetchWebsiteContentId(id: String): Boolean = fetchWebsiteContentId(id) == id

fun isFetchWebsiteImdbId(id: String): Boolean = IMDB_ID.matches(id)

/**
 * The website's id for a title known by these ids: IMDb's when there is one, then TMDB's (the
 * website can still fetch its metadata), then an anime catalog's, then the rest.
 */
fun TrackingExternalIds.fetchWebsiteContentId(): String? =
    imdb?.let(::fetchWebsiteContentId)?.takeIf(::isFetchWebsiteImdbId)
        ?: tmdb?.takeIf { it > 0 }?.let { "tmdb:$it" }
        ?: kitsu?.takeIf { it > 0 }?.let { "kitsu:$it" }
        ?: mal?.takeIf { it > 0 }?.let { "mal:$it" }
        ?: anilist?.takeIf { it > 0 }?.let { "anilist:$it" }
        ?: anidb?.takeIf { it > 0 }?.let { "anidb:$it" }
        ?: trakt?.takeIf { it > 0 }?.let { "trakt:$it" }
        ?: simkl?.takeIf { it > 0 }?.let { "simkl:$it" }
        ?: tvdb?.let { fetchWebsiteContentId("tvdb:$it") }
