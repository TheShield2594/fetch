package com.fetch.tv.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendRateLimitTest {
    @Test
    fun retryAfterSupportsSecondsAndHttpDates() {
        assertEquals(30_000L, retryAfterDelayMillis("30", nowEpochMs = 0L))
        assertEquals(
            10_000L,
            retryAfterDelayMillis(
                retryAfterHeader = "Wed, 21 Oct 2015 07:28:00 GMT",
                nowEpochMs = 1_445_412_470_000L
            )
        )
        assertEquals(1_000L, retryAfterDelayMillis("invalid", nowEpochMs = 0L))
    }

    @Test
    fun retryDelayUsesBoundedFallbackAndAddsJitter() {
        assertEquals(1_250L, backendRetryDelayMillis(0, null, nowEpochMs = 0L, jitterMs = 250L))
        assertEquals(30_500L, backendRetryDelayMillis(10, null, nowEpochMs = 0L, jitterMs = 500L))
        assertEquals(60_250L, backendRetryDelayMillis(0, "60", nowEpochMs = 0L, jitterMs = 250L))
    }

    @Test
    fun automaticRetriesAreLimitedToReads() {
        assertTrue(isSafeBackendRetryRequest("GET", "/rest/v1/plugins"))
        assertTrue(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/sync_pull_library_delta"))
        assertTrue(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/get_my_member_access"))
        assertFalse(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/sync_push_watch_progress"))
        assertFalse(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/verify_profile_pin"))
        assertFalse(isSafeBackendRetryRequest("DELETE", "/rest/v1/library"))
    }

    @Test
    fun cooldownUsesRetryAfterForServiceUnavailableResponses() {
        assertTrue(shouldApplyBackendCooldown(429, null))
        assertTrue(shouldApplyBackendCooldown(503, "10"))
        assertFalse(shouldApplyBackendCooldown(503, null))
        assertFalse(shouldApplyBackendCooldown(500, "10"))
    }

    @Test
    fun coordinatorDelaysRequestsUntilLongestCooldownExpires() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val coordinator = BackendRateLimitCoordinator(
            currentTimeMillis = { 0L },
            monotonicMillis = { now },
            pause = { waitMs ->
                waits += waitMs
                now += waitMs
            }
        )

        coordinator.record("2")
        coordinator.record("5")
        coordinator.record("1")
        coordinator.awaitPermission()

        assertEquals(listOf(5_000L), waits)
    }

    @Test
    fun retryAfterIsCappedForHugeSecondsAndFarFutureDates() {
        assertEquals(MaximumRetryAfterMs, retryAfterDelayMillis("3600", nowEpochMs = 0L))
        assertEquals(MaximumRetryAfterMs, retryAfterDelayMillis("${Long.MAX_VALUE}", nowEpochMs = 0L))
        assertEquals(
            MaximumRetryAfterMs,
            retryAfterDelayMillis(
                retryAfterHeader = "Fri, 01 Jan 2100 00:00:00 GMT",
                nowEpochMs = 1_445_412_470_000L
            )
        )
        assertEquals(
            MaximumRetryAfterMs + 250L,
            backendRetryDelayMillis(0, "86400", nowEpochMs = 0L, jitterMs = 250L)
        )
    }

    @Test
    fun cooldownIgnoresWallClockJumps() = runBlocking {
        var wall = 1_000_000L
        var mono = 50L
        val waits = mutableListOf<Long>()
        val coordinator = BackendRateLimitCoordinator(
            currentTimeMillis = { wall },
            monotonicMillis = { mono },
            pause = { waitMs ->
                waits += waitMs
                mono += waitMs
            }
        )

        coordinator.record("10")
        // A backwards NTP correction must not stretch the wait.
        wall -= 3_600_000L
        coordinator.awaitPermission()

        assertEquals(listOf(10_000L), waits)
    }

    @Test
    fun noCooldownBeforeAnyRateLimit() = runBlocking {
        val coordinator = BackendRateLimitCoordinator(
            monotonicMillis = { Long.MIN_VALUE + 5 },
            pause = { error("should not wait") }
        )
        coordinator.awaitPermission()
    }
}
