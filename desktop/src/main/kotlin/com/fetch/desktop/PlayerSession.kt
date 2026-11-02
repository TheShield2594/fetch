package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.player.embedded.EmbeddedMpv
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SegmentType

/** The built-in player while it's on screen: libmpv, what's playing and its skippable parts. */
class PlayerSession(val mpv: EmbeddedMpv) {
    var title by mutableStateOf("")
    var segments by mutableStateOf<List<Segment>>(emptyList())
    var autoSkip by mutableStateOf<Set<SegmentType>>(emptySet())
    /** How many files this session has loaded; Up Next loads the next one into the same player. */
    var loads = 0

    /** The segment to offer a Skip button for at [position]: one that isn't skipped automatically. */
    fun skippable(position: Double): Segment? =
        segments.firstOrNull { it.type !in autoSkip && position >= it.startSec && position < it.endSec - 1.0 }
}
