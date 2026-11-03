package com.fetch.desktop.player.autosync

import kotlin.math.abs
import kotlin.math.roundToLong

/** A stretch of the video the reference was read over, in ms: [startMs, endMs). */
data class Span(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * Where a subtitle's times go on the video's timeline: `scale × t + offset` (a constant shift,
 * or a stretch for another frame rate), bent through [anchors] where the subtitle was made for a
 * slightly different cut (a scene longer or shorter). Between anchors times are interpolated;
 * past the first and last they follow [scale] from the nearest one.
 */
data class TimeMap(val scale: Double, val offsetMs: Double, val anchors: List<Anchor> = emptyList()) {
    data class Anchor(val targetMs: Long, val referenceMs: Long)

    fun map(t: Long): Long {
        if (anchors.isEmpty()) return (scale * t + offsetMs).roundToLong()
        val first = anchors.first()
        val last = anchors.last()
        if (t <= first.targetMs) return first.referenceMs + (scale * (t - first.targetMs)).roundToLong()
        if (t >= last.targetMs) return last.referenceMs + (scale * (t - last.targetMs)).roundToLong()
        var low = 0
        var high = anchors.lastIndex
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (anchors[mid].targetMs <= t) low = mid else high = mid
        }
        val a = anchors[low]
        val b = anchors[high]
        val f = (t - a.targetMs).toDouble() / (b.targetMs - a.targetMs)
        return a.referenceMs + ((b.referenceMs - a.referenceMs) * f).roundToLong()
    }

    /** One shift for the whole subtitle, which mpv's sub-delay does without a new file. */
    val isConstantShift: Boolean get() = anchors.isEmpty() && abs(scale - 1.0) < SCALE_EPSILON

    /** The shift at [t]: how much later the subtitle shows there. */
    fun shiftAt(t: Long): Long = map(t) - t

    /** [cues] on the video's timeline; none get shorter than 100 ms or start before 0. */
    fun apply(cues: List<Cue>): List<Cue> = cues.map { cue ->
        val start = map(cue.startMs).coerceAtLeast(0)
        cue.copy(startMs = start, endMs = maxOf(map(cue.endMs), start + 100))
    }

    /** The largest difference between this map and [other] over [fromMs]..[toMs], sampled each second. */
    fun maxDifference(other: TimeMap, fromMs: Long, toMs: Long): Long {
        var worst = 0L
        var t = fromMs
        while (t <= toMs) {
            worst = maxOf(worst, abs(map(t) - other.map(t)))
            t += 1_000
        }
        return maxOf(worst, abs(map(toMs) - other.map(toMs)))
    }

    /** The largest shift over [fromMs]..[toMs]: for the tolerance ("leave it alone when this small"). */
    fun maxShift(fromMs: Long, toMs: Long): Long = maxDifference(IDENTITY, fromMs, toMs)

    companion object {
        const val SCALE_EPSILON = 0.0002
        val IDENTITY = TimeMap(1.0, 0.0)
        fun shift(offsetMs: Long) = TimeMap(1.0, offsetMs.toDouble())
    }
}

/** What [AutoSyncRetimer.retime] found. */
data class RetimeResult(
    val map: TimeMap,
    /** How well the retimed subtitle's speech matches the reference's where it was read, 0–1. */
    val score: Double,
    /** How far ahead of the best clearly different timing that is. */
    val margin: Double,
    /** Reference lines matched to a subtitle line, of those the subtitle could cover. */
    val matchedFraction: Double,
    val matchedLines: Int,
    /** The typical distance left between matched lines, ms. */
    val medianErrorMs: Long,
    val confident: Boolean
) {
    /** How good the match is, for picking between subtitles. */
    val quality: Double get() = score + margin + 0.25 * matchedFraction
}

