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

/** What the player's buttons and keys do: straight to mpv ([MpvControls]). */
interface PlayerControls {
    val speed: Double
    fun togglePause()
    fun seekTo(seconds: Double)
    fun seekBy(seconds: Double)
    fun changeSpeed(value: Double)
}

class MpvControls(private val mpv: EmbeddedMpv) : PlayerControls {
    override val speed: Double get() = mpv.speed
    override fun togglePause() = mpv.togglePause()
    override fun seekTo(seconds: Double) = mpv.seekTo(seconds)
    override fun seekBy(seconds: Double) = mpv.seekBy(seconds)
    override fun changeSpeed(value: Double) = mpv.changeSpeed(value)
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

    /** Pause, seek and speed for the buttons and keys. */
    var controls: PlayerControls by mutableStateOf(MpvControls(mpv))
    /** A YouTube video's seek bar marks, actions and menus; see [com.fetch.desktop.youtube.youTubeExtras]. */
    var youTube: com.fetch.desktop.youtube.YouTubePlayerExtras? by mutableStateOf(null)

    /**
     * When the Up Next card shows: at the next-episode threshold the app set ([upNextAt], which
     * also holds it back for a post-credits scene). Null when there's nothing next.
     */
    fun upNextCountdown(position: Double, duration: Double): Int? {
        if (nextEpisode == null || upNextDismissed) return null
        // From the next-episode threshold (Settings › Up Next), as on TV.
        val threshold = upNextAt ?: return null
        return if (threshold(position, duration)) (duration - position).toInt().coerceAtLeast(0) else null
    }

    /** Whether the next-episode threshold is reached at (position, duration); set by the app. */
    var upNextAt: ((Double, Double) -> Boolean)? = null
}

/** "Are you still watching?" before Up Next starts another episode. */
data class StillWatchingPrompt(val label: String, val onContinue: () -> Unit)
