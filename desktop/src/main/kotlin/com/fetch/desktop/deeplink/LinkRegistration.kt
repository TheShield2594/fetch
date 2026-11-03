package com.fetch.desktop.deeplink

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Makes Fetch the handler for `fetch://` links (and `stremio://` ones while nothing else has
 * them), as the TV app's intent filters do. jpackage's menu entry can't declare URL schemes, so
 * Fetch writes its own hidden entry in `~/.local/share/applications` and points `xdg-mime` at
 * it. Only for installed or unpacked builds, which have a launcher to point at.
 */
object LinkRegistration {
    const val ENTRY = "fetch-links.desktop"
    private const val FETCH = "x-scheme-handler/fetch"
    private const val STREMIO = "x-scheme-handler/stremio"

    /** The app's launcher, set by jpackage; null when running from Gradle. */
    val launcher: String? get() = System.getProperty("jpackage.app-path")?.takeIf { File(it).canExecute() }

    private val applicationsDir: File
        get() = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share"), "applications")

    /** The desktop entry text for [launcher]: hidden from menus, taking a link as its argument. */
    fun entry(launcher: String, icon: String?): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Name=Fetch")
        appendLine("Comment=Opens fetch:// and stremio:// links in Fetch")
        appendLine("Exec=\"${launcher.replace("\"", "\\\"")}\" %u")
        icon?.let { appendLine("Icon=$it") }
        appendLine("Terminal=false")
        appendLine("NoDisplay=true")
        appendLine("MimeType=$FETCH;$STREMIO;")
    }

    /**
     * Writes the entry and claims `fetch://`, and `stremio://` when no other app has it (or
     * [takeStremio] asks to). Quiet and best-effort: a system without xdg-utils just doesn't get it.
     */
    fun register(takeStremio: Boolean = false): Boolean {
        val exe = launcher ?: return false
        return runCatching {
            val dir = applicationsDir.apply { mkdirs() }
            val file = File(dir, ENTRY)
            val icon = File(exe).parentFile?.parentFile?.let { File(it, "lib/Fetch.png").takeIf(File::exists) ?: File(it, "lib/fetch.png").takeIf(File::exists) }?.absolutePath
            val text = entry(exe, icon)
            if (!file.exists() || file.readText() != text) {
                file.writeText(text)
                run("update-desktop-database", dir.absolutePath)
            }
            if (handler(FETCH) != ENTRY) run("xdg-mime", "default", ENTRY, FETCH)
            val stremio = handler(STREMIO)
            if (takeStremio || stremio.isNullOrBlank()) run("xdg-mime", "default", ENTRY, STREMIO)
            true
        }.getOrDefault(false)
    }

    /** Which entry opens `fetch://` and `stremio://` now, for Settings › About. */
    fun status(): Pair<String?, String?> = handler(FETCH) to handler(STREMIO)

    private fun handler(type: String): String? =
        runCatching {
            val p = ProcessBuilder("xdg-mime", "query", "default", type).redirectErrorStream(true).start()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroy()
                null
            } else p.inputStream.bufferedReader().readText().trim().ifEmpty { null }
        }.getOrNull()

    private fun run(vararg command: String) {
        runCatching { ProcessBuilder(*command).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS) }
    }
}
