package com.fetch.desktop.player.autosync

import kotlin.math.abs
import kotlin.math.roundToLong

/** One subtitle line's timing, in ms. */
data class Cue(val startMs: Long, val endMs: Long, val text: String = "")

/**
 * Reads SubRip, WebVTT and ASS/SSA subtitles into [Cue]s, as the TV's AutoSyncSubtitleCueParser
 * does for the formats it supports. Anything else reads as no cues.
 */
object CueParser {
    private val srtTime = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val vttTime = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})\.(\d{1,3})""")
    private val assTime = Regex("""(\d+):(\d{2}):(\d{2})[.:](\d{1,2})""")

    fun parse(raw: String): List<Cue> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val cues = when {
            text.trimStart().startsWith("WEBVTT") -> parseVtt(text)
            text.contains("[Events]", ignoreCase = true) || text.contains("\nDialogue:") -> parseAss(text)
            else -> parseSrt(text)
        }
        return cues.filter { it.endMs > it.startMs && it.startMs >= 0 }.sortedBy { it.startMs }
    }

    private fun ms(h: String?, m: String, s: String, frac: String): Long {
        val fraction = when (frac.length) {
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return ((h?.toLongOrNull() ?: 0) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + fraction
    }

    private fun parseSrt(text: String): List<Cue> = text.split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.trim().lines()
        val index = lines.indexOfFirst { "-->" in it }
        if (index < 0) return@mapNotNull null
        val parts = lines[index].split("-->")
        val a = srtTime.find(parts[0]) ?: return@mapNotNull null
        val b = srtTime.find(parts.getOrNull(1) ?: return@mapNotNull null) ?: return@mapNotNull null
        Cue(
            ms(a.groupValues[1], a.groupValues[2], a.groupValues[3], a.groupValues[4]),
            ms(b.groupValues[1], b.groupValues[2], b.groupValues[3], b.groupValues[4]),
            lines.drop(index + 1).joinToString("\n")
        )
    }

    private fun parseVtt(text: String): List<Cue> = text.split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.trim().lines()
        val index = lines.indexOfFirst { "-->" in it }
        if (index < 0) return@mapNotNull null
        val parts = lines[index].split("-->")
        val a = vttTime.find(parts[0]) ?: return@mapNotNull null
        val b = vttTime.find(parts.getOrNull(1) ?: return@mapNotNull null) ?: return@mapNotNull null
        Cue(
            ms(a.groupValues[1].ifEmpty { null }, a.groupValues[2], a.groupValues[3], a.groupValues[4]),
            ms(b.groupValues[1].ifEmpty { null }, b.groupValues[2], b.groupValues[3], b.groupValues[4]),
            lines.drop(index + 1).joinToString("\n")
        )
    }

    private fun parseAss(text: String): List<Cue> {
        var format: List<String>? = null
        var inEvents = !text.contains("[Events]", ignoreCase = true)
        return text.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inEvents = trimmed.equals("[Events]", ignoreCase = true)
                return@mapNotNull null
            }
            if (inEvents && trimmed.startsWith("Format:", ignoreCase = true)) {
                format = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }
                return@mapNotNull null
            }
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) return@mapNotNull null
            val fields = format ?: listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
            val values = trimmed.substringAfter(':').split(',', limit = fields.size).map { it.trim() }
            val start = assTime.find(values.getOrNull(fields.indexOf("start")) ?: return@mapNotNull null) ?: return@mapNotNull null
            val end = assTime.find(values.getOrNull(fields.indexOf("end")) ?: return@mapNotNull null) ?: return@mapNotNull null
            val body = values.getOrNull(fields.indexOf("text")).orEmpty().replace(Regex("\\{[^}]*\\}"), "").replace("\\N", "\n")
            // Signs and karaoke without text are drawing commands, not dialogue.
            if (body.isBlank()) return@mapNotNull null
            Cue(
                ms(start.groupValues[1], start.groupValues[2], start.groupValues[3], start.groupValues[4].padEnd(2, '0')),
                ms(end.groupValues[1], end.groupValues[2], end.groupValues[3], end.groupValues[4].padEnd(2, '0')),
                body
            )
        }
    }
}

/** What the aligner found: shift the subtitle by [offsetMs] (mpv's sub-delay, later when positive). */
data class AutoSyncResult(
    val offsetMs: Long,
    /** How well the shifted subtitle's speech matches the reference's, 0–1. */
    val score: Double,
    /** How far ahead of the best other shift that is. */
    val margin: Double,
    val confident: Boolean
)

/**
 * The TV's delay-only check (AutoSync V2's first stage), on the cues mpv showed so far of a
 * subtitle track inside the video: the shift of the addon subtitle whose speech lines up best with
 * the reference's over the stretch that played, applied only when it clearly beats every other.
 * Stretching and re-timing line by line, for subtitles made for another frame rate or cut, is
 * [AutoSyncRetimer]'s.
 */
object AutoSyncAligner {
    const val BIN_MS = 100L
    const val MAX_OFFSET_MS = 90_000L
    const val MIN_REFERENCE_CUES = 20
    const val MIN_REFERENCE_SPAN_MS = 45_000L
    private const val MIN_SCORE = 0.35
    private const val MIN_MARGIN = 0.06
    /** Shifts this close to the best are the same peak, not a competitor. */
    private const val PEAK_WIDTH_MS = 1_200L

    /** Whether [reference] has enough speech to judge by (the TV's live-reference minimums). */
    fun enoughReference(reference: List<Cue>): Boolean =
        reference.size >= MIN_REFERENCE_CUES && (reference.last().endMs - reference.first().startMs) >= MIN_REFERENCE_SPAN_MS

    fun align(reference: List<Cue>, target: List<Cue>, maxOffsetMs: Long = MAX_OFFSET_MS): AutoSyncResult? {
        if (reference.isEmpty() || target.isEmpty()) return null
        val windowStart = reference.minOf { it.startMs }
        val windowEnd = reference.maxOf { it.endMs }
        val bins = ((windowEnd - windowStart) / BIN_MS + 1).toInt()
        if (bins < 10) return null
        val ref = BooleanArray(bins)
        reference.forEach { mark(ref, it, windowStart, 0) }
        val refActive = ref.count { it }.takeIf { it > 0 } ?: return null
        val maxShift = (maxOffsetMs / BIN_MS).toInt()
        val scores = DoubleArray(2 * maxShift + 1)
        val shifted = BooleanArray(bins)
        for (k in -maxShift..maxShift) {
            java.util.Arrays.fill(shifted, false)
            val shiftMs = k * BIN_MS
            target.forEach { cue ->
                if (cue.endMs + shiftMs >= windowStart && cue.startMs + shiftMs <= windowEnd) mark(shifted, cue, windowStart, shiftMs)
            }
            var both = 0
            var either = 0
            for (i in 0 until bins) {
                if (ref[i] && shifted[i]) both++
                if (ref[i] || shifted[i]) either++
            }
            scores[k + maxShift] = if (either == 0) 0.0 else both.toDouble() / either
        }
        val bestIndex = scores.indices.maxByOrNull { scores[it] } ?: return null
        val best = scores[bestIndex]
        val peak = (PEAK_WIDTH_MS / BIN_MS).toInt()
        val second = scores.indices.filter { abs(it - bestIndex) > peak }.maxOfOrNull { scores[it] } ?: 0.0
        val coarse = (bestIndex - maxShift) * BIN_MS
        val refined = refine(reference, target, coarse)
        val margin = best - second
        return AutoSyncResult(refined, best, margin, confident = best >= MIN_SCORE && margin >= MIN_MARGIN && refActive >= 10)
    }

    /** The exact best shift within a bin either side of [coarseMs], in 10 ms steps, by overlap time. */
    private fun refine(reference: List<Cue>, target: List<Cue>, coarseMs: Long): Long =
        (-10..10).map { coarseMs + it * 10L }.maxByOrNull { overlap(reference, target, it) } ?: coarseMs

    /** Total time the reference's cues overlap [target]'s shifted by [shiftMs]. */
    fun overlap(reference: List<Cue>, target: List<Cue>, shiftMs: Long): Long {
        var total = 0L
        var j = 0
        val t = target.sortedBy { it.startMs }
        for (r in reference.sortedBy { it.startMs }) {
            while (j < t.size && t[j].endMs + shiftMs <= r.startMs) j++
            var k = j
            while (k < t.size && t[k].startMs + shiftMs < r.endMs) {
                total += (minOf(r.endMs, t[k].endMs + shiftMs) - maxOf(r.startMs, t[k].startMs + shiftMs)).coerceAtLeast(0)
                k++
            }
        }
        return total
    }

    private fun mark(bins: BooleanArray, cue: Cue, windowStart: Long, shiftMs: Long) {
        val from = ((cue.startMs + shiftMs - windowStart) / BIN_MS).toInt().coerceAtLeast(0)
        val to = ((cue.endMs + shiftMs - windowStart) / BIN_MS).toInt().coerceAtMost(bins.size - 1)
        for (i in from..to) bins[i] = true
    }

    /** The delay to apply for [result]: none when it's within [toleranceMs] (the TV's tolerance), or not confident. */
    fun delayToApply(result: AutoSyncResult?, toleranceMs: Int): Long? {
        if (result == null || !result.confident) return null
        if (toleranceMs > 0 && abs(result.offsetMs) <= toleranceMs) return 0
        return result.offsetMs
    }

    /** Two estimates agree when they're within [toleranceMs] of each other (the thorough search's check). */
    fun agree(a: AutoSyncResult?, b: AutoSyncResult?, toleranceMs: Long = 150): Boolean =
        a != null && b != null && a.confident && b.confident && abs(a.offsetMs - b.offsetMs) <= toleranceMs

    /** "+1.25 s" for an offset in ms. */
    fun label(offsetMs: Long): String = "%+.2f s".format(java.util.Locale.ROOT, offsetMs / 1000.0)
}

/**
 * Collects the reference track's cues as mpv shows them (its `secondary-sub-start` and
 * `secondary-sub-end`, the track kept hidden): one cue per new start time. [tick] records the
 * stretches of the video that played ([spans]), so a seek doesn't count the skipped part as a
 * stretch without speech.
 */
class LiveReference {
    private val cues = LinkedHashMap<Long, Cue>()
    private val spans = mutableListOf<Span>()
    private var lastPositionMs: Long? = null

    val size: Int get() = cues.size

    fun observe(startSec: Double?, endSec: Double?, text: String?) {
        if (startSec == null || endSec == null || text.isNullOrBlank()) return
        if (!startSec.isFinite() || !endSec.isFinite() || endSec <= startSec) return
        val start = (startSec * 1000).roundToLong()
        cues[start] = Cue(start, (endSec * 1000).roundToLong(), text)
    }

    /** Playback is at [positionMs]: extends the stretch that's playing, or starts one after a jump. */
    fun tick(positionMs: Long) {
        val last = lastPositionMs
        lastPositionMs = positionMs
        if (last != null && positionMs >= last && positionMs - last <= MAX_STEP_MS && spans.isNotEmpty() && spans.last().endMs == last) {
            spans[spans.lastIndex] = spans.last().copy(endMs = positionMs)
        } else {
            spans += Span(positionMs, positionMs)
        }
    }

    fun cues(): List<Cue> = cues.values.sortedBy { it.startMs }

    /** The stretches that played; empty when nothing was ticked (then the cues' own stretch counts). */
    fun spans(): List<Span> = spans.filter { it.lengthMs > 0 }.toList()

    fun clear() {
        cues.clear()
        spans.clear()
        lastPositionMs = null
    }

    private companion object {
        /** Further than this between two ticks is a seek. */
        const val MAX_STEP_MS = 5_000L
    }
}
