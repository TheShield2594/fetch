package com.fetch.desktop.player

import java.io.File
import java.util.Locale

enum class DebugStatSeverity { NORMAL, WARNING, DANGER }

/** One line of the stats overlay: a short label and its reading. */
data class DebugStat(val label: String, val value: String, val severity: DebugStatSeverity = DebugStatSeverity.NORMAL) {
    constructor(label: String, value: String, warn: Boolean) : this(label, value, if (warn) DebugStatSeverity.WARNING else DebugStatSeverity.NORMAL)
}

/** What mpv says about playback at one moment (its properties, already read). */
data class MpvSnapshot(
    /** `demuxer-cache-duration`, seconds read ahead. */
    val aheadSec: Double? = null,
    /** `video-bitrate` and `audio-bitrate`, bits per second. */
    val videoBitrate: Double? = null,
    val audioBitrate: Double? = null,
    /** `file-size` in bytes and `duration` in seconds, for the whole file's rate. */
    val fileSizeBytes: Long? = null,
    val durationSec: Double? = null,
    /** `cache-speed`: how fast mpv is reading from the network, bytes per second. */
    val readBytesPerSec: Double? = null,
    /** `frame-drop-count` plus `decoder-frame-drop-count`. */
    val droppedFrames: Int = 0
)

/**
 * The TV's playback stats overlay (PlayerDebugStatsOverlay) for the built-in player: cpu, paging,
 * memory, buffer, bitrate, network, dropped frames and temperature, sampled once a second. The
 * Linux readings come from /proc and /sys, which cover libmpv too since it runs inside Fetch.
 */