/**
 * The TV's full automatic subtitle sync (AutoSyncTimelineRetime) for the desktop, in three
 * stages:
 *
 * 1. Speech activity: the subtitle's lines against the reference's, binned, over every offset
 *    within [MAX_OFFSET_MS] and a set of stretches (1, the common frame-rate ratios such as
 *    25 / 23.976, and a grid between [MIN_SCALE] and [MAX_SCALE]); the best overlap wins.
 * 2. A line-by-line fit: reference lines are paired with the subtitle's nearest line under that
 *    timing, and a straight line is fitted through the pairs (outliers dropped). A stretch close
 *    to a frame-rate ratio, or to none at all, snaps to it.
 * 3. Per-line re-timing: where the pairs sit consistently off the line (a cut that differs),
 *    the map bends through them ([TimeMap.anchors]).
 *
 * The reference is what was read of the video's own subtitles, over [Span]s of the video: the
 * stretch that played (a text track mpv shows), or sampled stretches of the whole video (an
 * image track's timings).
 */
object AutoSyncRetimer {
    const val MAX_OFFSET_MS = 90_000L
    const val MIN_SCALE = 0.94
    const val MAX_SCALE = 1.06
    private const val COARSE_BIN_MS = 200L
    private const val FINE_BIN_MS = 100L
    private const val GRID_STEP = 0.0025
    /** Two timings this far apart somewhere are different answers, not the same peak. */
    private const val DISTINCT_TRANSFORM_MS = 3_000L
    private const val MATCH_TOLERANCE_MS = 1_500L
    private const val FINE_MATCH_TOLERANCE_MS = 700L
    /** Local corrections smaller than this stay on the fitted line. */
    private const val LOCAL_SHIFT_MIN_MS = 300L
    private const val LOCAL_SMOOTH_RADIUS = 4
    private const val MIN_PAIRS = 12
    private const val MIN_SCORE = 0.45
    private const val MIN_MARGIN = 0.04
    private const val MIN_MATCHED_FRACTION = 0.5
    private const val MAX_MEDIAN_ERROR_MS = 300L

    /** Video and subtitle frame rates that differ: PAL speed-up and NTSC pull-down. */
    private val frameRates = listOf(23.976, 24.0, 25.0, 29.97, 30.0)

    /** Stretches that come from frame rates (and none), which a fit close to them snaps to. */
    val plausibleScales: List<Double> = buildList {
        add(1.0)
        for (a in frameRates) for (b in frameRates) if (a != b) add(a / b)
    }.filter { it in MIN_SCALE..MAX_SCALE }.distinctBy { (it * 10_000).roundToLong() }.sorted()

    private fun candidateScales(spanMs: Long): List<Double> {
        val grid = generateSequence(MIN_SCALE) { it + GRID_STEP }.takeWhile { it <= MAX_SCALE + 1e-9 }.toList()
        // Over a long stretch the grid is too coarse to line up by itself; the fit refines it.
        return (plausibleScales + if (spanMs <= 40 * 60_000L) grid else emptyList())
            .sorted()
            .fold(mutableListOf<Double>()) { kept, s -> kept.apply { if (none { abs(it - s) < 0.0004 }) add(s) } }
    }

    /** [spans] merged and sorted; with none, the reference's own stretch. */
    fun normalize(spans: List<Span>, reference: List<Cue>): List<Span> {
        val source = spans.filter { it.endMs > it.startMs }.ifEmpty {
            if (reference.isEmpty()) return emptyList()
            listOf(Span(reference.minOf { it.startMs }, reference.maxOf { it.endMs }))
        }.sortedBy { it.startMs }
        val merged = mutableListOf<Span>()
        for (s in source) {
            val last = merged.lastOrNull()
            if (last != null && s.startMs <= last.endMs) merged[merged.lastIndex] = Span(last.startMs, maxOf(last.endMs, s.endMs))
            else merged += s
        }
        return merged
    }

