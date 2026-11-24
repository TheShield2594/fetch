package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvDemuxerCachePolicyTest {

    @Test
    fun noUserTarget_followsTheMemoryBudget() {
        val limits = MpvDemuxerCachePolicy.limits(userTargetMb = null, playbackBudgetMb = 400, allowAboveBudget = false)
        assertEquals(300, limits.forwardMb)
        assertEquals(100, limits.backMb)
    }

    @Test
    fun userTarget_isCappedByTheBudgetUnlessAllowed() {
        val capped = MpvDemuxerCachePolicy.limits(userTargetMb = 800, playbackBudgetMb = 200, allowAboveBudget = false)
        assertEquals(200, capped.forwardMb + capped.backMb)

        val allowed = MpvDemuxerCachePolicy.limits(userTargetMb = 800, playbackBudgetMb = 200, allowAboveBudget = true)
        assertEquals(800, allowed.forwardMb + allowed.backMb)
    }

    @Test
    fun smallUserTarget_isKeptWithinTheBudget() {
        val limits = MpvDemuxerCachePolicy.limits(userTargetMb = 100, playbackBudgetMb = 400, allowAboveBudget = false)
        assertEquals(75, limits.forwardMb)
        assertEquals(25, limits.backMb)
    }

    @Test
    fun lowMemoryBudget_dropsToTheFloorWithMostOfItForward() {
        val limits = MpvDemuxerCachePolicy.limits(userTargetMb = null, playbackBudgetMb = 10, allowAboveBudget = false)
        assertEquals(MpvDemuxerCachePolicy.MIN_TOTAL_MB, limits.forwardMb + limits.backMb)
        assertEquals(38, limits.forwardMb)
        assertEquals(12, limits.backMb)
    }

    @Test
    fun hugeTarget_isCappedAtTheMaximum() {
        val limits = MpvDemuxerCachePolicy.limits(userTargetMb = 4096, playbackBudgetMb = 300, allowAboveBudget = true)
        assertEquals(MpvDemuxerCachePolicy.MAX_TOTAL_MB, limits.forwardMb + limits.backMb)
        assertEquals(limits.forwardMb * 1024L * 1024L, limits.forwardBytes)
    }
}
