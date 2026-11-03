package com.fetch.desktop.player.autosync

/**
 * Writes a subtitle with its times moved by a [TimeMap], keeping everything else as it was
 * (the TV's AutoSyncSubtitleTextRetimer): SubRip and WebVTT timing lines, and the Start and End
 * of ASS/SSA Dialogue lines, so styling, positioning and notes survive. mpv then loads the
 * result as a file of its own.
 */
object SubtitleRetimeWriter {
    private val srtLine = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})(\s*-->\s*)(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val vttLine = Regex("""((?:\d{1,2}:)?\d{2}:\d{2}\.\d{1,3})(\s+-->\s+)((?:\d{1,2}:)?\d{2}:\d{2}\.\d{1,3})""")
    private val assTime = Regex("""(\d+):(\d{2}):(\d{2})[.:](\d{1,2})""")

    /** [raw] retimed, in its own format; null when it's none Fetch can write. */
    fun retime(raw: String, map: TimeMap): String? {
        val text = raw.removePrefix("﻿")
        return when {
            text.trimStart().startsWith("WEBVTT") -> retimeVtt(text, map)
            text.contains("[Events]", ignoreCase = true) || text.contains("\nDialogue:") -> retimeAss(text, map)
            srtLine.containsMatchIn(text) -> retimeSrt(text, map)
            else -> null
        }
    }

    private fun times(map: TimeMap, startMs: Long, endMs: Long): Pair<Long, Long> {
        val start = map.map(startMs).coerceAtLeast(0)
        return start to maxOf(map.map(endMs), start + 100)
    }

    private fun retimeSrt(text: String, map: TimeMap): String = srtLine.replace(text) { m ->
        val g = m.groupValues
        val (start, end) = times(map, ms(g[1], g[2], g[3], g[4]), ms(g[6], g[7], g[8], g[9]))
        srt(start) + g[5] + srt(end)
    }

    private fun retimeVtt(text: String, map: TimeMap): String = vttLine.replace(text) { m ->
        val (start, end) = times(map, vttMs(m.groupValues[1]), vttMs(m.groupValues[3]))
        vtt(start) + m.groupValues[2] + vtt(end)
    }

    private fun retimeAss(text: String, map: TimeMap): String {
        var format: List<String>? = null
        var inEvents = !text.contains("[Events]", ignoreCase = true)
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        return text.split(newline).joinToString(newline) { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[") -> {
                    inEvents = trimmed.equals("[Events]", ignoreCase = true)
                    line
                }
                inEvents && trimmed.startsWith("Format:", ignoreCase = true) -> {
                    format = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }
                    line
                }
                trimmed.startsWith("Dialogue:", ignoreCase = true) -> retimeDialogue(line, format, map)
                else -> line
            }
        }
    }

    private fun retimeDialogue(line: String, format: List<String>?, map: TimeMap): String {
        val fields = format ?: listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val head = line.substringBefore(':') + ":"
        val values = line.substringAfter(':').split(',', limit = fields.size).toMutableList()
        val si = fields.indexOf("start")
        val ei = fields.indexOf("end")
        if (si !in values.indices || ei !in values.indices) return line
        val a = assTime.find(values[si]) ?: return line
        val b = assTime.find(values[ei]) ?: return line
        val (start, end) = times(
            map,
            ms(a.groupValues[1], a.groupValues[2], a.groupValues[3], a.groupValues[4].padEnd(2, '0')),
            ms(b.groupValues[1], b.groupValues[2], b.groupValues[3], b.groupValues[4].padEnd(2, '0'))
        )
        values[si] = values[si].replaceRange(a.range, ass(start))
        values[ei] = values[ei].replaceRange(b.range, ass(end))
        return head + values.joinToString(",")
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val fraction = when (frac.length) {
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return ((h.toLongOrNull() ?: 0) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + fraction
    }

    private fun vttMs(time: String): Long {
        val parts = time.split(':')
        val (sec, frac) = parts.last().split('.').let { it[0] to it.getOrElse(1) { "0" } }
        val h = if (parts.size == 3) parts[0] else "0"
        val m = parts[parts.size - 2]
        return ms(h, m, sec, frac)
    }

    private fun split(t: Long): IntArray {
        val ms = t.coerceAtLeast(0)
        return intArrayOf((ms / 3_600_000).toInt(), (ms / 60_000 % 60).toInt(), (ms / 1000 % 60).toInt(), (ms % 1000).toInt())
    }

    private fun srt(t: Long) = split(t).let { (h, m, s, f) -> "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT, h, m, s, f) }
    private fun vtt(t: Long) = split(t).let { (h, m, s, f) -> "%02d:%02d:%02d.%03d".format(java.util.Locale.ROOT, h, m, s, f) }
    private fun ass(t: Long) = split(t).let { (h, m, s, f) -> "%d:%02d:%02d.%02d".format(java.util.Locale.ROOT, h, m, s, f / 10) }
}