    fun retime(reference: List<Cue>, coverage: List<Span>, target: List<Cue>, maxOffsetMs: Long = MAX_OFFSET_MS): RetimeResult? {
        val spans = normalize(coverage, reference)
        if (spans.isEmpty() || target.isEmpty()) return null
        val ref = reference.filter { cue -> spans.any { cue.endMs > it.startMs && cue.startMs < it.endMs } }.sortedBy { it.startMs }
        if (ref.size < MIN_PAIRS) return null
        val tgt = target.sortedBy { it.startMs }

        // 1. Speech activity over offsets and stretches.
        val activity = ActivityGrid(ref, spans, COARSE_BIN_MS)
        val spanMs = spans.last().endMs - spans.first().startMs
        val peaks = candidateScales(spanMs).mapNotNull { s -> activity.best(tgt, s, maxOffsetMs)?.let { (offset, score) -> Peak(s, offset, score) } }
        val best = peaks.maxByOrNull { it.score } ?: return null
        // The same stretch's next best offset is a competitor too.
        val sameScaleSecond = activity.secondBest(tgt, best.scale, maxOffsetMs, best.offsetMs, DISTINCT_TRANSFORM_MS)
        val competitors = peaks.filter { distinct(it, best, spans) }.map { it.score } + listOfNotNull(sameScaleSecond)
        val margin = best.score - (competitors.maxOrNull() ?: 0.0)

        // 2. Pair lines and fit, twice, the second time tighter.
        var map = TimeMap(best.scale, best.offsetMs.toDouble())
        var pairs = emptyList<Pair<Long, Long>>()
        for (tolerance in listOf(MATCH_TOLERANCE_MS, FINE_MATCH_TOLERANCE_MS)) {
            pairs = match(ref, tgt, map, tolerance)
            if (pairs.size < MIN_PAIRS) break
            val fitted = fit(pairs, best.scale) ?: break
            map = fitted
        }
        if (pairs.size < MIN_PAIRS) return result(map, ref, spans, tgt, margin, pairs, ref.size)

        // 3. Per-line corrections where the pairs sit off the line in a run, including stretches
        // too far off to have paired at all (a scene longer or shorter in the subtitle's cut).
        pairs = (pairs + localPairs(ref, tgt, map, pairs)).sortedBy { it.first }
        map = withLocalCorrections(map, pairs)
        val coverable = ref.count { r -> tgt.any { abs(map.map(it.startMs) - r.startMs) <= MATCH_TOLERANCE_MS * 2 } }.coerceAtLeast(1)
        return result(map, ref, spans, tgt, margin, match(ref, tgt, map, FINE_MATCH_TOLERANCE_MS), coverable)
    }

    private data class Peak(val scale: Double, val offsetMs: Long, val score: Double)

    private fun distinct(a: Peak, b: Peak, spans: List<Span>): Boolean {
        val from = spans.first().startMs
        val to = spans.last().endMs
        // Compare where the subtitle's lines would land: the inverse of each map at both ends.
        fun at(p: Peak, t: Long) = (t - p.offsetMs) / p.scale
        return abs(at(a, from) - at(b, from)) > DISTINCT_TRANSFORM_MS || abs(at(a, to) - at(b, to)) > DISTINCT_TRANSFORM_MS
    }

    private fun result(map: TimeMap, ref: List<Cue>, spans: List<Span>, tgt: List<Cue>, margin: Double, pairs: List<Pair<Long, Long>>, coverable: Int): RetimeResult {
        val score = ActivityGrid(ref, spans, FINE_BIN_MS).score(map.apply(tgt))
        val errors = pairs.map { (t, r) -> abs(map.map(t) - r) }.sorted()
        val median = errors.getOrNull(errors.size / 2) ?: Long.MAX_VALUE
        val fraction = pairs.size.toDouble() / coverable.coerceAtLeast(ref.size / 2).coerceAtLeast(1)
        val confident = pairs.size >= MIN_PAIRS && score >= MIN_SCORE && margin >= MIN_MARGIN &&
            fraction >= MIN_MATCHED_FRACTION && median <= MAX_MEDIAN_ERROR_MS
        return RetimeResult(map, score, margin, fraction.coerceAtMost(1.0), pairs.size, median, confident)
    }

