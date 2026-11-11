package com.fetch.tv.baselineprofile

import android.util.Log
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to the first frame (timeToInitialDisplayMs) and to reportFullyDrawn, with and
 * without the baseline profile, for the low-end device baseline in docs/performance.md.
 * Run through `scripts/perf_baseline.sh benchmark`.
 *
 * The cold start is prepared here rather than with `StartupMode.COLD` (#461). Macrobenchmark's
 * own cold setup kills the app once and then fails the whole test if the process is alive a
 * moment later ("Package com.fetch.tv must not be running prior to cold start!"). On the CI
 * emulator that happened in one run out of two. This setup does the same steps in the same order
 * (shader cache, force-stop, page cache), then checks, and if the process came back it logs who
 * started it (the ActivityManager "Start proc" line) and kills it again. The startup metric reads
 * the trace as a cold start whenever no mode is given, so the numbers mean the same thing.
 * See docs/performance.md, "StartupBenchmark and a process that comes back".
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartWithoutProfile() = coldStart(CompilationMode.None())

    @Test
    fun coldStartWithBaselineProfile() =
        coldStart(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun coldStart(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        // Null: the cold start is prepared by prepareColdStart() below.
        startupMode = null,
        iterations = 10,
        setupBlock = { prepareColdStart() }
    ) {
        startActivityAndWait()
    }

    private fun MacrobenchmarkScope.prepareColdStart() {
        pressHome()
        // Before the kill: on user builds this is a broadcast to the app, which wakes it.
        dropShaderCache()
        killProcess()
        dropKernelPageCache()

        var attempt = 0
        while (isAppRunning()) {
            attempt++
            val cause = recentProcessStarts()
            Log.w(TAG, "$PACKAGE was running again after it was killed (attempt $attempt). $cause")
            check(attempt <= MAX_RE_KILLS) {
                "$PACKAGE keeps coming back after it is killed, so a cold start can't be " +
                    "measured. Last process start: $cause"
            }
            killProcess()
        }
    }

    /** The main process or any `:name` process of the app, as Macrobenchmark's own check. */
    private fun MacrobenchmarkScope.isAppRunning(): Boolean =
        device.executeShellCommand("ps -A -o NAME")
            .lineSequence()
            .map { it.trim() }
            .any { it == PACKAGE || it.startsWith("$PACKAGE:") }

    /** What started the process: ActivityManager logs "Start proc <pid>:<name>/<uid> for <why>". */
    private fun MacrobenchmarkScope.recentProcessStarts(): String =
        device.executeShellCommand("logcat -d -t 500 -s ActivityManager:I")
            .lineSequence()
            .filter { "Start proc" in it && PACKAGE in it }
            .toList()
            .takeLast(2)
            .joinToString(separator = " | ")
            .ifEmpty { "No ActivityManager \"Start proc\" line for $PACKAGE in the recent log." }

    private companion object {
        const val PACKAGE = "com.fetch.tv"
        const val TAG = "FetchBenchmark"
        const val MAX_RE_KILLS = 3
    }
}
