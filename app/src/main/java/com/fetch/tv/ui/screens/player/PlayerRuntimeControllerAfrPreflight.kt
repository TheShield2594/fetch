package com.fetch.tv.ui.screens.player

import android.os.Build
import android.util.Log
import com.fetch.tv.core.player.FrameRateUtils
import com.fetch.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * AFR preflight probe budgets.
 *
 * Keep user-facing wait short. Cold debrid/CDN TTFB is handled by a tiny Range warmup
 * inside the OkHttp probe (not by inflating these ceilings). The three probes race: OkHttp
 * starts first, NextLib and the extractor each start [AFR_PREFLIGHT_STAGE_HEAD_START_MS]
 * after the one before (or at once when every earlier probe has already failed), and the
 * first detection wins. The head start keeps the usual case, a quick OkHttp hit, to one
 * connection to the host.
 */
internal const val AFR_PREFLIGHT_OKHTTP_TIMEOUT_MS = 15_000L
internal const val AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS = 10_000L
internal const val AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS = 5_000L
internal const val AFR_PREFLIGHT_TOTAL_TIMEOUT_MS = 15_000L
internal const val AFR_PREFLIGHT_STAGE_HEAD_START_MS = 2_000L

private sealed interface ProbeEvent<out T> {
    class Settled<T>(val value: T?) : ProbeEvent<T>
    class HeadStartElapsed(val nextStage: Int) : ProbeEvent<Nothing>
}

/**
 * Runs [probes] in order with staggered starts and returns the first non-null result.
 * Probe `i + 1` starts [headStartMs] after probe `i`, or immediately once every started
 * probe has finished without a result. The losers are cancelled when a winner arrives.
 */
internal suspend fun <T : Any> firstNonNullStaggered(
    headStartMs: Long,
    probes: List<suspend () -> T?>
): T? {
    if (probes.isEmpty()) return null
    return coroutineScope {
        val events = Channel<ProbeEvent<T>>(Channel.UNLIMITED)
        var started = 0
        var settled = 0
        var winner: T? = null

        fun startNext() {
            val probe = probes[started++]
            launch { events.send(ProbeEvent.Settled(probe())) }
            if (started < probes.size) {
                val nextStage = started
                launch {
                    delay(headStartMs)
                    events.send(ProbeEvent.HeadStartElapsed(nextStage))
                }
            }
        }

        startNext()
        while (winner == null && settled < probes.size) {
            when (val event = events.receive()) {
                is ProbeEvent.HeadStartElapsed -> if (event.nextStage == started) startNext()
                is ProbeEvent.Settled -> {
                    settled++
                    winner = event.value
                    if (winner == null && settled == started && started < probes.size) startNext()
                }
            }
        }
        coroutineContext.cancelChildren()
        winner
    }
}

/**
 * Runs a blocking probe on IO in the controller scope and awaits it for at most [timeoutMs].
 * The blocking call may ignore cancellation, so it runs detached: a lost race or a timeout
 * returns at once instead of waiting for the download to give up. The probe job is cancelled
 * on the way out, which [block] can poll through its `isProbeCancelled` argument.
 */
private suspend fun <T : Any> PlayerRuntimeController.runDetachedProbe(
    name: String,
    timeoutMs: Long,
    block: (isProbeCancelled: () -> Boolean) -> T?
): T? {
    val probe = scope.async(Dispatchers.IO) {
        val probeJob = coroutineContext[Job]
        try {
            block { probeJob?.isActive != true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PlayerRuntimeController.TAG, "AFR preflight: $name probe failed", e)
            null
        }
    }
    return try {
        val result = withTimeoutOrNull(timeoutMs) { probe.await() }
        if (result != null) {
            Log.d(PlayerRuntimeController.TAG, "AFR preflight: $name probe succeeded: $result")
        }
        result
    } finally {
        probe.cancel()
    }
}

/** A stream switch or a player release: a preflight still running for the old stream stands down. */
internal fun PlayerRuntimeController.cancelAfrPreflight() {
    afrPreflightGate.cancel()
    _uiState.update { it.copy(afrProbeRunning = false) }
}