    /**
     * Each reference line with the subtitle line that lands nearest its start under [map]
     * (within [toleranceMs], of a similar length), each subtitle line used once: (subtitle
     * start, reference start).
     */
    fun match(reference: List<Cue>, target: List<Cue>, map: TimeMap, toleranceMs: Long): List<Pair<Long, Long>> {
        val mapped = target.map { map.map(it.startMs) to map.map(it.endMs) }
        val starts = mapped.map { it.first }
        data class Candidate(val r: Int, val t: Int, val cost: Long)
        val candidates = mutableListOf<Candidate>()
        reference.forEachIndexed { ri, r ->
            var i = lowerBound(starts, r.startMs - toleranceMs)
            while (i < starts.size && starts[i] <= r.startMs + toleranceMs) {
                val (s, e) = mapped[i]
                val refLength = r.endMs - r.startMs
                val length = e - s
                if (abs(length - refLength) <= maxOf(1_200L, refLength * 6 / 10)) {
                    candidates += Candidate(ri, i, abs(s - r.startMs) + abs(e - r.endMs) / 2)
                }
                i++
            }
        }
        val usedR = BooleanArray(reference.size)
        val usedT = BooleanArray(target.size)
        val pairs = mutableListOf<Pair<Long, Long>>()
        for (c in candidates.sortedBy { it.cost }) {
            if (usedR[c.r] || usedT[c.t]) continue
            usedR[c.r] = true
            usedT[c.t] = true
            pairs += target[c.t].startMs to reference[c.r].startMs
        }
        return pairs.sortedBy { it.first }
    }

    /**
     * A straight line through [pairs] by least squares, dropping outliers. A stretch within its
     * uncertainty of a frame-rate ratio (or of [seedScale] over a short stretch) snaps to it, so
     * a little noise isn't carried across the whole film.
     */
    fun fit(pairs: List<Pair<Long, Long>>, seedScale: Double): TimeMap? {
        var points = pairs
        var scale = seedScale
        var offset = 0.0
        repeat(3) {
            if (points.size < MIN_PAIRS / 2) return null
            val (s, o) = leastSquares(points) ?: return null
            scale = s
            offset = o
            val residuals = points.map { (x, y) -> y - (scale * x + offset) }
            val mad = residuals.map { abs(it) }.sorted()[residuals.size / 2]
            val limit = maxOf(150.0, mad * 1.4826 * 3)
            points = points.filterIndexed { i, _ -> abs(residuals[i]) <= limit }
        }
        if (points.size < MIN_PAIRS / 2) return null
        val xs = points.map { it.first.toDouble() }
        val spreadMs = xs.max() - xs.min()
        // How far the stretch could be off: the residual spread over the fitted stretch's length.
        val residuals = points.map { (x, y) -> y - (scale * x + offset) }
        val sigma = kotlin.math.sqrt(residuals.sumOf { it * it } / residuals.size)
        val xMean = xs.average()
        val sxx = xs.sumOf { (it - xMean) * (it - xMean) }.coerceAtLeast(1.0)
        val scaleError = sigma / kotlin.math.sqrt(sxx)
        val snapTolerance = maxOf(3 * scaleError, 0.0003, if (spreadMs < 10 * 60_000) 0.0015 else 0.0)
        val snapTo = (plausibleScales + seedScale).minByOrNull { abs(it - scale) }?.takeIf { abs(it - scale) <= snapTolerance }
        if (snapTo != null) {
            scale = snapTo
            val shifts = points.map { (x, y) -> y - scale * x }.sorted()
            offset = shifts[shifts.size / 2]
        }
        return TimeMap(scale, offset)
    }

    private fun leastSquares(points: List<Pair<Long, Long>>): Pair<Double, Double>? {
        val n = points.size.toDouble()
        val mx = points.sumOf { it.first.toDouble() } / n
        val my = points.sumOf { it.second.toDouble() } / n
        var sxy = 0.0
        var sxx = 0.0
        for ((x, y) in points) {
            sxy += (x - mx) * (y - my)
            sxx += (x - mx) * (x - mx)
        }
        if (sxx <= 0.0) return null
        val scale = (sxy / sxx).coerceIn(MIN_SCALE, MAX_SCALE)
        return scale to (my - scale * mx)
    }

