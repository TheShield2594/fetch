package com.fetch.tv.core.livetv

import java.util.concurrent.ConcurrentHashMap

/**
 * The ids of channels opened from the Live TV guide (#660), so the player treats them as live
 * from the first frame: no resume point, no Continue Watching entry, and the Go live control.
 *
 * Kept in memory. Addon channels come with whatever Stremio type their addon declares, so their
 * type can't say they are live, and the player's own live signal arrives only once a stream has
 * loaded — and never for a plain MPEG-TS feed with no duration.
 */
object LiveTvChannels {
    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun markLive(id: String) {
        if (id.isNotBlank()) ids += id
    }

    fun isLiveChannel(id: String?): Boolean = id != null && id in ids
}
