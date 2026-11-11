package com.fetch.desktop.youtube

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.PlayerSession
import com.fetch.tv.data.youtube.YouTubeChapter
import com.fetch.tv.data.youtube.YouTubePlaylistOption
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeStoryboard
import com.fetch.tv.data.youtube.YouTubeVideo
import com.fetch.tv.data.youtube.YouTubeVideoActions
import com.fetch.tv.data.youtube.YouTubeVoteCounts
import com.fetch.tv.data.youtube.YouTubeWatchNext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** The YouTube panel open in the built-in player (beside the player's own Audio/Subtitles/… panel). */
enum class YouTubePlayerPanel(val label: String) {
    CHAPTERS("Chapters"),
    CAPTIONS("Captions"),
    SAVE("Save to playlist")
}

/** "Skipped sponsor": the segment just skipped automatically, which Watch it goes back to. */
data class YouTubeSkipNotice(val label: String, val startSec: Double, val shownAtMs: Long)

/**
 * What the built-in player shows for one YouTube video, as the TV's YouTube player overlay has it:
 * SponsorBlock marks and storyboard frames on the seek bar, the like / dislike / subscribe / save
 * row, the chapter and caption menus and the "undo skip" notice. Tied to one load of the player
 * ([load] is [PlayerSession.loads] when it started), so anything else played in the same player
 * doesn't show it.
 */
class YouTubePlayerExtras(val video: YouTubeVideo, val load: Int) {
    /** SponsorBlock's segments of every category that isn't Off, drawn on the seek bar in their colours. */
    var marks by mutableStateOf<List<YouTubeSkipSegment>>(emptyList())
    var storyboard by mutableStateOf<YouTubeStoryboard?>(null)
    /** The like and subscribe state, once the watch page has loaded (signed in only). */
    var actions by mutableStateOf<YouTubeVideoActions?>(null)
    /** YouTube refused the last like or subscribe; the change was undone. */
    var actionFailed by mutableStateOf(false)
    var votes by mutableStateOf<YouTubeVoteCounts?>(null)
    /** YouTube's chapters from the watch page; mpv's own chapter list (from yt-dlp) comes first. */
    var chapters by mutableStateOf<List<YouTubeChapter>>(emptyList())
    var captionTracks by mutableStateOf<List<YouTubeCaptionTrack>>(emptyList())
    /** The YouTube caption track showing ([YouTubeCaptionTrack.id]); null while off or another subtitle shows. */
    var selectedCaption by mutableStateOf<String?>(null)
    /** The showing track's cues, which Fetch draws itself (the TV's caption renderer); empty while mpv shows it instead. */
    var captionCues by mutableStateOf<List<com.fetch.desktop.player.autosync.Cue>>(emptyList())
    var skipNotice by mutableStateOf<YouTubeSkipNotice?>(null)
    var panel by mutableStateOf<YouTubePlayerPanel?>(null)
    /** The channel's playlists for Save, read when the panel opens; null while loading or failed. */
    var playlists by mutableStateOf<List<YouTubePlaylistOption>?>(null)
    var playlistsFailed by mutableStateOf(false)
    /** mpv track ids of the YouTube captions already added, by track id, so picking one again reuses it. */
    internal val addedCaptions = mutableMapOf<String, String>()
}

/** This session's YouTube extras, when what it plays now is the YouTube video they're for. */
fun PlayerSession.youTubeExtras(): YouTubePlayerExtras? = youTube?.takeIf { it.load == loads }

object YouTubeChapters {
    private val json = Json { ignoreUnknownKeys = true }

    /** mpv's `chapter-list` (`[{"title":…,"time":…}]`) as chapters; empty when there are fewer than two. */
    fun fromMpv(raw: String?): List<YouTubeChapter> {
        if (raw.isNullOrBlank()) return emptyList()
        val chapters = runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val time = o["time"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                YouTubeChapter((time * 1000).toLong(), o["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }.getOrDefault(emptyList()).distinctBy { it.startMs }.sortedBy { it.startMs }
        return if (chapters.size >= 2) chapters else emptyList()
    }

    /** The chapter playing at [positionMs] (the TV's chapterAt). */
    fun at(chapters: List<YouTubeChapter>, positionMs: Long): YouTubeChapter? = chapters.lastOrNull { it.startMs <= positionMs }
}

/**
 * The card when a video ends (the TV's UpNextCard): YouTube's pick, counting down
 * [YouTubeUpNext.COUNTDOWN_SECONDS] to play it while autoplay is on, and the related videos.
 * [builtIn] is where it plays: the built-in player, or mpv's window.
 */
data class YouTubeUpNextPrompt(
    val next: YouTubeVideo?,
    val related: List<YouTubeVideo>,
    val secondsLeft: Int,
    /** False once stopped (Cancel), or when autoplay is off: then it waits for a pick. */
    val counting: Boolean,
    val builtIn: Boolean,
    /** "Next in <playlist>" when the next video is the playlist's. */
    val playlistTitle: String? = null
) {
    /** One second later: [secondsLeft] down by one while counting. */
    fun tick(): YouTubeUpNextPrompt = if (counting) copy(secondsLeft = (secondsLeft - 1).coerceAtLeast(0)) else this

    /** Whether the countdown ran out and [next] should play now. */
    val due: Boolean get() = counting && next != null && secondsLeft <= 0

    val heading: String get() = playlistTitle?.let { "Next in $it" } ?: "Up next"

    val status: String get() = if (counting && next != null) "Playing in $secondsLeft ${if (secondsLeft == 1) "second" else "seconds"}" else "Select a video to play"
}

object YouTubeUpNext {
    /** The TV's countdown and how many related videos its end card shows. */
    const val COUNTDOWN_SECONDS = 10
    const val RELATED_COUNT = 8

    /**
     * The card for a video that played to its end, or null when there's nothing to offer. It counts
     * down only with [autoplay] on and a next video.
     */
    fun prompt(watchNext: YouTubeWatchNext?, autoplay: Boolean, builtIn: Boolean): YouTubeUpNextPrompt? {
        val next = watchNext?.next
        val related = watchNext?.related.orEmpty().filter { it.videoId != next?.videoId }.take(RELATED_COUNT)
        if (next == null && related.isEmpty()) return null
        val playlist = watchNext?.playlist?.takeIf { next?.playlistId == it.playlistId }?.title
        return YouTubeUpNextPrompt(next, related, COUNTDOWN_SECONDS, counting = autoplay && next != null, builtIn = builtIn, playlistTitle = playlist)
    }
}

/**
 * Shows a note in mpv's window (its OSD, over the IPC socket): Return YouTube Dislike's counts,
 * which may arrive before or after mpv's socket opens. A note sent before it opens shows once it does.
 */
class MpvOsdNote {
    private var sender: ((String) -> Unit)? = null
    private var pending: String? = null

    /** mpv's socket is open: [send] shows text from now on. */
    @Synchronized
    fun attach(send: (String) -> Unit) {
        sender = send
        pending?.let { runCatching { send(it) } }
        pending = null
    }

    @Synchronized
    fun show(text: String) {
        val send = sender
        if (send == null) pending = text else runCatching { send(text) }
    }
}
