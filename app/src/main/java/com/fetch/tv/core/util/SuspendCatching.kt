package com.fetch.tv.core.util

import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for coroutine code. A plain [runCatching] also catches [CancellationException], so
 * a cancelled coroutine (a profile switched away, a screen closed) turns its cancellation into an
 * ordinary failure and carries on past the suspension point, still writing. This rethrows it.
 *
 * A [TimeoutCancellationException] from a `withTimeout` inside [block] is still a failure, as it
 * is with [runCatching]: it means the work took too long, not that the caller was cancelled.
 *
 * Inline, so [block] can call suspend functions when this is used in suspend code.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: TimeoutCancellationException) {
        Result.failure(error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

/** The receiver form of [runSuspendCatching], matching `receiver.runCatching { }`. */
inline fun <R, T> R.runSuspendCatching(block: R.() -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: TimeoutCancellationException) {
        Result.failure(error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
