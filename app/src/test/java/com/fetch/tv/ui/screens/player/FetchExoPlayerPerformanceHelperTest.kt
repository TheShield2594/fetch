package com.fetch.tv.ui.screens.player

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FetchExoPlayerPerformanceHelperTest {

    private val gb = 1024L * 1024L * 1024L

    @Before
    fun setUp() {
        FetchExoPlayerPerformanceHelper.clearCache()
    }

    @Test
    fun `test default fallback values when RAM is zero or unknown`() {
        val helperSpy = spyk(FetchExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns 0L

        assertEquals("Unknown", helperSpy.getFriendlyRamLabel(context))
        assertEquals(200, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 1 GB RAM tier classification`() {
        val helperSpy = spyk(FetchExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 1GB physical RAM (reports ~900MB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (0.9 * gb).toLong()

        assertEquals("1 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(100, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 1_5 GB RAM tier classification`() {
        val helperSpy = spyk(FetchExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 1.5GB physical RAM (reports ~1.3GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (1.3 * gb).toLong()

        assertEquals("1.5 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(200, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 2 GB RAM tier classification`() {
        val helperSpy = spyk(FetchExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 2GB physical RAM (reports ~1.7GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (1.7 * gb).toLong()

        assertEquals("2 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(200, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 3 GB RAM tier classification`() {
        val helperSpy = spyk(FetchExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 3GB physical RAM (reports ~2.6GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (2.6 * gb).toLong()

        assertEquals("3 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(500, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 4 GB RAM tier classification`() {
        val helperSpy = spyk(FetchExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 4GB physical RAM (reports ~3.6GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (3.6 * gb).toLong()

        assertEquals("4 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(1000, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 6 GB RAM tier classification`() {
        val helperSpy = spyk(FetchExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 6GB physical RAM (reports ~5.4GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (5.4 * gb).toLong()

        assertEquals("6 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(1600, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 8 GB RAM tier classification`() {
        val helperSpy = spyk(FetchExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 8GB physical RAM (reports ~7.4GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (7.4 * gb).toLong()

        assertEquals("8 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(2000, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 12 GB RAM tier classification`() {
        val helperSpy = spyk(FetchExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 12GB physical RAM (reports ~11.0GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (11.0 * gb).toLong()

        assertEquals("12 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(2000, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 16 GB RAM tier classification`() {
        val helperSpy = spyk(FetchExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 16GB physical RAM (reports ~14.8GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (14.8 * gb).toLong()

        assertEquals("16 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(2000, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `getDevicePhysicalRamBytes asks the system once and then uses the cached value`() {
        val context = mockk<Context>()
        val activityManager = mockk<android.app.ActivityManager>()

        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        every { activityManager.getMemoryInfo(any()) } answers {
            val memInfo = firstArg<android.app.ActivityManager.MemoryInfo>()
            memInfo.totalMem = 4 * gb
        }

        FetchExoPlayerPerformanceHelper.clearCache()

        repeat(100) {
            assertEquals(4 * gb, FetchExoPlayerPerformanceHelper.getDevicePhysicalRamBytes(context))
        }
        verify(exactly = 1) { activityManager.getMemoryInfo(any()) }
    }

    @Test
    fun `test buildLoadControl deducts chunkOverheadMb from targetBufferSizeMb`() {
        val enabledField = FetchExoPlayerPerformanceHelper::class.java.getDeclaredField("enabled")
        enabledField.isAccessible = true
        enabledField.setBoolean(FetchExoPlayerPerformanceHelper, true)
        FetchExoPlayerPerformanceHelper.targetBufferSizeMb = 250

        // Overhead = 96 MB -> target should be 250 - 96 = 154 MB
        val loadControl = FetchExoPlayerPerformanceHelper.buildLoadControl(chunkOverheadMb = 96)
        val targetBufferBytesField = androidx.media3.exoplayer.DefaultLoadControl::class.java.getDeclaredField("targetBufferBytesOverwrite")
        targetBufferBytesField.isAccessible = true
        val targetBytes = targetBufferBytesField.getInt(loadControl)

        assertEquals(154 * 1024 * 1024, targetBytes)
    }

    @Test
    fun `test buildLoadControl respects MIN_BUFFER_MB floor`() {
        val enabledField = FetchExoPlayerPerformanceHelper::class.java.getDeclaredField("enabled")
        enabledField.isAccessible = true
        enabledField.setBoolean(FetchExoPlayerPerformanceHelper, true)
        FetchExoPlayerPerformanceHelper.targetBufferSizeMb = 50

        // Overhead = 100 MB -> 50 - 100 = -50 -> coerceAtLeast MIN_BUFFER_MB (25 MB)
        val loadControl = FetchExoPlayerPerformanceHelper.buildLoadControl(chunkOverheadMb = 100)
        val targetBufferBytesField = androidx.media3.exoplayer.DefaultLoadControl::class.java.getDeclaredField("targetBufferBytesOverwrite")
        targetBufferBytesField.isAccessible = true
        val targetBytes = targetBufferBytesField.getInt(loadControl)

        assertEquals(com.fetch.tv.ui.screens.settings.MemoryBudget.MIN_BUFFER_MB * 1024 * 1024, targetBytes)
    }
}
