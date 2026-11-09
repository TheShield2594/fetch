package com.fetch.tv.core.lifecycle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppForegroundTest {

    @After
    fun reset() = AppForeground.set(false)

    @Test
    fun `awaitForeground waits until the app is on screen`() = runTest {
        AppForeground.set(false)
        var resumed = false
        val job = launch {
            AppForeground.awaitForeground()
            resumed = true
        }
        advanceUntilIdle()
        assertFalse(resumed)

        AppForeground.set(true)
        advanceUntilIdle()
        assertTrue(resumed)
        job.cancel()
    }

    @Test
    fun `awaitForeground returns at once in the foreground`() = runTest {
        AppForeground.set(true)
        AppForeground.awaitForeground()
        assertTrue(AppForeground.isForeground.value)
    }
}
