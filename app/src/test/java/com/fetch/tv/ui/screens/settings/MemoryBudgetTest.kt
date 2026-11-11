@file:OptIn(UnstableApi::class)

package com.fetch.tv.ui.screens.settings

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryBudgetTest {

    @Test
    fun testTotalUsageMb() {
        // totalUsageMb(bufferMb, connectionCount, chunkSizeMb, parallelEnabled)
        // case 1: parallel disabled
        assertEquals(50, MemoryBudget.totalUsageMb(50, 4, 32, false))
        
        // case 2: parallel enabled
        // bufferCount(4) = 4 + 2 = 6
        // overhead = 6 * 32 = 192
        // total = 50 + 192 = 242
        assertEquals(242, MemoryBudget.totalUsageMb(50, 4, 32, true))
    }

    @Test
    fun testParallelOverheadMb() {
        // 3 parallel connections with 8 MB chunk size:
        // bufferCount(3) = 3 + 2 = 5 chunks * 8 MB = 40 MB
        assertEquals(40, MemoryBudget.parallelOverheadMb(connectionCount = 3, chunkSizeMb = 8))

        // 4 parallel connections with 8 MB chunks:
        // bufferCount(4) = 4 + 2 = 6 chunks * 8 MB = 48 MB
        assertEquals(48, MemoryBudget.parallelOverheadMb(connectionCount = 4, chunkSizeMb = 8))
    }

    @Test
    fun testGetUsageStatusNativeAutoMode() {
        // When safeLimitMb = 1000, warningLimitMb = 1250
        // 1. totalUsageMb <= safeLimitMb should be SAFE
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(500, 1000, 1250))
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(1000, 1000, 1250))
        
        // 2. totalUsageMb > safeLimitMb && totalUsageMb <= warningLimitMb should be WARNING
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1050, 1000, 1250))
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1200, 1000, 1250))
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1250, 1000, 1250))
        
        // 3. totalUsageMb > warningLimitMb should be DANGER
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1260, 1000, 1250))
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1500, 1000, 1250))
    }

    @Test
    fun testGetUsageStatusManualMode() {
        // When safeLimitMb = 1000, warningLimitMb = 1250
        // 1. totalUsageMb <= safeLimitMb should be SAFE
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(500, 1000, 1250))
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(1000, 1000, 1250))
        
        // 2. totalUsageMb > safeLimitMb && totalUsageMb <= warningLimitMb should be WARNING
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1050, 1000, 1250))
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1200, 1000, 1250))
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1250, 1000, 1250))
        
        // 3. totalUsageMb > warningLimitMb should be DANGER
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1260, 1000, 1250))
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1500, 1000, 1250))
    }

    @Test
    fun testMemoryBudgetEnforce() {
        // If we choose values well within budget, enforce should return them unchanged
        val withinBuffer = MemoryBudget.MIN_BUFFER_MB
        val withinChunk = MemoryBudget.MIN_CHUNK_MB
        val connections = 2
        val (adjBuf, adjChunk) = MemoryBudget.enforce(withinBuffer, withinChunk, connections)
        assertEquals(withinBuffer, adjBuf)
        assertEquals(withinChunk, adjChunk)
    }

    private val mb = 1024L * 1024L

    @Test
    fun playbackBudgetIsAShareOfMemoryAboveTheKillThreshold() {
        // 1 GB class device: 350 MB available, kill threshold 150 MB -> 200 MB spare -> 50 MB.
        assertEquals(50, MemoryBudget.playbackBudgetMb(400, 25, 350 * mb, 150 * mb, lowMemory = false))
    }

    @Test
    fun playbackBudgetNeverExceedsTheHeapBudget() {
        assertEquals(400, MemoryBudget.playbackBudgetMb(400, 25, 8_000 * mb, 200 * mb, lowMemory = false))
    }

    @Test
    fun playbackBudgetKeepsAFloorWhenMemoryIsShort() {
        assertEquals(25, MemoryBudget.playbackBudgetMb(400, 25, 160 * mb, 150 * mb, lowMemory = false))
        assertEquals(25, MemoryBudget.playbackBudgetMb(400, 25, 100 * mb, 150 * mb, lowMemory = false))
        assertEquals(25, MemoryBudget.playbackBudgetMb(400, 25, 4_000 * mb, 150 * mb, lowMemory = true))
    }

    @Test
    fun playbackBudgetFloorNeverExceedsTheHeapBudget() {
        assertEquals(20, MemoryBudget.playbackBudgetMb(20, 25, 100 * mb, 150 * mb, lowMemory = true))
    }

    @Test
    fun unknownMemoryStateKeepsTheHeapBudget() {
        assertEquals(400, MemoryBudget.playbackBudgetMb(400, 25, 0L, 0L, lowMemory = false))
    }

    @Test
    fun publicPlaybackBudgetCanGoBelowTheHeapFloorUnderPressure() {
        // Production floor: a 1 GB class device with 200 MB to spare gets 50 MB, not the heap floor.
        assertEquals(MemoryBudget.MIN_TARGET_BUFFER_MB, MemoryBudget.playbackBudgetMb(350 * mb, 150 * mb, lowMemory = false))
        assertEquals(MemoryBudget.MIN_TARGET_BUFFER_MB, MemoryBudget.playbackBudgetMb(4_000 * mb, 150 * mb, lowMemory = true))
    }

    @Test
    fun sessionChunksLeaveTheMinimumBuffer() {
        // 100 MB session, 4 connections (6 chunks): 16 MB chunks would take 96 MB; 12 MB leaves 28.
        assertEquals(12, MemoryBudget.sessionChunkMb(100, 4, 16))
        // Plenty of budget: the setting stands.
        assertEquals(16, MemoryBudget.sessionChunkMb(400, 4, 16))
        // Never below the minimum chunk.
        assertEquals(MemoryBudget.MIN_CHUNK_MB, MemoryBudget.sessionChunkMb(50, 4, 16))
    }

    @Test
    fun parallelIsOffWhenTheSmallestChunksCannotFit() {
        // 4 connections: 6 chunks of 8 MB plus the 25 MB buffer = 73 MB.
        assertEquals(false, MemoryBudget.parallelFits(50, 4))
        assertEquals(true, MemoryBudget.parallelFits(73, 4))
        // Two connections: 4 chunks of 8 MB plus 25 MB = 57 MB.
        assertEquals(false, MemoryBudget.parallelFits(50, 2))
        assertEquals(true, MemoryBudget.parallelFits(100, 2))
    }
}
