package com.fetch.desktop.player

import java.io.File

/**
 * yt-dlp, which mpv (the built-in player and mpv's window alike) needs to open YouTube: trailers,
 * Fetch's YouTube and a collection's hero video. Without it mpv only says it couldn't open the
 * page, so the players check first and say what to install.
 */
object YtDlp {
    const val MISSING_MESSAGE =
        "YouTube needs yt-dlp, which isn't installed. Install it (sudo apt install yt-dlp, sudo dnf install yt-dlp, or pipx install yt-dlp) and try again."

    /** yt-dlp on the PATH, or null. Only yt-dlp: youtube-dl lacks the options Fetch passes. */
    fun find(path: String? = System.getenv("PATH")): String? = path.orEmpty()
        .split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .flatMap { dir -> listOf(File(dir, "yt-dlp"), File(dir, "yt-dlp.exe")) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath

    fun installed(): Boolean = find() != null

    /** Whether mpv opens [url] through yt-dlp: a YouTube page rather than a direct link. */
    fun needs(url: String?): Boolean {
        val host = runCatching { java.net.URI(url ?: return false).host }.getOrNull()?.lowercase() ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    /** [MISSING_MESSAGE] when [url] needs yt-dlp and it isn't installed, else null. */
    fun missingFor(url: String?, installed: () -> Boolean = ::installed): String? =
        MISSING_MESSAGE.takeIf { needs(url) && !installed() }
}
