@file:OptIn(UnstableApi::class)

package com.fetch.tv.ui.screens.settings

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetTest {

    @Test
    fun testTotalUsageMb() {
        // totalUsageMb(bufferMb, connectionCount, chunkSizeMb, parallelEnabled)
        // case 1: parallel disabled
        assertEquals(50, MemoryBudget.totalUsageMb(50, 4, 32, false))
        
        // case 2: parallel enabled: the buffer plus every chunk the session holds
        assertEquals(50 + MemoryBudget.bufferCount(4) * 32, MemoryBudget.totalUsageMb(50, 4, 32, true))
    }

    @Test
    fun testParallelOverheadMb() {
        assertEquals(MemoryBudget.bufferCount(3) * 8, MemoryBudget.parallelOverheadMb(connectionCount = 3, chunkSizeMb = 8))
        assertEquals(MemoryBudget.bufferCount(4) * 8, MemoryBudget.parallelOverheadMb(connectionCount = 4, chunkSizeMb = 8))
    }

    @Test
    fun bufferCountIsWhatAParallelSessionRetains() {
        // The default depth (connections + 1), the session's slack over it, and the eviction slack.
        val slack = MemoryBudget.sessionSlackChunks + MemoryBudget.EVICTION_SLACK_CHUNKS
        assertEquals(4 + 1 + slack, MemoryBudget.bufferCount(4))
        assertEquals(2 + 1 + slack, MemoryBudget.bufferCount(2))
        assertEquals(MemoryBudget.bufferCount(4), MemoryBudget.retainedChunkCount(MemoryBudget.defaultPrefetchDepth(4)))
    }

    @Test
    fun prefetchDepthHasNoFloorAboveTheDefaultDepth() {
        // Low-RAM, 4 connections of 16 MB, a budget with no room for deeper prefetch: the default
        // depth, not the old floor of two chunks per connection (#735).
        assertEquals(5, MemoryBudget.prefetchDepthChunks(4, 16, safeNativeLimitMb = 100, reserveBufferMb = 50))
    }

    @Test
    fun prefetchDepthFitsWhatTheSessionRetains() {
        // 400 MB of room for 16 MB chunks is 25 chunks; the slack comes out of it.
        val slack = MemoryBudget.sessionSlackChunks + MemoryBudget.EVICTION_SLACK_CHUNKS
        val depth = MemoryBudget.prefetchDepthChunks(2, 16, safeNativeLimitMb = 450, reserveBufferMb = 50)
        assertEquals((25 - slack).coerceAtMost(8), depth)
        assertTrue(MemoryBudget.retainedChunkCount(depth) * 16 <= 400)
        // Plenty of room: capped at four chunks a connection.
        assertEquals(16, MemoryBudget.prefetchDepthChunks(4, 8, safeNativeLimitMb = 4_000, reserveBufferMb = 0))
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
        // A session that can't hold its chunks at 16 MB beside the minimum buffer gets smaller ones.
        val chunks = MemoryBudget.bufferCount(4)
        val budget = MemoryBudget.MIN_BUFFER_MB + chunks * 12
        assertEquals(12, MemoryBudget.sessionChunkMb(budget, 4, 16))
        // Plenty of budget: the setting stands.
        assertEquals(16, MemoryBudget.sessionChunkMb(400, 4, 16))
        // Never below the minimum chunk.
        assertEquals(MemoryBudget.MIN_CHUNK_MB, MemoryBudget.sessionChunkMb(50, 4, 16))
    }

    @Test
    fun parallelIsOffWhenTheSmallestChunksCannotFit() {
        // Every retained chunk at 8 MB plus the 25 MB buffer.
        val fits4 = MemoryBudget.MIN_BUFFER_MB + MemoryBudget.bufferCount(4) * MemoryBudget.MIN_CHUNK_MB
        assertEquals(false, MemoryBudget.parallelFits(fits4 - 1, 4))
        assertEquals(true, MemoryBudget.parallelFits(fits4, 4))
        val fits2 = MemoryBudget.MIN_BUFFER_MB + MemoryBudget.bufferCount(2) * MemoryBudget.MIN_CHUNK_MB
        assertEquals(false, MemoryBudget.parallelFits(50, 2))
        assertEquals(true, MemoryBudget.parallelFits(fits2, 2))
    }
}