    /**
     * Pairs for runs of reference lines that [pairs] left out: each run of up to [LOCAL_RUN]
     * lines tries shifts within ±[LOCAL_SEARCH_MS] of [map], and one that lines most of them up
     * pairs them under it.
     */
    fun localPairs(reference: List<Cue>, target: List<Cue>, map: TimeMap, pairs: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val pairedRef = pairs.map { it.second }.toHashSet()
        val usedTarget = pairs.map { it.first }.toHashSet()
        val starts = target.map { map.map(it.startMs) }
        val runs = mutableListOf<MutableList<Cue>>()
        var current = mutableListOf<Cue>()
        for (r in reference) {
            if (r.startMs in pairedRef) {
                // A lone paired line inside a stretch doesn't end it.
                if (current.size >= 2) { runs += current; current = mutableListOf() }
                continue
            }
            current += r
            if (current.size == LOCAL_RUN) { runs += current; current = mutableListOf() }
        }
        if (current.isNotEmpty()) runs += current
        val out = mutableListOf<Pair<Long, Long>>()
        for (run in runs) {
            if (run.size < LOCAL_MIN_HITS) continue
            var bestShift = 0L
            var bestHits = 0
            var shift = -LOCAL_SEARCH_MS
            while (shift <= LOCAL_SEARCH_MS) {
                val hits = run.count { r ->
                    val i = lowerBound(starts, r.startMs - shift - LOCAL_HIT_MS)
                    i < starts.size && starts[i] <= r.startMs - shift + LOCAL_HIT_MS
                }
                if (hits > bestHits || (hits == bestHits && abs(shift) < abs(bestShift))) {
                    bestHits = hits
                    bestShift = shift
                }
                shift += 100
            }
            if (abs(bestShift) < LOCAL_SHIFT_MIN_MS || bestHits < maxOf(LOCAL_MIN_HITS, run.size * 6 / 10)) continue
            val shifted = if (map.anchors.isEmpty()) map.copy(offsetMs = map.offsetMs + bestShift) else continue
            match(run, target, shifted, FINE_MATCH_TOLERANCE_MS).filter { it.first !in usedTarget }.forEach {
                usedTarget += it.first
                out += it
            }
        }
        return out
    }

    private const val LOCAL_RUN = 16
    private const val LOCAL_MIN_HITS = 6
    private const val LOCAL_SEARCH_MS = 15_000L
    private const val LOCAL_HIT_MS = 250L

    /**
     * [map] bent through the pairs where they sit off it by more than [LOCAL_SHIFT_MIN_MS]
     * consistently (a running median over neighbouring lines, so one bad pair moves nothing).
     */
    fun withLocalCorrections(map: TimeMap, pairs: List<Pair<Long, Long>>): TimeMap {
        if (pairs.size < 2 * LOCAL_SMOOTH_RADIUS + 1) return map
        val residuals = pairs.map { (x, y) -> y - map.map(x) }
        val smoothed = residuals.indices.map { i ->
            val window = residuals.subList(maxOf(0, i - LOCAL_SMOOTH_RADIUS), minOf(residuals.size, i + LOCAL_SMOOTH_RADIUS + 1)).sorted()
            window[window.size / 2]
        }
        if (smoothed.none { abs(it) >= LOCAL_SHIFT_MIN_MS }) return map
        val anchors = mutableListOf<TimeMap.Anchor>()
        var lastShift: Long? = null
        smoothed.forEachIndexed { i, shift ->
            // Small wobbles round to the line; anchors only where the local shift changes.
            val local = if (abs(shift) < LOCAL_SHIFT_MIN_MS / 2) 0L else shift
            val x = pairs[i].first
            if (lastShift == null || abs(local - lastShift!!) >= 40 || i == smoothed.lastIndex) {
                if (anchors.isEmpty() || x > anchors.last().targetMs) anchors += TimeMap.Anchor(x, map.map(x) + local)
                lastShift = local
            }
        }
        return if (anchors.size >= 2) map.copy(anchors = anchors) else map
    }

    private fun lowerBound(sorted: List<Long>, value: Long): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] < value) low = mid + 1 else high = mid
        }
        return low
    }
}

/**
 * The reference's speech as bins over its [spans], to compare a subtitle with: the share of
 * bins with speech in either that have it in both (only bins inside the spans count, so the
 * subtitle isn't marked down for lines where nothing of the video was read).
 */
