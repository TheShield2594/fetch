package com.fetch.tv.data.local

import androidx.compose.runtime.Immutable

/**
 * A show the user removed from Up Next, with where they were when they did it
 * (TheShield2594/fetch#648).
 *
 * [seedSeason]/[seedEpisode] is the last episode they had watched, [season]/[episode] the one
 * Up Next was offering and [hadAired] whether it had aired. The show comes back by itself when
 * newer history arrives (the seed moves), when Up Next offers a later episode, or when the
 * offered episode airs. A dismissal stored before this was tracked has no seed and hides the
 * show until it is restored in Settings or the user watches it in Fetch.
 */
@Immutable
data class NextUpDismissal(
    val contentId: String,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val hadAired: Boolean? = null,
    val dismissedAtMs: Long = 0L,
    val title: String? = null
) {
    val isLegacy: Boolean get() = seedSeason == null && seedEpisode == null && season == null && episode == null

    /**
     * Whether this dismissal still hides a Next Up for the show. Unknown values on the
     * caller's side (null) don't bring the show back.
     */
    fun hides(
        seedSeason: Int?,
        seedEpisode: Int?,
        season: Int? = null,
        episode: Int? = null,
        hasAired: Boolean? = null
    ): Boolean {
        if (isLegacy) return true
        if (this.seedSeason != null && this.seedEpisode != null &&
            seedSeason != null && seedEpisode != null &&
            episodeOrder(seedSeason, seedEpisode) != episodeOrder(this.seedSeason, this.seedEpisode)
        ) {
            return false
        }
        if (this.season != null && this.episode != null && season != null && episode != null &&
            episodeOrder(season, episode) > episodeOrder(this.season, this.episode)
        ) {
            return false
        }
        if (hadAired == false && hasAired == true) return false
        return true
    }

    internal fun encode(): String = listOf(
        contentId.trim(),
        seedSeason?.toString().orEmpty(),
        seedEpisode?.toString().orEmpty(),
        season?.toString().orEmpty(),
        episode?.toString().orEmpty(),
        when (hadAired) { true -> "1"; false -> "0"; null -> "" },
        dismissedAtMs.toString(),
        title.orEmpty().replace(SEPARATOR, ' ')
    ).joinToString(SEPARATOR.toString())

    companion object {
        /** ASCII unit separator: can't appear in a content id. */
        internal const val SEPARATOR = '\u001F'

        /**
         * Reads one stored entry: the current encoding, or the legacy "contentId" and
         * "contentId|season|episode" keys (which hid the show by id alone).
         */
        internal fun decode(raw: String): NextUpDismissal? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            if (SEPARATOR !in value) {
                val id = value.substringBefore("|").trim()
                return id.takeIf { it.isNotEmpty() }?.let { NextUpDismissal(contentId = it) }
            }
            val parts = value.split(SEPARATOR, limit = 8)
            val id = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return NextUpDismissal(
                contentId = id,
                seedSeason = parts.getOrNull(1)?.toIntOrNull(),
                seedEpisode = parts.getOrNull(2)?.toIntOrNull(),
                season = parts.getOrNull(3)?.toIntOrNull(),
                episode = parts.getOrNull(4)?.toIntOrNull(),
                hadAired = when (parts.getOrNull(5)) { "1" -> true; "0" -> false; else -> null },
                dismissedAtMs = parts.getOrNull(6)?.toLongOrNull() ?: 0L,
                title = parts.getOrNull(7)?.takeIf { it.isNotBlank() }
            )
        }

        internal fun contentIdOf(raw: String): String =
            raw.trim().substringBefore(SEPARATOR).substringBefore("|").trim()

        private fun episodeOrder(season: Int, episode: Int): Long =
            season.toLong() * 100_000L + episode.toLong()
    }
}

/** The active profile's Up Next dismissals, one per show. */
@Immutable
data class NextUpDismissals(val byContentId: Map<String, NextUpDismissal> = emptyMap()) {

    val all: List<NextUpDismissal>
        get() = byContentId.values.sortedByDescending { it.dismissedAtMs }

    fun hides(
        contentId: String,
        seedSeason: Int?,
        seedEpisode: Int?,
        season: Int? = null,
        episode: Int? = null,
        hasAired: Boolean? = null
    ): Boolean = byContentId[contentId.trim()]
        ?.hides(seedSeason, seedEpisode, season, episode, hasAired) == true

    /**
     * For a watch-history seed whose Next Up episode isn't known yet. An unaired episode
     * that was dismissed may have aired since, so those seeds still go on to be resolved
     * and are checked again with [hides].
     */
    fun hidesSeed(contentId: String, seedSeason: Int?, seedEpisode: Int?): Boolean {
        val dismissal = byContentId[contentId.trim()] ?: return false
        if (dismissal.hadAired == false) return false
        return dismissal.hides(seedSeason, seedEpisode)
    }

    companion object {
        val EMPTY = NextUpDismissals()

        internal fun fromStored(raw: Set<String>): NextUpDismissals {
            val entries = raw.mapNotNull(NextUpDismissal::decode)
            // A show has one entry; if an old and a new one both survive, the newest wins.
            val byId = entries
                .groupBy { it.contentId }
                .mapValues { (_, candidates) ->
                    candidates.maxWith(
                        compareBy<NextUpDismissal> { !it.isLegacy }.thenBy { it.dismissedAtMs }
                    )
                }
            return NextUpDismissals(byId)
        }
    }
}
