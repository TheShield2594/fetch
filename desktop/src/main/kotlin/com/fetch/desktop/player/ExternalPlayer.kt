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
        data class Started(
            val player: String,
            val process: Process? = null,
            val ipcSocket: File? = null,
            /** mpv's output (and yt-dlp's, through it), owner-readable, for [failureReason]. */
            val outputLog: File? = null
        ) : Result
        data class Failed(val message: String) : Result
    }

    /** The command line that plays [stream], or null when Fetch can't play it. */
    fun command(
        stream: Stream,
        title: String,
        mpvPath: String? = findMpv(),
        startSeconds: Long? = null,
        ipcSocket: File? = null,
        options: List<String> = emptyList(),
        /** mpv's --start as given ("754" or "42%"); wins over [startSeconds]. */
        start: String? = null
    ): List<String>? {
        val target = playableTarget(stream) ?: return null
        if (mpvPath == null) return listOf("xdg-open", target)
        return buildList {
            add(mpvPath)
            add("--force-window=immediate")
            add("--force-media-title=$title")
            val from = start ?: startSeconds?.takeIf { it > 0 }?.toString()
            if (from != null) add("--start=$from")
            if (ipcSocket != null) add("--input-ipc-server=${ipcSocket.absolutePath}")
            addAll(options)
            stream.behaviorHints?.proxyHeaders?.request.orEmpty().forEach { (name, value) ->
                // One header per option: a comma inside a value would split a combined list.
                add("--http-header-fields-append=$name: $value")
            }
            add("--")
            add(target)
        }
    }

    /**
     * [options] are extra mpv options from the player settings ([MpvArgs]). With [captureOutput],
     * mpv's output goes to [Result.Started.outputLog], which the caller hands to [failureReason]
     * (that deletes it); otherwise it's discarded.
     */
    fun play(
        stream: Stream,
        title: String,
        start: String? = null,
        options: List<String> = emptyList(),
        captureOutput: Boolean = false
    ): Result {
        val mpv = findMpv()
        val socket = mpv?.let { newIpcSocket() }
        val command = command(stream, title, mpv, startSeconds = null, ipcSocket = socket, options = options, start = start)
            ?: return Result.Failed("This source has no playable link. Fetch plays direct and debrid links only.")
        return start(command, socket, captureOutput)
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

    private fun start(command: List<String>, ipcSocket: File?, captureOutput: Boolean = false): Result {
        return try {
            val isMpv = command.first() != "xdg-open"
            // mpv's output is kept (in a file only its owner can read) so a link mpv or yt-dlp
            // couldn't open can say why, instead of the window just closing.
            val log = if (isMpv && captureOutput) runCatching { java.nio.file.Files.createTempFile("fetch-mpv-", ".log").toFile() }.getOrNull() else null
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log?.let { ProcessBuilder.Redirect.to(it) } ?: ProcessBuilder.Redirect.DISCARD)
                .start()
            log?.deleteOnExit()
            Result.Started(
                player = File(command.first()).name,
                process = process.takeIf { isMpv },
                ipcSocket = ipcSocket.takeIf { isMpv },
                outputLog = log
            )
        } catch (e: Exception) {
            Result.Failed("Couldn't start ${command.first()}: ${e.message}. Install mpv to play streams.")
        }
    }

    /**
     * Why mpv couldn't play, from its output: the last error it or yt-dlp printed ("ERROR: Sign
     * in to confirm your age", "could not find cookies database"). Null when it printed none.
     * The log is deleted after reading.
     */
    fun failureReason(log: File?): String? {
        if (log == null) return null
        return try {
            // Only the tail is kept while reading: the log grows with the whole playback.
            val tail = ArrayDeque<String>(FAILURE_TAIL_LINES)
            log.useLines { lines ->
                lines.forEach { line ->
                    if (tail.size == FAILURE_TAIL_LINES) tail.removeFirst()
                    tail.addLast(line)
                }
            }
            tail.lastOrNull { line ->
                line.contains("ERROR", ignoreCase = false) || line.contains("Failed to recognize file format") ||
                    line.contains("could not find", ignoreCase = true)
            }?.let(::cleanErrorLine)
        } catch (_: Exception) {
            null
        } finally {
            log.delete()
        }
    }

    private const val FAILURE_TAIL_LINES = 200

    /** "[ytdl_hook] ERROR: [youtube] abc: Sign in…" → "Sign in…". */
    internal fun cleanErrorLine(line: String): String =
        line.replace(Regex("""^\s*(\[[^\]]+]\s*)+"""), "")
            .replace(Regex("""^ERROR:\s*"""), "")
            .replace(Regex("""^\[[^\]]+]\s*[\w-]+:\s*"""), "")
            .trim()
            .take(200)

    /** A fresh socket path in the user's runtime directory (or the temp directory). */
    private fun newIpcSocket(): File {
        val dir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: File(System.getProperty("java.io.tmpdir"))
        return File(dir, "fetch-mpv-${ProcessHandle.current().pid()}-${System.nanoTime()}.sock")
    }

    /** The link mpv opens for [stream], or null when Fetch can't play it. */
    fun playableUrl(stream: Stream): String? = playableTarget(stream)

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
