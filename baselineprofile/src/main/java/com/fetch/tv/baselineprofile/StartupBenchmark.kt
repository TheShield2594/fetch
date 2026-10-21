package com.fetch.tv.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
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
        packageName = "com.fetch.tv",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
    }
}