internal suspend fun PlayerRuntimeController.runAfrPreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean,
    mimeType: String? = null,
    preflightToken: Long = afrPreflightGate.token()
) {
    fun isStale() = !afrPreflightGate.isCurrent(preflightToken)
    if (isStale()) return
    mpvDelayStartAfterAfrSwitch = false

    if (frameRateMatchingMode == FrameRateMatchingMode.OFF) {
        _uiState.update {
            it.clearFrameRateDetection().copy(afrProbeRunning = false)
        }
        return
    }

    val activity = currentHostActivity()
    if (activity == null) {
        Log.w(PlayerRuntimeController.TAG, "AFR preflight skipped: host activity unavailable")
        return
    }

    if (_uiState.value.afrProbeRunning || _uiState.value.detectedFrameRateSource != null) {
        Log.d(PlayerRuntimeController.TAG, "AFR preflight: already running or completed, skipping duplicate execution")
        return
    }

    _uiState.update {
        it.clearFrameRateDetection().copy(afrProbeRunning = true)
    }

    // Original stream headers (without Range) – used for NextLib bypass decision.
    // If these contain auth/custom headers, NextLib is skipped (MediaInfoBuilder cannot forward them).
    val streamHeaders = FrameRateUtils.streamHeadersForAfrProbe(headers)
    // Extractor fallback headers – add Connection: close for proper connection teardown.
    val probeHeaders = FrameRateUtils.extractorProbeHeaders(headers)
    val effectiveMimeType = mimeType ?: currentStreamMimeType
    val filename = currentFilename
    try {
        val cached = FrameRateUtils.getCachedFrameRate(url, headers, filename)
        if (cached != null) {
            Log.d(PlayerRuntimeController.TAG, "AFR preflight: cache hit! Using cached FPS=${cached.snapped}")
            _uiState.update {
                it.copy(
                    detectedFrameRateRaw = cached.raw,
                    detectedFrameRate = cached.snapped,
                    detectedFrameRateSource = FrameRateSource.PROBE
                )
            }
            val prefer23976ProbeBias = cached.raw in 23.95f..23.999f
            val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
                activity = activity,
                detectedFps = cached.snapped,
                prefer23976Near24 = prefer23976ProbeBias
            )
            val initialDisplayModeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                withContext(Dispatchers.Main) {
                    activity.window?.decorView?.display?.mode?.modeId
                }
            } else {
                null
            }

            if (isStale()) return
            val result = FrameRateUtils.matchFrameRateAndWait(
                activity = activity,
                frameRate = targetFrameRate,
                videoWidth = cached.videoWidth,
                videoHeight = cached.videoHeight,
                resolutionMatchingEnabled = resolutionMatchingEnabled
            )
            if (isStale()) return

            if (result != null) {
                val switchedDisplayMode = initialDisplayModeId != null &&
                    initialDisplayModeId != result.appliedMode.modeId
                mpvDelayStartAfterAfrSwitch = switchedDisplayMode

                _uiState.update {
                    it.copy(
                        displayModeInfo = DisplayModeInfo(
                            width = result.appliedMode.physicalWidth,
                            height = result.appliedMode.physicalHeight,
                            refreshRate = result.appliedMode.refreshRate,
                            refreshMatched = result.matched
                        ),
                        showDisplayModeInfo = true
                    )
                }
            }
            return
        }

        val detection = withTimeoutOrNull(AFR_PREFLIGHT_TOTAL_TIMEOUT_MS) {
            firstNonNullStaggered<FrameRateUtils.FrameRateDetection>(
                headStartMs = AFR_PREFLIGHT_STAGE_HEAD_START_MS,
                probes = listOf(
                    {
                        runDetachedProbe("OkHttp", AFR_PREFLIGHT_OKHTTP_TIMEOUT_MS) { isProbeCancelled ->
                            FrameRateUtils.detectFrameRateWithOkHttpProbe(
                                context = context,
                                sourceUrl = url,
                                headers = streamHeaders,
                                mimeType = effectiveMimeType,
                                filename = filename,
                                isCancelled = { isProbeCancelled() || isStale() }
                            )
                        }
                    },
                    {
                        runDetachedProbe("NextLib", AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS) {
                            FrameRateUtils.detectFrameRateFromNextLib(
                                context = context,
                                sourceUrl = url,
                                headers = streamHeaders,
                                mimeType = effectiveMimeType,
                                filename = filename
                            )
                        }
                    },
                    {
                        runDetachedProbe("extractor", AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS) {
                            FrameRateUtils.detectFrameRateFromExtractor(
                                context = context,
                                sourceUrl = url,
                                headers = probeHeaders
                            )
                        }
                    }
                )
            )
        }
        if (isStale()) return

        if (detection == null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "AFR preflight probe timed out/failed (OkHttp, NextLib and extractor)"
            )
            return
        }

        FrameRateUtils.cacheFrameRate(url, headers, detection, currentFilename)
        if (isStale()) return

        _uiState.update {
            it.copy(
                detectedFrameRateRaw = detection.raw,
                detectedFrameRate = detection.snapped,
                detectedFrameRateSource = FrameRateSource.PROBE
            )
        }

        val prefer23976ProbeBias = detection.raw in 23.95f..23.999f
        val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
            activity = activity,
            detectedFps = detection.snapped,
            prefer23976Near24 = prefer23976ProbeBias
        )
        val initialDisplayModeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            withContext(Dispatchers.Main) {
                activity.window?.decorView?.display?.mode?.modeId
            }
        } else {
            null
        }

        if (isStale()) return
        val result = FrameRateUtils.matchFrameRateAndWait(
            activity = activity,
            frameRate = targetFrameRate,
            videoWidth = detection.videoWidth,
            videoHeight = detection.videoHeight,
            resolutionMatchingEnabled = resolutionMatchingEnabled
        )
        if (isStale()) return

        if (result != null) {
            val switchedDisplayMode = initialDisplayModeId != null &&
                initialDisplayModeId != result.appliedMode.modeId
            mpvDelayStartAfterAfrSwitch = switchedDisplayMode

            _uiState.update {
                it.copy(
                    displayModeInfo = DisplayModeInfo(
                        width = result.appliedMode.physicalWidth,
                        height = result.appliedMode.physicalHeight,
                        refreshRate = result.appliedMode.refreshRate,
                        refreshMatched = result.matched
                    ),
                    showDisplayModeInfo = true
                )
            }
        }
    } finally {
        // A switch or release already cleared the flag and a newer preflight may own it now.
        if (!isStale()) {
            withContext(NonCancellable) {
                _uiState.update { it.copy(afrProbeRunning = false) }
            }
        }
    }
}
