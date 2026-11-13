package com.fetch.tv.core.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * "Mark watched on…" — logging a watch at a time other than now, for something watched before it
 * was marked. Shared by the TV and desktop apps so both log the same instant for the same choice.
 */

/** When it was watched. */
sealed interface WatchedWhen {
    /** The release date — a film's, or each episode's air date. */
    data object ReleaseDate : WatchedWhen
    /** Watched, but nobody knows when. Ordered as now; the Fetch website records it as unknown. */
    data object Unknown : WatchedWhen
    /** A day, logged at its local noon (the TV, where picking a time with a remote isn't worth it). */
    data class On(val date: LocalDate) : WatchedWhen
    /** An exact instant (the desktop, which has a time field). */
    data class At(val epochMs: Long) : WatchedWhen
}

/**
 * The instant to log a watch at, for something released at [released].
 *
 * A day is logged at noon so that it reads back as the same calendar day in any time zone: a day
 * the viewer picked at their local noon, a release date at noon UTC (the date a release is listed
 * under). Nothing is logged in the future. A release date that is missing or still to come falls
 * back to [now] — the caller only offers it where it exists, and a season with a few undated
 * episodes logs those as now, as the website does.
 */
fun watchedAtFor(
    whenWatched: WatchedWhen,
    released: String?,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault()
): Long = when (whenWatched) {
    WatchedWhen.Unknown -> now
    is WatchedWhen.At -> whenWatched.epochMs.coerceAtMost(now)
    is WatchedWhen.On -> whenWatched.date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli().coerceAtMost(now)
    WatchedWhen.ReleaseDate -> releaseWatchedAt(released, now) ?: now
}

/** Noon UTC on the day [released] names, or null when it names none or hasn't come yet. */
fun releaseWatchedAt(released: String?, now: Long): Long? {
    val releasedAt = parseEpisodeReleaseInstant(released)?.toEpochMilli() ?: return null
    if (releasedAt > now) return null
    val day = parseEpisodeReleaseLocalDate(released) ?: return null
    return day.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC).toEpochMilli().coerceAtMost(now)
}
