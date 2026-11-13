package com.fetch.tv.data.anime

/**
 * What AniList and MyAnimeList have in common, so the scrobble and import rules are written once.
 * Statuses use AniList's names; MyAnimeList's map onto them (watching → CURRENT, on_hold → PAUSED,
 * plan_to_watch → PLANNING, a rewatch → REPEATING).
 */
enum class AnimeListStatus { CURRENT, PLANNING, COMPLETED, DROPPED, PAUSED, REPEATING }

/** A user's list entry for one anime, with what Fetch needs to move it forward. */
data class AnimeEntry(
    val status: AnimeListStatus?,
    val progress: Int,
    /** The anime's episode count; null while it is still airing with no count announced. */
    val episodes: Int?
)

/** One anime on a user's list, for the history import. At least one of the ids is set. */
data class AnimeListEntry(
    val aniListId: Long?,
    val malId: Long?,
    val title: String,
    val isMovie: Boolean,
    val status: AnimeListStatus?,
    val progress: Int,
    /** Epoch seconds of the last change; 0 when unknown. */
    val updatedAt: Long,
    val coverUrl: String?
)

data class AnimeProgressUpdate(val progress: Int, val status: AnimeListStatus)

/** Trakt's rule for a play that counts as watched; Fetch scrobbles every service the same way. */
const val ANIME_WATCHED_PERCENT = 80.0

/**
 * What a finished [episode] does to a list entry: progress only moves forward, the entry is
 * completed on its last episode, and a completed entry being rewatched outside a repeat is left
 * alone. Null means nothing to change.
 */
fun nextAnimeProgress(entry: AnimeEntry?, episode: Int): AnimeProgressUpdate? {
    if (episode <= 0) return null
    val total = entry?.episodes
    val progress = total?.let { episode.coerceAtMost(it) } ?: episode
    val current = entry?.progress ?: 0
    if (entry?.status == AnimeListStatus.COMPLETED || progress <= current) return null
    val status = when {
        total != null && progress >= total -> AnimeListStatus.COMPLETED
        entry?.status == AnimeListStatus.REPEATING -> AnimeListStatus.REPEATING
        else -> AnimeListStatus.CURRENT
    }
    return AnimeProgressUpdate(progress, status)
}
