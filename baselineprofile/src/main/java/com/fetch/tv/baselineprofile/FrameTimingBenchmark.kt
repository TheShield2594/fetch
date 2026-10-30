package com.fetch.tv.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing (frameDurationCpuMs, frameOverrunMs) for Home scrolling and for opening a detail
 * page, with the baseline profile, for the frame-timing table in docs/performance.md. Each
 * iteration cold launches to Home outside the measured block, so only the interaction is
 * measured. Play → fetching panel → first frame needs real streams and is measured by hand with
 * `dumpsys gfxinfo`. Run through `scripts/perf_baseline.sh frames`.
 */
@RunWith(AndroidJUnit4::class)
class FrameTimingBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun homeScroll() = measureFrames { device.scrollHome() }

    @Test
    fun openDetail() = measureFrames {
        device.pressDPadCenter()
        device.waitForIdle()
        Thread.sleep(2_500)
    }

    private fun measureFrames(measureBlock: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            inEnglish { launchToHome() }
            // Move focus off the sidebar into the content grid.
            device.pressDPadRight()
            device.waitForIdle()
        },
        measureBlock = measureBlock
    )
}
