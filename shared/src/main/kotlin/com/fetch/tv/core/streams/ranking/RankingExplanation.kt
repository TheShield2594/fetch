package com.fetch.tv.core.streams.ranking

import androidx.compose.runtime.Immutable

/**
 * One part of [StreamRanker]'s score, which may add or take away points. Listed in the order the ranker adds them,
 * which also breaks ties when two factors separate a pair by the same number of points.
 * [ADDON_ORDER] is the ranker's last resort: equal scores keep the order the addons gave.
 */
enum class RankFactor {
    OWN_LIBRARY,
    /** Debrid cache status: a few points for a cached link, a large penalty for one not cached yet. */
    DEBRID_CACHED,
    RESOLUTION,
    PREFERRED_CODEC,
    HDR,
    AUDIO,
    PREFERRED_KEYWORD,
    LANGUAGE,
    PREFERRED_RELEASE_GROUP,
    PREFERRED_ADDON,
    RELEASE_SOURCE,
    SIZE,
    SEEDERS,
    ADDON_ORDER
}

/**
 * What ordered two eligible streams: [ahead] ranks above [behind], and [factor] is the part of
 * the score that did it.
 */
@Immutable
data class RankComparison(
    val ahead: RankedStream,
    val behind: RankedStream,
    val factor: RankFactor
)
