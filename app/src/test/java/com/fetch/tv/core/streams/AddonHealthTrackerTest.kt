package com.fetch.tv.core.streams

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddonHealthTrackerTest {

    private class FakeStore(var stored: Map<String, AddonHealth> = emptyMap()) : AddonHealthStore(context = io.mockk.mockk(relaxed = true)) {
        var saves = 0
        override suspend fun load(): Map<String, AddonHealth> = stored
        override suspend fun save(health: Map<String, AddonHealth>) {
            saves++
            stored = health
        }
    }

    private fun TestScope.tracker(store: FakeStore) =
        AddonHealthTracker(store, backgroundScope, saveDebounceMs = 1_000L).also { runCurrent() }

    private fun health(vararg samples: Pair<AddonRequestOutcome, Long>): AddonHealth =
        samples.foldIndexed(AddonHealth("A")) { index, acc, (outcome, latency) ->
            acc.plus(AddonHealthSample(outcome, latency, atMs = index.toLong()), error = "boom $index")
        }

    @Test
    fun `median ignores timeouts and failure rate counts errors and timeouts`() {
        val h = health(
            AddonRequestOutcome.SUCCESS to 100L,
            AddonRequestOutcome.SUCCESS to 300L,
            AddonRequestOutcome.EMPTY to 200L,
            AddonRequestOutcome.ERROR to 400L,
            AddonRequestOutcome.TIMEOUT to 25_000L
        )
        assertEquals(250L, h.p50LatencyMs)
        assertEquals(0.4f, h.failureRate, 0.0001f)
        assertEquals(1, h.failures)
        assertEquals(1, h.timeouts)
        assertEquals(AddonHealthStatus.FAILING, h.status)
    }

    @Test
    fun `an answer resets consecutive failures and is the last success`() {
        val failing = health(*Array(5) { AddonRequestOutcome.TIMEOUT to 25_000L })
        assertEquals(5, failing.consecutiveFailures)
        assertTrue(failing.shouldSuggestDisabling)
        assertNull(failing.lastSuccessAtMs)
        assertNull(failing.p50LatencyMs)

        val recovered = failing.plus(AddonHealthSample(AddonRequestOutcome.EMPTY, 120L, atMs = 99L), error = null)
        assertEquals(0, recovered.consecutiveFailures)
        assertFalse(recovered.shouldSuggestDisabling)
        assertEquals(99L, recovered.lastSuccessAtMs)
        assertEquals("boom 4", recovered.lastError)
    }

    @Test
    fun `window keeps only the latest samples`() {
        val h = health(*Array(AddonHealth.WINDOW_SIZE + 10) { AddonRequestOutcome.SUCCESS to it.toLong() })
        assertEquals(AddonHealth.WINDOW_SIZE, h.requests)
        assertEquals(10L, h.samples.first().latencyMs)
    }

    @Test
    fun `store round-trips health`() {
        val h = health(AddonRequestOutcome.SUCCESS to 100L, AddonRequestOutcome.ERROR to 50L)
            .plus(AddonHealthSample(AddonRequestOutcome.TIMEOUT, 9_000L, 7L, AddonRequestKind.META), error = null)
        val decoded = AddonHealthStore.decode(AddonHealthStore.encode(mapOf("A" to h)))
        assertEquals(h, decoded["A"])
    }

    @Test
    fun `store ignores garbage`() {
        assertTrue(AddonHealthStore.decode("not json").isEmpty())
    }

    @Test
    fun `tracker restores stored health and keeps adding to it`() = runTest {
        val store = FakeStore(mapOf("A" to health(AddonRequestOutcome.ERROR to 10L)))
        val tracker = tracker(store)
        assertEquals(1, tracker.health.value["A"]?.requests)

        tracker.record("A", AddonRequestOutcome.ERROR, 20L, error = "down", nowMs = 50L)
        val a = tracker.health.value.getValue("A")
        assertEquals(2, a.requests)
        assertEquals(2, a.consecutiveFailures)
        assertEquals("down", a.lastError)
    }

    @Test
    fun `saves are debounced`() = runTest {
        val store = FakeStore()
        val tracker = tracker(store)
        repeat(5) { tracker.record("A", AddonRequestOutcome.SUCCESS, 10L) }
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(0, store.saves)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, store.saves)
        assertEquals(5, store.stored.getValue("A").requests)
    }

    @Test
    fun `guard records a timeout`() = runTest {
        val store = FakeStore()
        val tracker = tracker(store)
        val guard = AddonRequestGuard(tracker, timeoutMs = { 1_000L })
        val result = guard.run<String>("A", AddonRequestKind.CATALOG, classify = { AddonRequestOutcome.SUCCESS }) {
            awaitCancellation()
        }
        assertSame(AddonCallResult.TimedOut, result)
        val sample = tracker.health.value.getValue("A").samples.single()
        assertEquals(AddonRequestOutcome.TIMEOUT, sample.outcome)
        assertEquals(AddonRequestKind.CATALOG, sample.kind)
    }

    @Test
    fun `guard classifies an answer and keeps a null value`() = runTest {
        val store = FakeStore()
        val tracker = tracker(store)
        val guard = AddonRequestGuard(tracker, timeoutMs = { 1_000L })
        val result = guard.run<String?>(
            "A",
            AddonRequestKind.META,
            classify = { if (it == null) AddonRequestOutcome.ERROR else AddonRequestOutcome.SUCCESS },
            errorOf = { "HTTP 500" }
        ) {
            delay(10L)
            null
        }
        assertEquals(AddonCallResult.Completed(null), result)
        val a = tracker.health.value.getValue("A")
        assertEquals(AddonRequestOutcome.ERROR, a.lastOutcome)
        assertEquals("HTTP 500", a.lastError)
    }

    @Test
    fun `guard records an exception and rethrows it`() = runTest {
        val store = FakeStore()
        val tracker = tracker(store)
        val guard = AddonRequestGuard(tracker, timeoutMs = { 1_000L })
        try {
            guard.run<String>("A", AddonRequestKind.STREAM, classify = { AddonRequestOutcome.SUCCESS }) {
                throw IllegalStateException("bad json")
            }
            fail("expected the exception")
        } catch (e: IllegalStateException) {
            assertEquals("bad json", e.message)
        }
        assertEquals("bad json", tracker.health.value.getValue("A").lastError)
    }

    @Test
    fun `guard does not record a cancelled request`() = runTest {
        val store = FakeStore()
        val tracker = tracker(store)
        val guard = AddonRequestGuard(tracker, timeoutMs = { 1_000L })
        try {
            guard.run<String>("A", AddonRequestKind.STREAM, classify = { AddonRequestOutcome.SUCCESS }) {
                throw CancellationException("left the screen")
            }
            fail("expected the cancellation")
        } catch (_: CancellationException) {
        }
        assertNull(tracker.health.value["A"])
    }
}
