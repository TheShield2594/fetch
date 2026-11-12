package com.fetch.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * System notifications through `notify-send` (libnotify), which every mainstream Linux desktop
 * answers. Missing tool or no notification daemon: nothing happens, the in-app banner still shows.
 */
object DesktopNotification {
    /** "Fetch X is available", with how to get it. */
    fun updateAvailable(update: AvailableUpdate) {
        val body = if (update.viaApt) {
            "Install it with: ${UpdateChecker.APT_UPGRADE_COMMAND}"
        } else {
            update.notes ?: "Open Fetch to download it."
        }
        send("Fetch ${update.version} is available", body)
    }

    fun send(title: String, body: String) {
        val command = buildList {
            add("notify-send")
            add("--app-name=Fetch")
            icon()?.let { add("--icon=$it") }
            add(title)
            add(body)
        }
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** The packaged icon (jpackage puts it beside the runtime, in /opt/fetch/lib), if there is one. */
    private fun icon(): String? {
        val home = System.getProperty("java.home") ?: return null
        return File(home).parentFile?.resolve("fetch.png")?.takeIf { it.isFile }?.path
    }
}
