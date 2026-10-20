package com.nuvio.tv.core.streams

import androidx.compose.runtime.Immutable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AddonRequestOutcome { SUCCESS, EMPTY, ERROR, TIMEOUT }

enum class AddonHealthStatus { HEALTHY, SLOW, FAILING, UNKNOWN }

@Immutable
data class AddonHealth(
    val addonName: String,
    val requests: Int = 0,
    val failures: Int = 0,
    val timeouts: Int = 0,
    /** Exponential moving average of response time for completed requests. */
    val averageLatencyMs: Long? = null,
    val lastLatencyMs: Long? = null,
    val lastOutcome: AddonRequestOutcome? = null,
    val lastError: String? = null,
    val lastUpdatedAtMs: Long = 0L
) {
    val status: AddonHealthStatus
        get() = when {
            requests == 0 -> AddonHealthStatus.UNKNOWN
            lastOutcome == AddonRequestOutcome.ERROR || lastOutcome == AddonRequestOutcome.TIMEOUT ->
                AddonHealthStatus.FAILING
            (averageLatencyMs ?: 0L) >= SLOW_THRESHOLD_MS -> AddonHealthStatus.SLOW
            else -> AddonHealthStatus.HEALTHY
        }

    companion object {
        const val SLOW_THRESHOLD_MS = 5_000L
    }
}

/**
 * In-memory record of how each addon behaves, so a dead or slow addon is visible in the
 * addon manager instead of silently making every stream search feel broken.
 * Nothing here leaves the device.
 */
@Singleton
class AddonHealthTracker @Inject constructor() {
    private val _health = MutableStateFlow<Map<String, AddonHealth>>(emptyMap())
    val health: StateFlow<Map<String, AddonHealth>> = _health.asStateFlow()

    fun record(
        addonName: String,
        outcome: AddonRequestOutcome,
        latencyMs: Long,
        error: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        _health.update { current ->
            val previous = current[addonName] ?: AddonHealth(addonName)
            val completed = outcome != AddonRequestOutcome.TIMEOUT
            val average = when {
                !completed -> previous.averageLatencyMs
                previous.averageLatencyMs == null -> latencyMs
                else -> (previous.averageLatencyMs * 0.7 + latencyMs * 0.3).toLong()
            }
            current + (addonName to previous.copy(
                requests = previous.requests + 1,
                failures = previous.failures + if (outcome == AddonRequestOutcome.ERROR) 1 else 0,
                timeouts = previous.timeouts + if (outcome == AddonRequestOutcome.TIMEOUT) 1 else 0,
                averageLatencyMs = average,
                lastLatencyMs = latencyMs,
                lastOutcome = outcome,
                lastError = error?.take(200),
                lastUpdatedAtMs = nowMs
            ))
        }
    }

    fun clear() {
        _health.value = emptyMap()
    }
}
