package com.fetch.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateAwareLoadControlTest {

    private val segment = 64 * 1024

    private fun loadControl(allocator: DefaultAllocator, budgetBytes: Long, hardCeilingBytes: Long) =
        BitrateAwareLoadControl(
            minBufferMs = 50_000,
            maxBufferMs = 60_000,
            bufferForPlaybackMs = 5_000,
            bufferForPlaybackAfterRebufferMs = 3_000,
            prioritizeTimeOverSizeThresholds = true,
            backBufferDurationMs = 0,
            retainBackBufferFromKeyframe = true,
            budgetBytes = budgetBytes,
            allocator = allocator,
            hardCeilingBytes = hardCeilingBytes
        ).also {
            it.onPrepared(PlayerId.UNSET)
            it.onTracksSelected(params(bufferedUs = 0L), TrackGroupArray.EMPTY, emptyArray<ExoTrackSelection?>())
        }

    private fun params(bufferedUs: Long) = LoadControl.Parameters(
        PlayerId.UNSET, Timeline.EMPTY, MediaSource.MediaPeriodId(Any()),
        0L, bufferedUs, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET
    )

    private fun fill(allocator: DefaultAllocator, segments: Int): List<Allocation> =
        List(segments) { allocator.allocate() }

    @Test
    fun `time priority loads past the target until the heap ceiling`() {
        val allocator = DefaultAllocator(true, segment)
        val control = loadControl(allocator, budgetBytes = 4L * segment, hardCeilingBytes = 8L * segment)

        fill(allocator, 6)
        // Past the 4-segment target but under the minimum duration: still loading, as before.
        assertTrue(control.shouldContinueLoading(params(bufferedUs = 1_000_000)))
        assertFalse(control.shouldStartPlayback(params(bufferedUs = 1_000_000)))

        fill(allocator, 2)
        // At the ceiling: stop loading and let playback start on what is buffered.
        assertFalse(control.shouldContinueLoading(params(bufferedUs = 1_000_000)))
        assertTrue(control.shouldStartPlayback(params(bufferedUs = 1_000_000)))
    }

    @Test
    fun `a runtime budget override is also the ceiling`() {
        val allocator = DefaultAllocator(true, segment)
        val control = loadControl(allocator, budgetBytes = 4L * segment, hardCeilingBytes = 16L * segment)
        control.setBudgetBytesOverride(2L * segment)

        fill(allocator, 2)
        assertFalse(control.shouldContinueLoading(params(bufferedUs = 1_000_000)))
    }
}
