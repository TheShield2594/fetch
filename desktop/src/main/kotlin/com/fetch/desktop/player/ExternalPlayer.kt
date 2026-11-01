package com.fetch.desktop.player

import com.fetch.tv.domain.model.Stream
import java.io.File

/**
 * Plays a stream in mpv, the same player the TV app uses as its fallback engine. An embedded
 * player (libmpv rendering into the window) comes later; until then this hands the stream to
 * mpv in its own window, or to the desktop's default handler when mpv isn't installed.
 */
object ExternalPlayer {

    sealed interface Result {
        /** [player] is the program's name, for the confirmation message. */
        data class Started(val player: String) : Result
        data class Failed(val message: String) : Result
    }

    /** The command line that plays [stream], or null when Fetch can't play it. */
    fun command(stream: Stream, title: String, mpvPath: String? = findMpv()): List<String>? {
        val target = playableTarget(stream) ?: return null
        if (mpvPath == null) return listOf("xdg-open", target)
        return buildList {
            add(mpvPath)
            add("--force-window=immediate")
            add("--force-media-title=$title")
            stream.behaviorHints?.proxyHeaders?.request.orEmpty().forEach { (name, value) ->
                // One header per option: a comma inside a value would split a combined list.
                add("--http-header-fields-append=$name: $value")
            }
            add("--")
            add(target)
        }
    }

    fun play(stream: Stream, title: String): Result {
        val command = command(stream, title)
            ?: return Result.Failed("This source has no playable link. Fetch plays direct and debrid links only.")
        return try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            Result.Started(File(command.first()).name)
        } catch (e: Exception) {
            Result.Failed("Couldn't start ${command.first()}: ${e.message}. Install mpv to play streams.")
        }
    }

    /** Whether Fetch can play [stream]: a direct link, or a YouTube id mpv resolves through yt-dlp. */
    fun canPlay(stream: Stream): Boolean = playableTarget(stream) != null

    private fun playableTarget(stream: Stream): String? {
        if (stream.isTorrent() || stream.needsClientDebridResolve()) return null
        stream.getStreamUrl()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { return it }
        stream.ytId?.takeIf { it.isNotBlank() }?.let { return "https://www.youtube.com/watch?v=$it" }
        return null
    }

    fun findMpv(): String? = System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .map { File(it, "mpv") }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
}
