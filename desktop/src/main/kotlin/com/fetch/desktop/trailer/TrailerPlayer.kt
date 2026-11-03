package com.fetch.desktop.trailer

import com.fetch.desktop.YouTubeHost
import com.fetch.desktop.data.PlayerExtras
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.SubtitleMode
import com.fetch.desktop.player.ExternalPlayer
import com.fetch.desktop.player.MpvArgs
import com.fetch.desktop.youtube.YouTubePlayback
import com.fetch.tv.domain.model.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Trailers as the TV plays them, in the app: in the built-in player (libmpv resolving YouTube
 * through yt-dlp) when that's the Default Player, closing back to the title page when they end;
 * otherwise in mpv's window. 1080p at most unless 4K trailers are on (Settings › Layout).
 */
class TrailerPlayer(private val scope: CoroutineScope, private val host: YouTubeHost) {

    /** Plays the YouTube trailer [ytId] of [title]. */
    fun play(ytId: String, title: String) {
        scope.launch {
            val builtIn = host.chooseBuiltIn() ?: return@launch
            val label = "$title (trailer)"
            val url = watchUrl(ytId)
            val options = options(host.playerSettings)
            val session = if (builtIn) host.openBuiltInPlayer() else null
            if (session == null) {
                host.closePlayer()
                val stream = Stream(
                    name = "Trailer", title = label, description = null, url = url, ytId = null, infoHash = null, fileIdx = null,
                    externalUrl = null, behaviorHints = null, addonName = "YouTube", addonLogo = null
                )
                val result = if (ExternalPlayer.findMpv() != null) ExternalPlayer.play(stream, label, options = options) else ExternalPlayer.playUrl(url, label)
                when (result) {
                    is ExternalPlayer.Result.Started -> host.message("Opening the trailer in ${result.player}")
                    is ExternalPlayer.Result.Failed -> host.message(result.message)
                }
                return@launch
            }
            session.title = label
            session.target = null
            session.stream = null
            session.sources = emptyList()
            session.nextEpisode = null
            session.upNextDismissed = false
            session.addonSubtitles = emptyList()
            session.segments = emptyList()
            session.autoSkip = emptySet()
            session.description = null
            val load = ++session.loads
            session.mpv.load(url, label, null, options, emptyMap())
            val ending = withContext(Dispatchers.IO) { session.mpv.follow { } }
            // Played to the end: back to the title page, as the TV does.
            if (ending.reachedEnd && host.currentPlayer === session && session.loads == load) host.closePlayer()
        }
    }

    companion object {
        fun watchUrl(ytId: String) = "https://www.youtube.com/watch?v=$ytId"

        /** The tallest trailer to play: 2160 with 4K trailers on, else 1080 (the TV's TrailerVideoPolicy). */
        fun maxHeight(extras: PlayerExtras): Int = if (extras.trailer4k) 2160 else 1080

        /** mpv options for a trailer: the player's own, yt-dlp at the height cap, no subtitles picked. */
        fun options(player: PlayerSettings): List<String> = buildList {
            addAll(MpvArgs.build(player))
            add("--ytdl=yes")
            add("--ytdl-format=${YouTubePlayback.ytdlFormat(maxHeight(player.extras))}")
            if (player.subtitleMode != SubtitleMode.OFF) add("--sid=no")
        }
    }
}
