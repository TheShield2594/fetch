package com.fetch.tv.data.youtube

import kotlinx.serialization.Serializable

/** Where a YouTube video stopped in Fetch, to the millisecond. */
@Serializable
data class YouTubeResumePoint(
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long
)

/**
 * When a YouTube video resumes, and from where: Fetch's own saved place, or YouTube's progress for
 * it. Both apps keep each profile's [MAX_POINTS] most recent places.
 */
object YouTubeResume {
    const val MAX_POINTS = 300

    private const val MIN_RESUME_MS = 10_000L
    private const val MIN_PERCENT = 3
    private const val MAX_PERCENT = 95

    /**
     * Whether YouTube's progress ([watchedPercent], from the video's card) should win over the place
     * Fetch saved: the video was finished elsewhere, or watched clearly further on another device
     * since. Fetch's place stays when the two roughly agree, since it's exact to the second.
     */
    fun youTubeIsAhead(savedPositionMs: Long, savedDurationMs: Long, watchedPercent: Int?): Boolean {
        val percent = watchedPercent ?: return false
        if (percent > MAX_PERCENT) return true
        if (savedDurationMs <= 0L) return percent > 0
        val savedPercent = savedPositionMs * 100 / savedDurationMs
        return percent >= savedPercent + 5
    }

    /** [positionMs] when it's worth resuming from: past the first few seconds and short of the end. */
    fun resumableMs(positionMs: Long, durationMs: Long): Long? {
        if (durationMs <= 0L || positionMs <= 0L) return null
        if (positionMs < MIN_RESUME_MS || positionMs < durationMs * MIN_PERCENT / 100) return null
        if (positionMs > durationMs * MAX_PERCENT / 100) return null
        return positionMs
    }

    /**
     * YouTube's watched percentage when it's worth resuming from: nowhere when barely started or
     * nearly finished, since those start over, like YouTube's own apps.
     */
    fun resumablePercent(watchedPercent: Int?): Int? =
        watchedPercent?.takeIf { it in MIN_PERCENT..MAX_PERCENT }

    /** Where [resumablePercent] lands in a video [durationMs] long. */
    fun percentPositionMs(watchedPercent: Int?, durationMs: Long): Long? {
        if (durationMs <= 0L) return null
        val percent = resumablePercent(watchedPercent) ?: return null
        return durationMs * percent / 100
    }

    /**
     * Where a video [durationMs] long starts: Fetch's saved place ([savedPositionMs]) unless
     * YouTube's progress is clearly further on, else YouTube's percentage; null to start at 0:00.
     */
    fun startPositionMs(savedPositionMs: Long?, watchedPercent: Int?, durationMs: Long): Long? =
        savedPositionMs
            ?.takeUnless { youTubeIsAhead(it, durationMs, watchedPercent) }
            ?.let { resumableMs(it, durationMs) }
            ?: percentPositionMs(watchedPercent, durationMs)

    /** The length a tile shows ("4:13", "1:02:03") in milliseconds; null for "LIVE" or anything else. */
    fun durationLabelMs(label: String?): Long? {
        val parts = label?.trim()?.split(':')?.takeIf { it.size in 2..3 } ?: return null
        val numbers = parts.map { part -> part.toLongOrNull()?.takeIf { it >= 0 } ?: return null }
        if (numbers.drop(1).any { it >= 60 }) return null
        val seconds = numbers.fold(0L) { total, n -> total * 60 + n }
        return (seconds * 1000).takeIf { it > 0L }
    }

    /**
     * [entries] with [videoId]'s point moved to the front, or dropped when the video was barely
     * started or nearly finished: those start over, as on YouTube.
     */
    fun updatePoints(
        entries: List<YouTubeResumePoint>,
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long,
        max: Int = MAX_POINTS
    ): List<YouTubeResumePoint> {
        val others = entries.filterNot { it.videoId == videoId }
        val point = YouTubeResumePoint(videoId, positionMs, durationMs, nowMs)
        return if (resumableMs(positionMs, durationMs) != null) (listOf(point) + others).take(max) else others
    }
}
