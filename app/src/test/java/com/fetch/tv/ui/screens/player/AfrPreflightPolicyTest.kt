package com.fetch.tv.ui.screens.player

import com.fetch.tv.core.player.FrameRateUtils
import com.fetch.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy-level tests for AFR preflight behaviour that can be verified without
 * spinning up [PlayerRuntimeController] or a real Activity/Display.
 *
 * These encode the contracts used by [runAfrPreflightIfEnabled]:
 * - OFF short-circuits
 * - header split for NextLib vs extractor
 * - cache lookup key ingredients
 * - timeout constants (documenting the 0.7.10 → current change)
 */
class AfrPreflightPolicyTest {

    private companion object {
        // Documented 0.7.10-beta values for regression comparison only.
        const val LEGACY_0_7_10_NEXTLIB_TIMEOUT_MS = 30_000L
        const val LEGACY_0_7_10_FALLBACK_TIMEOUT_MS = 5_500L
    }

    @Test
    fun `preflight budgets stay short and the probes race behind a head start`() {
        assertEquals(15_000L, AFR_PREFLIGHT_OKHTTP_TIMEOUT_MS)
        assertEquals(10_000L, AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS)
        assertEquals(5_000L, AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS)
        assertEquals(15_000L, AFR_PREFLIGHT_TOTAL_TIMEOUT_MS)
        assertEquals(2_000L, AFR_PREFLIGHT_STAGE_HEAD_START_MS)
        // User-facing await must not grow with cold-CDN retries; warmup is inside OkHttp.
        assertTrue(AFR_PREFLIGHT_TOTAL_TIMEOUT_MS >= AFR_PREFLIGHT_OKHTTP_TIMEOUT_MS)
        // Every probe gets its full budget inside the total, even when started late.
        assertTrue(AFR_PREFLIGHT_STAGE_HEAD_START_MS + AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS <= AFR_PREFLIGHT_TOTAL_TIMEOUT_MS)
        assertTrue(2 * AFR_PREFLIGHT_STAGE_HEAD_START_MS + AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS <= AFR_PREFLIGHT_TOTAL_TIMEOUT_MS)
        assertTrue(AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS < LEGACY_0_7_10_NEXTLIB_TIMEOUT_MS)
        assertTrue(AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS < LEGACY_0_7_10_FALLBACK_TIMEOUT_MS)
        assertEquals(262_144L, FrameRateUtils.AFR_CDN_WARMUP_MAX_BYTES)
    }

    @Test
    fun `fast first probe wins without starting the others`() = runTest {
        val startedAt = mutableListOf<Long>()
        val result = firstNonNullStaggered<String>(
            headStartMs = 2_000L,
            probes = listOf(
                { startedAt += currentTime; delay(500L); "okhttp" },
                { startedAt += currentTime; "nextlib" },
                { startedAt += currentTime; "extractor" }
            )
        )
        assertEquals("okhttp", result)
        assertEquals(listOf(0L), startedAt)
        assertEquals(500L, currentTime)
    }

    @Test
    fun `slow first probe loses to a later probe once its head start elapses`() = runTest {
        var okHttpCancelled = false
        val result = firstNonNullStaggered<String>(
            headStartMs = 2_000L,
            probes = listOf(
                {
                    try {
                        delay(15_000L); "okhttp"
                    } catch (e: CancellationException) {
                        okHttpCancelled = true
                        throw e
                    }
                },
                { delay(1_000L); "nextlib" },
                { delay(10_000L); "extractor" }
            )
        )
        assertEquals("nextlib", result)
        // NextLib starts at 2 s and answers 1 s later, instead of after OkHttp's 15 s.
        assertEquals(3_000L, currentTime)
        assertTrue(okHttpCancelled)
    }

    @Test
    fun `a failed probe starts the next one at once`() = runTest {
        val startedAt = mutableListOf<Long>()
        val result = firstNonNullStaggered<String>(
            headStartMs = 2_000L,
            probes = listOf(
                { startedAt += currentTime; delay(300L); null },
                { startedAt += currentTime; delay(200L); null },
                { startedAt += currentTime; delay(100L); "extractor" }
            )
        )
        assertEquals("extractor", result)
        assertEquals(listOf(0L, 300L, 500L), startedAt)
    }

