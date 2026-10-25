package com.fetch.tv.core.streams

import com.fetch.tv.data.local.AddonTimeoutPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** What a guarded addon request came back with. */
sealed interface AddonCallResult<out T> {
    data class Completed<T>(val value: T) : AddonCallResult<T>
    data object TimedOut : AddonCallResult<Nothing>
}

/**
 * Gives every addon request (streams, catalogs, meta) the same budget and records how it went
 * in [AddonHealthTracker], so one dead addon can't stall a screen and shows up in its health.
 */
@Singleton
class AddonRequestGuard(
    private val tracker: AddonHealthTracker?,
    private val timeoutMs: () -> Long,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    @Inject
    constructor(tracker: AddonHealthTracker, preferences: AddonTimeoutPreferences) :
        this(tracker, { preferences.effectiveTimeoutMs })

    /**
     * Runs [block] within the addon timeout. [classify] decides what a finished response means
     * for the addon's health and [errorOf] what to show as its last error. An exception counts
     * as an error and is rethrown.
     */
    suspend fun <T> run(
        addonName: String,
        kind: AddonRequestKind,
        classify: (T) -> AddonRequestOutcome,
        errorOf: (T) -> String? = { null },
        block: suspend () -> T
    ): AddonCallResult<T> {
        val startedAt = clockMs()
        fun elapsed() = clockMs() - startedAt
        val value = try {
            withTimeoutOrNull(timeoutMs()) { Box(block()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracker?.record(addonName, AddonRequestOutcome.ERROR, elapsed(), e.message, kind)
            throw e
        }
        if (value == null) {
            tracker?.record(addonName, AddonRequestOutcome.TIMEOUT, elapsed(), null, kind)
            return AddonCallResult.TimedOut
        }
        val outcome = classify(value.value)
        tracker?.record(addonName, outcome, elapsed(), if (outcome.isFailure) errorOf(value.value) else null, kind)
        return AddonCallResult.Completed(value.value)
    }

    /** Wraps the result so a block that legitimately returns null isn't mistaken for a timeout. */
    private class Box<T>(val value: T)

    companion object {
        /** No budget and no tracking, for tests and callers built without Hilt. */
        val Unguarded = AddonRequestGuard(tracker = null, timeoutMs = { Long.MAX_VALUE })
    }
}
