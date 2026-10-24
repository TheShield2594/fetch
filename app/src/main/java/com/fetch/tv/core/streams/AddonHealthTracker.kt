package com.fetch.tv.core.streams

import androidx.compose.runtime.Immutable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AddonRequestOutcome { SUCCESS, EMPTY, ERROR, TIMEOUT }

enum class AddonRequestKind { STREAM, CATALOG, META }

enum class AddonHealthStatus { HEALTHY, SLOW, FAILING, UNKNOWN }

val AddonRequestOutcome.isFailure: Boolean
    get() = this == AddonRequestOutcome.ERROR || this == AddonRequestOutcome.TIMEOUT

@Immutable
data class AddonHealthSample(
    val outcome: AddonRequestOutcome,
    val latencyMs: Long,
    val atMs: Long,
    val kind: AddonRequestKind = AddonRequestKind.STREAM
)

/** One addon's recent requests: the last [WINDOW_SIZE] samples plus the last error and success. */
@Immutable
data class AddonHealth(
    val addonName: String,
    val samples: List<AddonHealthSample> = emptyList(),
    val lastError: String? = null,
    val lastErrorAtMs: Long? = null,
    val lastSuccessAtMs: Long? = null,
    /** Errors and timeouts since the last answer, across restarts. */
    val consecutiveFailures: Int = 0
) {
    val requests: Int get() = samples.size
    val failures: Int get() = samples.count { it.outcome == AddonRequestOutcome.ERROR }
    val timeouts: Int get() = samples.count { it.outcome == AddonRequestOutcome.TIMEOUT }
    val lastOutcome: AddonRequestOutcome? get() = samples.lastOrNull()?.outcome
    val lastLatencyMs: Long? get() = samples.lastOrNull()?.latencyMs
    val lastUpdatedAtMs: Long get() = samples.lastOrNull()?.atMs ?: 0L

    /** Median response time of requests that answered; timeouts only say "slower than the budget". */
    val p50LatencyMs: Long?
        get() {
            val answered = samples.filter { it.outcome != AddonRequestOutcome.TIMEOUT }
                .map { it.latencyMs }
                .sorted()
            if (answered.isEmpty()) return null
            val mid = answered.size / 2
            return if (answered.size % 2 == 1) answered[mid] else (answered[mid - 1] + answered[mid]) / 2
        }

    /** Share of the window that errored or timed out, 0..1. */
    val failureRate: Float
        get() = if (samples.isEmpty()) 0f else samples.count { it.outcome.isFailure }.toFloat() / samples.size

    val shouldSuggestDisabling: Boolean
        get() = consecutiveFailures >= SUGGEST_DISABLE_AFTER_FAILURES

    val status: AddonHealthStatus
        get() = when {
            requests == 0 -> AddonHealthStatus.UNKNOWN
            lastOutcome?.isFailure == true -> AddonHealthStatus.FAILING
            (p50LatencyMs ?: 0L) >= SLOW_THRESHOLD_MS -> AddonHealthStatus.SLOW
            else -> AddonHealthStatus.HEALTHY
        }

    fun plus(sample: AddonHealthSample, error: String?): AddonHealth = copy(
        samples = (samples + sample).takeLast(WINDOW_SIZE),
        lastError = if (sample.outcome.isFailure) error?.take(200) ?: lastError else lastError,
        lastErrorAtMs = if (sample.outcome.isFailure) sample.atMs else lastErrorAtMs,
        lastSuccessAtMs = if (sample.outcome.isFailure) lastSuccessAtMs else sample.atMs,
        consecutiveFailures = if (sample.outcome.isFailure) consecutiveFailures + 1 else 0
    )

    companion object {
        const val SLOW_THRESHOLD_MS = 5_000L
        const val WINDOW_SIZE = 50
        const val SUGGEST_DISABLE_AFTER_FAILURES = 5
    }
}

/** A request outcome as it happens, so a screen can react to the addon it is waiting on. */
data class AddonRequestEvent(
    val addonName: String,
    val kind: AddonRequestKind,
    val outcome: AddonRequestOutcome,
    val atMs: Long
)

/**
 * Record of how each addon behaves, so a dead or slow addon is visible in the addon manager
 * instead of silently making every search feel broken. Kept on the device across restarts;
 * nothing here leaves it.
 */
@Singleton
class AddonHealthTracker(
    private val store: AddonHealthStore,
    private val scope: CoroutineScope,
    private val saveDebounceMs: Long = SAVE_DEBOUNCE_MS
) {
    @Inject
    constructor(store: AddonHealthStore) : this(store, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val _health = MutableStateFlow<Map<String, AddonHealth>>(emptyMap())
    val health: StateFlow<Map<String, AddonHealth>> = _health.asStateFlow()

    private val _events = MutableSharedFlow<AddonRequestEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AddonRequestEvent> = _events.asSharedFlow()

    private val saveRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch {
            val stored = runCatching { store.load() }.getOrDefault(emptyMap())
            // Requests recorded before the load finished are newer than anything stored.
            _health.update { recorded ->
                (stored.keys + recorded.keys).associateWith { name ->
                    val old = stored[name]
                    val new = recorded[name]
                    when {
                        old == null -> new!!
                        new == null -> old
                        else -> new.samples
                            .fold(old) { acc, sample -> acc.plus(sample, error = null) }
                            .copy(lastError = new.lastError ?: old.lastError)
                    }
                }
            }
        }
        scope.launch {
            @OptIn(FlowPreview::class)
            saveRequests.debounce(saveDebounceMs).collect {
                runCatching { store.save(_health.value) }
            }
        }
    }

    fun record(
        addonName: String,
        outcome: AddonRequestOutcome,
        latencyMs: Long,
        error: String? = null,
        kind: AddonRequestKind = AddonRequestKind.STREAM,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val sample = AddonHealthSample(outcome, latencyMs, nowMs, kind)
        _health.update { current ->
            val previous = current[addonName] ?: AddonHealth(addonName)
            current + (addonName to previous.plus(sample, error))
        }
        _events.tryEmit(AddonRequestEvent(addonName, kind, outcome, nowMs))
        saveRequests.tryEmit(Unit)
    }

    fun clear() {
        _health.value = emptyMap()
        saveRequests.tryEmit(Unit)
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 3_000L
    }
}