private class ActivityGrid(reference: List<Cue>, spans: List<Span>, private val binMs: Long) {
    private val start = spans.first().startMs
    private val bins = ((spans.last().endMs - start) / binMs + 1).toInt()
    private val ref = BooleanArray(bins)
    private val refActive: IntArray
    /** The spans as bin ranges, inclusive. */
    private val ranges: List<IntRange> = spans.map { ((it.startMs - start) / binMs).toInt()..((it.endMs - 1 - start) / binMs).toInt().coerceAtMost(bins - 1) }
    private val inSpan = BooleanArray(bins).also { mask -> ranges.forEach { r -> for (i in r) mask[i] = true } }

    init {
        reference.forEach { cue -> mark(ref, cue.startMs - start, cue.endMs - start, 0) }
        for (i in 0 until bins) if (!inSpan[i]) ref[i] = false
        refActive = (0 until bins).filter { ref[it] }.toIntArray()
    }

    private fun mark(array: BooleanArray, fromMs: Long, toMs: Long, base: Int) {
        if (toMs <= fromMs) return
        val from = (Math.floorDiv(fromMs, binMs) + base).coerceAtLeast(0)
        val to = (Math.floorDiv(toMs - 1, binMs) + base).coerceAtMost(array.size - 1L)
        for (i in from..to) array[i.toInt()] = true
    }

    /** Score of [cues] (already on the reference's timeline). */
    fun score(cues: List<Cue>): Double {
        if (refActive.isEmpty()) return 0.0
        val other = BooleanArray(bins)
        cues.forEach { mark(other, it.startMs - start, it.endMs - start, 0) }
        var both = 0
        var either = 0
        for (i in 0 until bins) {
            if (!inSpan[i]) continue
            if (ref[i] && other[i]) both++
            if (ref[i] || other[i]) either++
        }
        return if (either == 0) 0.0 else both.toDouble() / either
    }

    /** Every offset's score for [target] stretched by [scale], over ±[maxOffsetMs]. */
    private fun scores(target: List<Cue>, scale: Double, maxOffsetMs: Long): DoubleArray? {
        if (refActive.isEmpty()) return null
        val k = (maxOffsetMs / binMs).toInt()
        // The stretched subtitle in bins over the reference's stretch widened by the offset range.
        val tgt = BooleanArray(bins + 2 * k)
        target.forEach { cue ->
            mark(tgt, (scale * cue.startMs).roundToLong() - start, (scale * cue.endMs).roundToLong() - start, k)
        }
        val prefix = IntArray(tgt.size + 1)
        for (i in tgt.indices) prefix[i + 1] = prefix[i] + if (tgt[i]) 1 else 0
        val out = DoubleArray(2 * k + 1)
        for (shift in -k..k) {
            // Reference bin i sees the subtitle's bin i + k - shift.
            val base = k - shift
            var both = 0
            for (i in refActive) if (tgt[i + base]) both++
            var inside = 0
            for (r in ranges) inside += prefix[r.last + base + 1] - prefix[r.first + base]
            val either = refActive.size + inside - both
            out[shift + k] = if (either == 0) 0.0 else both.toDouble() / either
        }
        return out
    }

    /** The best offset (ms) and its score for [target] stretched by [scale]. */
    fun best(target: List<Cue>, scale: Double, maxOffsetMs: Long): Pair<Long, Double>? {
        val s = scores(target, scale, maxOffsetMs) ?: return null
        val k = (maxOffsetMs / binMs).toInt()
        val i = s.indices.maxByOrNull { s[it] } ?: return null
        return (i - k) * binMs to s[i]
    }

    /** The best score at offsets more than [awayMs] from [offsetMs]. */
    fun secondBest(target: List<Cue>, scale: Double, maxOffsetMs: Long, offsetMs: Long, awayMs: Long): Double? {
        val s = scores(target, scale, maxOffsetMs) ?: return null
        val k = (maxOffsetMs / binMs).toInt()
        return s.indices.filter { abs((it - k) * binMs - offsetMs) > awayMs }.maxOfOrNull { s[it] }
    }
}