class DebugStatsSampler(
    private val procStat: () -> String? = { runCatching { File("/proc/self/stat").readText() }.getOrNull() },
    private val procStatus: () -> String? = { runCatching { File("/proc/self/status").readText() }.getOrNull() },
    private val thermal: () -> List<Int> = ::thermalMillidegrees,
    private val clockTicks: Long = 100L,
    private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    private val now: () -> Long = System::currentTimeMillis
) {
    private var lastCpu = -1L
    private var lastFaults = -1L
    private var lastAt = 0L
    private var cpuTotal = 0.0
    private var cpuSamples = 0
    private var faultTotal = 0.0
    private var faultSamples = 0
    private var bufferTotal = 0.0
    private var bufferSamples = 0
    private var netTotal = 0.0
    private var netSamples = 0

    fun sample(mpv: MpvSnapshot?): List<DebugStat> = buildList {
        val proc = procStat()?.let(::parseProcStat)
        val t = now()
        val elapsed = if (lastAt > 0) (t - lastAt) / 1000.0 else 0.0
        add(cpu(proc, elapsed))
        add(paging(proc, elapsed))
        if (proc != null) {
            lastCpu = proc.first
            lastFaults = proc.second
            lastAt = t
        }
        add(memory())
        add(buffer(mpv))
        add(bitrate(mpv))
        add(network(mpv))
        add(DebugStat("dropped", "${mpv?.droppedFrames ?: 0} frames", warn = (mpv?.droppedFrames ?: 0) > 0))
        add(temperature())
    }.filterNot { it.value == UNAVAILABLE }

    private fun cpu(proc: Pair<Long, Long>?, elapsed: Double): DebugStat {
        if (proc == null) return DebugStat("cpu", UNAVAILABLE)
        if (lastCpu < 0 || elapsed <= 0) return DebugStat("cpu", "...")
        val percent = (proc.first - lastCpu).toDouble() / clockTicks / elapsed / cores * 100.0
        cpuTotal += percent
        cpuSamples++
        return DebugStat("cpu", "%.0f%%   avg %.0f%%".format(Locale.US, percent, cpuTotal / cpuSamples), warn = percent >= 60.0)
    }

    private fun paging(proc: Pair<Long, Long>?, elapsed: Double): DebugStat {
        if (proc == null) return DebugStat("paging", UNAVAILABLE)
        if (lastFaults < 0 || elapsed <= 0) return DebugStat("paging", "...")
        val perSecond = (proc.second - lastFaults) / elapsed
        faultTotal += perSecond
        faultSamples++
        return DebugStat("paging", "%.0f/s   avg %.0f/s".format(Locale.US, perSecond, faultTotal / faultSamples), warn = perSecond >= 100.0)
    }

    private fun memory(): DebugStat {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val maxMb = runtime.maxMemory() / MB
        val rssMb = procStatus()?.let(::parseRssKb)?.let { it / 1024 }
        val severity = when {
            maxMb > 0 && usedMb.toDouble() / maxMb >= 0.90 -> DebugStatSeverity.DANGER
            maxMb > 0 && usedMb.toDouble() / maxMb >= 0.80 -> DebugStatSeverity.WARNING
            else -> DebugStatSeverity.NORMAL
        }
        return DebugStat("memory", "$usedMb / $maxMb MB" + (rssMb?.let { "   process $it MB" } ?: ""), severity)
    }

    private fun buffer(mpv: MpvSnapshot?): DebugStat {
        val ahead = mpv?.aheadSec ?: return DebugStat("buffer", UNAVAILABLE)
        bufferTotal += ahead
        bufferSamples++
        return DebugStat("buffer", "%.1fs   avg %.1fs".format(Locale.US, ahead, bufferTotal / bufferSamples), warn = ahead < 5.0)
    }

    private fun bitrate(mpv: MpvSnapshot?): DebugStat {
        val size = mpv?.fileSizeBytes
        val duration = mpv?.durationSec
        if (size != null && size > 0 && duration != null && duration > 0) {
            val bps = size * 8.0 / duration
            return DebugStat("bitrate", "%.1f Mbps file   %.1f GB".format(Locale.US, bps / 1_000_000.0, size / (1024.0 * 1024.0 * 1024.0)))
        }
        val video = mpv?.videoBitrate ?: 0.0
        val audio = mpv?.audioBitrate ?: 0.0
        if (video <= 0 && audio <= 0) return DebugStat("bitrate", UNAVAILABLE)
        return DebugStat("bitrate", "%.1f Mbps tracks".format(Locale.US, (video.coerceAtLeast(0.0) + audio.coerceAtLeast(0.0)) / 1_000_000.0))
    }

    private fun network(mpv: MpvSnapshot?): DebugStat {
        val speed = mpv?.readBytesPerSec ?: return DebugStat("network", UNAVAILABLE)
        val mbps = speed * 8.0 / 1_000_000.0
        netTotal += mbps
        netSamples++
        return DebugStat("network", "%.1f Mbps   avg %.1f".format(Locale.US, mbps, netTotal / netSamples))
    }

    private fun temperature(): DebugStat {
        val hottest = thermal().maxOrNull() ?: return DebugStat("thermal", UNAVAILABLE)
        val c = hottest / 1000.0
        val severity = when {
            c >= 95 -> DebugStatSeverity.DANGER
            c >= 85 -> DebugStatSeverity.WARNING
            else -> DebugStatSeverity.NORMAL
        }
        return DebugStat("thermal", "%.0f °C".format(Locale.US, c), severity)
    }

    companion object {
        const val UNAVAILABLE = "n/a"
        private const val MB = 1024L * 1024L

        /** (utime + stime ticks, major faults) from /proc/self/stat; the name field may hold spaces. */
        fun parseProcStat(stat: String): Pair<Long, Long>? = runCatching {
            val f = stat.substring(stat.lastIndexOf(')') + 1).trim().split(" ")
            (f[11].toLong() + f[12].toLong()) to f[9].toLong()
        }.getOrNull()

        /** VmRSS in kB from /proc/self/status. */
        fun parseRssKb(status: String): Long? =
            status.lineSequence().firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()

        /** Every thermal zone's temperature, in millidegrees. */
        fun thermalMillidegrees(): List<Int> = runCatching {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
                .mapNotNull { runCatching { File(it, "temp").readText().trim().toInt() }.getOrNull() }
                .filter { it in 1..150_000 }
        }.getOrDefault(emptyList())
    }
}
