package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalPlaybackCompletionRulesTest {

    @Test
    fun `real episode end is treated as natural completion`() {
        assertTrue(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 2_400_000L, // 40 min
                positionMs = 2_400_000L
            )
        )
    }

    @Test
    fun `short debrid placeholder is not natural completion`() {
        // Comet-style cache-sync / error clips are a few seconds long.
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 5_000L,
                positionMs = 5_000L
            )
        )
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 30_000L,
                positionMs = 30_000L
            )
        )
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 120_999L,
                positionMs = 120_999L
            )
        )
    }

    @Test
    fun `just over placeholder threshold is natural completion`() {
        assertTrue(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 121_000L,
                positionMs = 121_000L
            )
        )
    }

    @Test
    fun `no first frame is not natural completion`() {
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = false,
                hasFatalError = false,
                durationMs = 2_400_000L,
                positionMs = 2_400_000L
            )
        )
    }

    @Test
    fun `fatal error is not natural completion`() {
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = true,
                durationMs = 2_400_000L,
                positionMs = 2_400_000L
            )
        )
    }

    @Test
    fun `zero or unknown duration is still allowed when first frame rendered`() {
        // Match external-player behavior: missing duration is left to the normal path.
        assertTrue(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 0L,
                positionMs = 0L
            )
        )
    }

    @Test
    fun `stream ending well before its duration is not natural completion`() {
        // A dropped debrid connection hits EOF at 50%: this must not mark the episode watched.
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 2_400_000L,
                positionMs = 1_200_000L
            )
        )
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 2_400_000L,
                positionMs = 1_919_999L
            )
        )
    }

    @Test
    fun `stream ending past Trakt's watched threshold is natural completion`() {
        assertTrue(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 2_400_000L,
                positionMs = 1_920_000L
            )
        )
    }

    @Test
    fun `isShortPlaceholderDuration covers error and cache-sync clips`() {
        assertTrue(isShortPlaceholderDuration(1L))
        assertTrue(isShortPlaceholderDuration(5_000L))
        assertTrue(isShortPlaceholderDuration(120_999L))
        assertFalse(isShortPlaceholderDuration(0L))
        assertFalse(isShortPlaceholderDuration(121_000L))
        assertFalse(isShortPlaceholderDuration(2_400_000L))
    }
}
