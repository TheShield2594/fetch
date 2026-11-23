package com.fetch.tv.ui.screens.player

internal object MpvStartupWatchdogPolicy {
    const val POLL_INTERVAL_MS = 500L
    const val SURFACE_WAIT_MS = 8_000L
    const val IDLE_AFTER_LOAD_MS = 4_000L
    const val STALL_TIMEOUT_MS = 20_000L
    /**
     * Past this, a stream must keep its cache growing to be waited on: one with no growth for
     * [RECENT_GROWTH_WINDOW_MS] fails without waiting out the full [STALL_TIMEOUT_MS]. A stream
     * that is still buffering (a big remux on a slow link, a debrid link with a slow first byte)
     * is never cut off by this clock alone.
     */
    const val ABSOLUTE_TIMEOUT_MS = 30_000L
    const val RECENT_GROWTH_WINDOW_MS = 5_000L
    const val MIN_CACHE_GROWTH_SEC = 0.25

    val surfaceWaitTicks: Int = (SURFACE_WAIT_MS / POLL_INTERVAL_MS).toInt()
    val idleTicksLimit: Int = (IDLE_AFTER_LOAD_MS / POLL_INTERVAL_MS).toInt()
    val stallTicksLimit: Int = (STALL_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
    val absoluteTicksLimit: Int = (ABSOLUTE_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
    val recentGrowthTicks: Int = (RECENT_GROWTH_WINDOW_MS / POLL_INTERVAL_MS).toInt()

    enum class Action {
        Continue,
        SurfaceTimeout,
        IdleError,
        StallTimeout,
        AbsoluteTimeout,
    }

    data class Counters(
        val surfaceWaitTicks: Int = 0,
        val idleTicks: Int = 0,
        val stallTicks: Int = 0,
        val absoluteTicks: Int = 0,
    )

    data class Input(
        val enabled: Boolean,
        val waitingForSurface: Boolean,
        val idleActive: Boolean,
        val cacheProgressing: Boolean,
        val counters: Counters,
    )

    data class Step(
        val action: Action,
        val counters: Counters,
    )

    fun cacheIsProgressing(previousSec: Double, currentSec: Double): Boolean {
        return currentSec > previousSec + MIN_CACHE_GROWTH_SEC
    }

    /**
     * The cache level to measure the next poll's growth against. It only moves up once growth
     * counts, so a slow link that adds less than [MIN_CACHE_GROWTH_SEC] per poll still adds up
     * to progress over a few polls. A drop (a seek to the resume point) starts again from there.
     */
    fun nextCacheBaselineSec(baselineSec: Double, currentSec: Double): Double = when {
        currentSec < baselineSec -> currentSec
        cacheIsProgressing(baselineSec, currentSec) -> currentSec
        else -> baselineSec
    }

    fun step(input: Input): Step {
        if (!input.enabled) {
            return Step(Action.Continue, Counters())
        }
        if (input.waitingForSurface) {
            val next = input.counters.surfaceWaitTicks + 1
            if (next >= surfaceWaitTicks) {
                return Step(Action.SurfaceTimeout, Counters())
            }
            return Step(Action.Continue, Counters(surfaceWaitTicks = next))
        }

        val absolute = input.counters.absoluteTicks + 1
        if (input.idleActive) {
            val idle = input.counters.idleTicks + 1
            if (idle >= idleTicksLimit) {
                return Step(Action.IdleError, Counters())
            }
            if (absolute >= absoluteTicksLimit) {
                return Step(Action.AbsoluteTimeout, Counters())
            }
            return Step(
                Action.Continue,
                Counters(
                    idleTicks = idle,
                    stallTicks = input.counters.stallTicks,
                    absoluteTicks = absolute
                )
            )
        }

        val stall = if (input.cacheProgressing) 0 else input.counters.stallTicks + 1
        if (stall >= stallTicksLimit) {
            return Step(Action.StallTimeout, Counters())
        }
        if (absolute >= absoluteTicksLimit && stall >= recentGrowthTicks) {
            return Step(Action.AbsoluteTimeout, Counters())
        }
        return Step(
            Action.Continue,
            Counters(stallTicks = stall, absoluteTicks = absolute.coerceAtMost(absoluteTicksLimit))
        )
    }
}
