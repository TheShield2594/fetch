package com.fetch.tv.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchedOnTest {
    private val now = Instant.parse("2026-11-13T20:00:00Z").toEpochMilli()

    @Test
    fun `a release date is logged at noon UTC on that day`() {
        assertEquals(
            Instant.parse("2010-07-16T12:00:00Z").toEpochMilli(),
            watchedAtFor(WatchedWhen.ReleaseDate, "2010-07-16", now)
        )
        // A timestamped release keeps its day.
        assertEquals(
            Instant.parse("2019-03-01T12:00:00Z").toEpochMilli(),
            releaseWatchedAt("2019-03-01T02:00:00.000Z", now)
        )
    }

    @Test
    fun `a release still to come or missing has no date`() {
        assertNull(releaseWatchedAt("2027-01-01", now))
        assertNull(releaseWatchedAt(null, now))
        assertNull(releaseWatchedAt("", now))
        // And falls back to now when asked for anyway (an undated episode in a season).
        assertEquals(now, watchedAtFor(WatchedWhen.ReleaseDate, null, now))
    }

    @Test
    fun `a release today is not logged after now`() {
        val morning = Instant.parse("2026-11-13T08:00:00Z").toEpochMilli()
        assertEquals(morning, releaseWatchedAt("2026-11-13", morning))
    }

    @Test
    fun `a picked day is its local noon, and never in the future`() {
        val zone = ZoneId.of("America/Chicago")
        assertEquals(
            Instant.parse("2024-03-09T18:00:00Z").toEpochMilli(),
            watchedAtFor(WatchedWhen.On(LocalDate.of(2024, 3, 9)), null, now, zone)
        )
        assertEquals(now, watchedAtFor(WatchedWhen.On(LocalDate.of(2026, 11, 14)), null, now, zone))
    }

    @Test
    fun `an exact time is kept, unknown is now`() {
        val at = Instant.parse("2023-05-06T21:15:00Z").toEpochMilli()
        assertEquals(at, watchedAtFor(WatchedWhen.At(at), null, now))
        assertEquals(now, watchedAtFor(WatchedWhen.At(now + 60_000), null, now))
        assertEquals(now, watchedAtFor(WatchedWhen.Unknown, "2010-07-16", now))
    }
}