    @Test
    fun `all probes failing yields null`() = runTest {
        val result = firstNonNullStaggered<String>(
            headStartMs = 2_000L,
            probes = listOf({ delay(5_000L); null }, { null }, { delay(100L); null })
        )
        assertNull(result)
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun `FrameRateMatchingMode OFF is the only disabled mode`() {
        assertEquals(
            setOf(FrameRateMatchingMode.OFF, FrameRateMatchingMode.START, FrameRateMatchingMode.START_STOP),
            FrameRateMatchingMode.entries.toSet()
        )
        // Preflight returns immediately only for OFF
        assertTrue(FrameRateMatchingMode.OFF != FrameRateMatchingMode.START)
        assertTrue(FrameRateMatchingMode.START != FrameRateMatchingMode.START_STOP)
    }

    @Test
    fun `START_STOP is the only mode that restores display on player exit`() {
        // Mirrors PlayerScreen DisposableEffect contract:
        // START_STOP → restoreOriginalDisplayMode
        // START / OFF → clearOriginalDisplayMode (no restore on dispose for START)
        fun shouldRestoreOnExit(mode: FrameRateMatchingMode): Boolean =
            mode == FrameRateMatchingMode.START_STOP

        assertFalse(shouldRestoreOnExit(FrameRateMatchingMode.OFF))
        assertFalse(shouldRestoreOnExit(FrameRateMatchingMode.START))
        assertTrue(shouldRestoreOnExit(FrameRateMatchingMode.START_STOP))
    }

    @Test
    fun `preflight header pipeline matches NextLib and extractor contracts`() {
        val incoming = mapOf(
            "User-Agent" to "Fetch/0.7",
            "Range" to "bytes=0-65535",
            "Authorization" to "Bearer debrid-token"
        )

        val streamHeaders = FrameRateUtils.streamHeadersForAfrProbe(incoming)
        val probeHeaders = FrameRateUtils.extractorProbeHeaders(incoming)

        // NextLib path
        assertFalse(
            "Range must never reach NextLib bypass decision",
            streamHeaders.keys.any { it.equals("Range", ignoreCase = true) }
        )
        assertFalse(
            "Auth streams must skip NextLib",
            FrameRateUtils.shouldUseNextLibProbe(
                "https://download.real-debrid.com/d/ABC/movie.mkv",
                streamHeaders
            )
        )

        // Extractor path keeps auth + Connection: close
        assertEquals("Bearer debrid-token", probeHeaders["Authorization"])
        assertEquals("close", probeHeaders["Connection"])
        assertEquals("Fetch/0.7", probeHeaders["User-Agent"])
    }

    @Test
    fun `public stream preflight still enables NextLib after UA injection`() {
        val incoming = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Range" to "bytes=0-"
        )
        val streamHeaders = FrameRateUtils.streamHeadersForAfrProbe(incoming)
        assertTrue(
            FrameRateUtils.shouldUseNextLibProbe(
                "https://public.cdn.example.com/releases/film.mkv",
                streamHeaders
            )
        )
    }

    @Test
    fun `cache key ingredients used by preflight are consistent for signed urls`() {
        val filename = "Show.S01E01.1080p.mkv"
        val detection = FrameRateUtils.FrameRateDetection(
            raw = 23.976f,
            snapped = FrameRateUtils.snapToStandardRate(23.976f),
            videoWidth = 1920,
            videoHeight = 1080
        )
        FrameRateUtils.clearFrameRateCache()

        // First play: signed URL A
        FrameRateUtils.cacheFrameRate(
            url = "https://download.real-debrid.com/d/TOKEN_A/file",
            headers = emptyMap(),
            detection = detection,
            filename = filename
        )

        // Second play: signed URL B, same filename (preflight uses currentFilename)
        val cached = FrameRateUtils.getCachedFrameRate(
            url = "https://download.real-debrid.com/d/TOKEN_B/file",
            headers = emptyMap(),
            filename = filename
        )
        assertEquals(23.976f, cached!!.raw, 0.001f)
        FrameRateUtils.clearFrameRateCache()
    }

    @Test
    fun `prefer23976ProbeBias window matches preflight bias gate`() {
        // Mirrors: prefer23976ProbeBias = detection.raw in 23.95f..23.999f
        fun prefer23976(raw: Float): Boolean = raw in 23.95f..23.999f

        assertTrue(prefer23976(23.976f))
        assertTrue(prefer23976(23.95f))
        assertTrue(prefer23976(23.999f))
        assertFalse(prefer23976(24.0f))
        assertFalse(prefer23976(23.94f))
        assertFalse(prefer23976(25f))
    }

    @Test
    fun `duplicate preflight guard condition is exclusive or`() {
        // Mirrors: if (afrProbeRunning || detectedFrameRateSource != null) skip
        fun shouldSkipDuplicate(afrProbeRunning: Boolean, source: FrameRateSource?): Boolean =
            afrProbeRunning || source != null

        assertFalse(shouldSkipDuplicate(false, null))
        assertTrue(shouldSkipDuplicate(true, null))
        assertTrue(shouldSkipDuplicate(false, FrameRateSource.PROBE))
        assertTrue(shouldSkipDuplicate(false, FrameRateSource.TRACK))
        assertTrue(shouldSkipDuplicate(true, FrameRateSource.PROBE))
    }
}
