package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.data.PlaybackTarget
import com.fetch.desktop.data.VideoFit
import com.fetch.desktop.player.embedded.EmbeddedMpv
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SegmentType
import com.fetch.tv.core.streams.ranking.RankedStream
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.Video

/** The side panel open in the built-in player, as on the TV's player overlay. */
enum class PlayerPanel(val label: String) {
    AUDIO("Audio"),
    SUBTITLES("Subtitles"),
    SOURCES("Sources"),
    EPISODES("Episodes")
}

/** A subtitle file an addon offers for what's playing. */
data class AddonSubtitle(val language: String, val url: String, val addon: String)

/** The built-in player while it's on screen: libmpv, what's playing and its skippable parts. */
class PlayerSession(val mpv: EmbeddedMpv) {
    var title by mutableStateOf("")
    var segments by mutableStateOf<List<Segment>>(emptyList())
    var autoSkip by mutableStateOf<Set<SegmentType>>(emptySet())
    /** How many files this session has loaded; Up Next loads the next one into the same player. */
    var loads = 0

    /** What's playing, its stream and the other sources for it (for switching mid-playback). */
    var target: PlaybackTarget? by mutableStateOf(null)
    var stream: Stream? by mutableStateOf(null)
    var sources by mutableStateOf<List<RankedStream>>(emptyList())
    var sourcesLoading by mutableStateOf(false)
    /** The episode Up Next would play after this one, when autoplay is on. */
    var nextEpisode: Video? by mutableStateOf(null)
    /** The user said no to Up Next for this episode. */
    var upNextDismissed by mutableStateOf(false)
    var panel: PlayerPanel? by mutableStateOf(null)
    var addonSubtitles by mutableStateOf<List<AddonSubtitle>?>(null)
    var addonSubtitlesLoading by mutableStateOf(false)
    var showInfo by mutableStateOf(false)
    var fit by mutableStateOf(VideoFit.FIT)
    /** Whether to show the Skip button for segments that aren't skipped automatically. */
    var skipButton by mutableStateOf(true)
    var description: String? by mutableStateOf(null)

    /** The segment to offer a Skip button for at [position]: one that isn't skipped automatically. */
    fun skippable(position: Double): Segment? =
        if (!skipButton) null
        else segments.firstOrNull { !it.skipsAutomatically(autoSkip) && position >= it.startSec && position < it.endSec - 1.0 }

    /**
     * When the Up Next card shows: [promptSeconds] before the end, or from the start of the
     * credits when IntroDB knows them, whichever comes first. Null when there's nothing next.
     */
    fun upNextCountdown(position: Double, duration: Double, promptSeconds: Int): Int? =
        if (nextEpisode == null || upNextDismissed) null else upNextCountdown(segments, position, duration, promptSeconds)
}

/**
 * Seconds left when the Up Next card should show: from [promptSeconds] before the end, or from
 * the start of the credits when IntroDB knows them. Null before that.
 */
fun upNextCountdown(segments: List<Segment>, position: Double, duration: Double, promptSeconds: Int): Int? {
    if (duration <= 0) return null
    val remaining = duration - position
    val credits = segments.firstOrNull { it.type == SegmentType.OUTRO }
    val inCredits = credits != null && position >= credits.startSec
    if (remaining > promptSeconds && !inCredits) return null
    return remaining.toInt().coerceAtLeast(0)
}

/** "Are you still watching?" before Up Next starts another episode. */
data class StillWatchingPrompt(val label: String, val onContinue: () -> Unit)
