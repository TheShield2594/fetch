package com.fetch.tv.core.streams

/**
 * The content-level meta id a video id belongs to: everything before its season/episode suffix.
 *
 *     tt1234567:1:5   → tt1234567
 *     mal:63375:1:5   → mal:63375
 *     kitsu:12345:2   → kitsu:12345
 *     tmdb:603        → tmdb:603
 *
 * Trailing numeric segments are dropped, but a prefixed id ("mal:", "kitsu:", "tmdb:") keeps at
 * least two segments and an IMDb-style or bare numeric id at least one. For "other" items the
 * video id usually is the content id and comes back unchanged.
 */
internal fun metaIdForVideoId(videoId: String): String {
    val parts = videoId.split(":")
    if (parts.size <= 1) return videoId
    val trailingNumericCount = parts.reversed().takeWhile { it.toIntOrNull() != null }.size
    val firstSegment = parts.first()
    val minSegments = if (firstSegment.startsWith("tt") || firstSegment.toIntOrNull() != null) 1 else 2
    val segmentsToDrop = trailingNumericCount.coerceAtMost((parts.size - minSegments).coerceAtLeast(0))
    return if (segmentsToDrop > 0) parts.dropLast(segmentsToDrop).joinToString(":") else videoId
}
