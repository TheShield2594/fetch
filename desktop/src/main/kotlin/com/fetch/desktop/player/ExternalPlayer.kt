package com.fetch.desktop.player

import com.fetch.tv.domain.model.Stream
import java.io.File

/**
 * Plays a stream in mpv, the same player the TV app uses as its fallback engine. An embedded
 * player (libmpv rendering into the window) comes later; until then this hands the stream to
 * mpv in its own window, or to the desktop's default handler when mpv isn't installed.
 *
 * With mpv, Fetch also opens mpv's JSON IPC socket ([ipcSocket]) and reads the position from it,
 * which is how Continue Watching knows where you stopped. [startSeconds] resumes from there.
 */
object ExternalPlayer {

    sealed interface Result {
        /**
         * [player] is the program's name, for the confirmation message. [process] and
         * [ipcSocket] are set when it is mpv, so the caller can follow the playback position.
         */
        data class Started(val player: String, val process: Process? = null, val ipcSocket: File? = null) : Result
        data class Failed(val message: String) : Result
    }

    /** The command line that plays [stream], or null when Fetch can't play it. */
    fun command(
        stream: Stream,
        title: String,
        mpvPath: String? = findMpv(),
        startSeconds: Long? = null,
        ipcSocket: File? = null
    ): List<String>? {
        val target = playableTarget(stream) ?: return null
        if (mpvPath == null) return listOf("xdg-open", target)
        return buildList {
            add(mpvPath)
            add("--force-window=immediate")
            add("--force-media-title=$title")
            if (startSeconds != null && startSeconds > 0) add("--start=$startSeconds")
            if (ipcSocket != null) add("--input-ipc-server=${ipcSocket.absolutePath}")
            stream.behaviorHints?.proxyHeaders?.request.orEmpty().forEach { (name, value) ->
                // One header per option: a comma inside a value would split a combined list.
                add("--http-header-fields-append=$name: $value")
            }
            add("--")
            add(target)
        }
    }

    fun play(stream: Stream, title: String, startSeconds: Long? = null): Result {
        val mpv = findMpv()
        val socket = mpv?.let { newIpcSocket() }
        val command = command(stream, title, mpv, startSeconds, socket)
            ?: return Result.Failed("This source has no playable link. Fetch plays direct and debrid links only.")
        return start(command, socket)
    }

    /** Plays a plain link, such as a YouTube trailer (mpv resolves it through yt-dlp). */
    fun playUrl(url: String, title: String, mpvPath: String? = findMpv()): Result {
        val command = if (mpvPath == null) {
            listOf("xdg-open", url)
        } else {
            listOf(mpvPath, "--force-window=immediate", "--force-media-title=$title", "--", url)
        }
        return start(command, ipcSocket = null)
    }

    private fun start(command: List<String>, ipcSocket: File?): Result {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            val isMpv = command.first() != "xdg-open"
            Result.Started(
                player = File(command.first()).name,
                process = process.takeIf { isMpv },
                ipcSocket = ipcSocket.takeIf { isMpv }
            )
        } catch (e: Exception) {
            Result.Failed("Couldn't start ${command.first()}: ${e.message}. Install mpv to play streams.")
        }
    }

    /** A fresh socket path in the user's runtime directory (or the temp directory). */
    private fun newIpcSocket(): File {
        val dir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: File(System.getProperty("java.io.tmpdir"))
        return File(dir, "fetch-mpv-${ProcessHandle.current().pid()}-${System.nanoTime()}.sock")
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
