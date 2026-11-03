package com.fetch.desktop.home

import java.util.concurrent.TimeUnit

/**
 * Whether the connection is one the background ready check may use, as the TV's
 * HomeReadyChecker asks Android: Wi-Fi or Ethernet that isn't metered, never mobile data.
 * On Linux, NetworkManager knows (its `Metered` and `PrimaryConnectionType` properties, read
 * over D-Bus). Without NetworkManager there's nothing to go by, and a desktop is taken to be on
 * an unmetered connection.
 */
object NetworkMetering {
    /** NetworkManager's NMMetered values. */
    private const val METERED_YES = 1
    private const val METERED_GUESS_YES = 3

    /** Connection types that are mobile data (or tethered over Bluetooth). */
    private val mobileTypes = setOf("gsm", "cdma", "bluetooth", "wwan", "wimax", "olpc-mesh", "modem")

    private const val CACHE_MS = 30_000L
    @Volatile private var cached: Pair<Long, Boolean>? = null

    /** True unless NetworkManager says the connection is metered or mobile. Blocks briefly; call off the UI thread. */
    fun isUnmetered(nowMs: Long = System.currentTimeMillis()): Boolean {
        cached?.let { (at, value) -> if (nowMs - at < CACHE_MS) return value }
        val metered = query("Metered")?.let(::parseUint)
        val type = query("PrimaryConnectionType")?.let(::parseString)
        return decide(metered, type).also { cached = nowMs to it }
    }

    /** The decision on NetworkManager's answers (null when it gave none). */
    fun decide(metered: Int?, primaryType: String?): Boolean {
        if (metered == METERED_YES || metered == METERED_GUESS_YES) return false
        if (primaryType != null && primaryType.lowercase() in mobileTypes) return false
        return true
    }

    /** `variant uint32 4` from dbus-send. */
    fun parseUint(output: String): Int? = Regex("""uint32\s+(\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()

    /** `variant string "802-11-wireless"` from dbus-send. */
    fun parseString(output: String): String? = Regex("""string\s+"([^"]*)"""").find(output)?.groupValues?.get(1)

    private fun query(property: String): String? = runCatching {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("linux")) return null
        val process = ProcessBuilder(
            "dbus-send", "--system", "--print-reply", "--reply-timeout=1500",
            "--dest=org.freedesktop.NetworkManager", "/org/freedesktop/NetworkManager",
            "org.freedesktop.DBus.Properties.Get",
            "string:org.freedesktop.NetworkManager", "string:$property"
        ).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        val output = process.inputStream.bufferedReader().readText()
        output.takeIf { process.exitValue() == 0 }
    }.getOrNull()
}
