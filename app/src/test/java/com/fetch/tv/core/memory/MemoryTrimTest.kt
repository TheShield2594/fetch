package com.fetch.tv.core.memory

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@Suppress("DEPRECATION")
class MemoryTrimTest {

    @Test
    fun foregroundLevelsReleaseNothing() {
        assertNull(MemoryTrim.stageFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertNull(MemoryTrim.stageFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertNull(MemoryTrim.stageFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
    }

    @Test
    fun uiHiddenAndBackgroundLevelsMapToStages() {
        assertEquals(MemoryTrim.Stage.UI_HIDDEN, MemoryTrim.stageFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(MemoryTrim.Stage.BACKGROUND, MemoryTrim.stageFor(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals(MemoryTrim.Stage.BACKGROUND, MemoryTrim.stageFor(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun registeredHoldersReceiveTheStage() {
        val seen = mutableListOf<MemoryTrim.Stage>()
        val holder = MemoryTrim.Trimmable { seen += it }
        MemoryTrim.register(holder)
        try {
            MemoryTrim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            MemoryTrim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertEquals(listOf(MemoryTrim.Stage.UI_HIDDEN), seen)
        } finally {
            MemoryTrim.unregister(holder)
        }
    }
}
