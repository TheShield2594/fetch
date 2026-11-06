package com.fetch.tv.core.streams

import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.Stream

/**
 * Fetch plays debrid and direct links only. It has no built-in debrid service and no torrent
 * engine, so a bare torrent, or a stream an addon left for the client to resolve through debrid,
 * has nothing Fetch can play.
 */
fun Stream.hasNoPlayableLink(): Boolean = needsClientDebridResolve() || isTorrent()

/**
 * Drops the streams Fetch can't play and adds how many it dropped to
 * [AddonStreams.hiddenUnplayableCount], so the UI can say why an addon came back empty.
 */
fun AddonStreams.withoutUnplayableStreams(): AddonStreams {
    val hidden = streams.count { it.hasNoPlayableLink() }
    if (hidden == 0) return this
    return copy(
        streams = streams.filterNot { it.hasNoPlayableLink() },
        hiddenUnplayableCount = hiddenUnplayableCount + hidden
    )
}

/** Addon name to the number of its streams hidden for having no playable link; only addons that hid some. */
fun List<AddonStreams>.hiddenUnplayableCounts(): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    forEach { group ->
        if (group.hiddenUnplayableCount > 0) {
            counts[group.addonName] = (counts[group.addonName] ?: 0) + group.hiddenUnplayableCount
        }
    }
    return counts
}

/** Addons that answered, but only with streams Fetch hid (e.g. Torrentio without a debrid key). */
fun List<AddonStreams>.unplayableOnlyAddonNames(): Set<String> {
    val withPlayable = filter { it.streams.isNotEmpty() }.mapTo(hashSetOf()) { it.addonName }
    return filter { it.streams.isEmpty() && it.hiddenUnplayableCount > 0 && it.addonName !in withPlayable }
        .mapTo(linkedSetOf()) { it.addonName }
}
