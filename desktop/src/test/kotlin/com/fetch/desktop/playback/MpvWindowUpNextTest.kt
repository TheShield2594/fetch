package com.fetch.desktop.playback

import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.desktop.player.MpvProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvWindowUpNextTest {
    private val duration = 2_400.0 // 40 minutes; the 99 % threshold is at 2 376 s

    private class Calls {
        var preloads = 0
        var cancels = 0
    }

    private fun upNext(
        settings: AutoPlaySettings = AutoPlaySettings(),
        next: String? = "S1 E4 · The Next One",
        queue: List<String> = emptyList(),
        calls: Calls = Calls()
    ) = MpvWindowUpNext(
        settings = { settings }, segments = emptyList(), nextLabel = next, timeoutSeconds = { 5 },
        upcoming = { queue }, onPreload = { calls.preloads++ }, onCancelled = { calls.cancels++ }
    )

    @Test
    fun `the threshold counts down on mpv's OSD by playback time, then closes the window`() {
        val u = upNext()
        assertTrue(u.onTimes(100.0, duration).isEmpty())
        assertTrue("before the threshold", u.onTimes(2_370.0, duration).isEmpty())
        val first = u.onTimes(2_376.0, duration).single()
        assertTrue(first.contains("Up Next: S1 E4 · The Next One") && first.contains("Starting in 5 s"))
        assertTrue("the same second isn't shown twice", u.onTimes(2_376.4, duration).isEmpty())
        assertTrue(u.onTimes(2_377.1, duration).single().contains("Starting in 4 s"))
        assertFalse(u.movedOn)
        val last = u.onTimes(2_381.0, duration)
        assertEquals(MpvProgress.QUIT_COMMAND, last.last())
        assertTrue(u.movedOn)
        assertTrue("nothing more once it moved on", u.onTimes(2_382.0, duration).isEmpty())
    }

    @Test
    fun `starting near the end doesn't fire until the position was once away from it`() {
        val u = upNext()
        assertTrue(u.onTimes(2_390.0, duration).isEmpty())
        assertTrue(u.onTimes(2_000.0, duration).isEmpty())
        assertTrue(u.onTimes(2_380.0, duration).isNotEmpty())
    }

    @Test
    fun `Alt+N keeps watching, and a seek back waits for the threshold again`() {
        val calls = Calls()
        val u = upNext(calls = calls)
        u.onTimes(100.0, duration)
        u.onTimes(2_376.0, duration)
        val cleared = u.onTimes(1_000.0, duration)
        assertEquals(listOf(MpvProgress.showTextCommand("", 1)), cleared)
        assertTrue(u.onTimes(2_376.0, duration).single().contains("Starting in 5 s"))
        assertTrue(u.onClientMessage(listOf(MpvWindowUpNext.CANCEL_MESSAGE)).single().contains("Up Next cancelled"))
        assertEquals(1, calls.cancels)
        assertTrue(u.onTimes(2_390.0, duration).isEmpty())
        assertFalse(u.movedOn)
    }

    @Test
    fun `preloading starts ahead of the threshold when it's on`() {
        val calls = Calls()
        val u = upNext(AutoPlaySettings(preloadNextEpisode = true), calls = calls)
        u.onTimes(100.0, duration)
        u.onTimes(2_340.0, duration)
        assertEquals(0, calls.preloads)
        // The lead is max(timeout, 10 s): 10 s before the threshold at 2 376 s.
        u.onTimes(2_350.0, duration)
        assertEquals(0, calls.preloads)
        u.onTimes(2_367.0, duration)
        assertEquals(1, calls.preloads)
        u.onTimes(2_368.0, duration)
        assertEquals("once", 1, calls.preloads)
        val off = Calls()
        val quiet = upNext(AutoPlaySettings(preloadNextEpisode = false), calls = off)
        quiet.onTimes(100.0, duration)
        quiet.onTimes(2_370.0, duration)
        assertEquals(0, off.preloads)
    }

    @Test
    fun `without a next episode there's no Up Next, only the queue key`() {
        val u = upNext(next = null, queue = listOf("Heat", "Lost · S1 E1"))
        assertEquals(1, u.startup().size)
        assertTrue(u.startup().single().contains(MpvWindowUpNext.QUEUE_MESSAGE))
        u.onTimes(100.0, duration)
        assertTrue(u.onTimes(2_399.0, duration).isEmpty())
        val queue = u.onClientMessage(listOf(MpvWindowUpNext.QUEUE_MESSAGE)).single()
        assertTrue(queue.contains("1. Heat") && queue.contains("2. Lost · S1 E1"))
        assertTrue(upNext(next = null).onClientMessage(listOf(MpvWindowUpNext.QUEUE_MESSAGE)).single().contains("The queue is empty"))
        assertTrue(u.onClientMessage(listOf("something-else")).isEmpty())
    }

    @Test
    fun `IPC commands are one JSON line each`() {
        assertEquals("{\"command\":[\"keybind\",\"Alt+n\",\"script-message fetch-upnext-cancel\"]}\n",
            MpvProgress.keybindCommand("Alt+n", "fetch-upnext-cancel"))
        assertEquals("{\"command\":[\"show-text\",\"a\\nb \\\"c\\\"\",10]}\n", MpvProgress.showTextCommand("a\nb \"c\"", 10))
    }
}
