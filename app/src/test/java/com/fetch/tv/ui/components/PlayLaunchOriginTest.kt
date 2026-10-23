package com.fetch.tv.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayLaunchOriginTest {
    private val button = Rect(10f, 20f, 210f, 80f)

    @After
    fun clear() {
        PlayLaunchOrigin.take()
        PlayLaunchOrigin.offer(null)
    }

    @Test
    fun `a launch is taken once`() {
        PlayLaunchOrigin.launchFrom(button, nowMs = 1_000L)

        assertEquals(PlayLaunch(button, atMs = 1_000L), PlayLaunchOrigin.take(nowMs = 1_200L))
        assertNull(PlayLaunchOrigin.take(nowMs = 1_300L))
    }

    @Test
    fun `an old launch is not the one the screen came from`() {
        PlayLaunchOrigin.launchFrom(button, nowMs = 1_000L)

        assertNull(PlayLaunchOrigin.take(nowMs = 1_000L + PlayLaunchOrigin.MAX_AGE_MS + 1))
    }

    @Test
    fun `resume launches from the offered bounds while they are shown`() {
        PlayLaunchOrigin.offer(button)
        PlayLaunchOrigin.launchFromOffer(nowMs = 5_000L)
        assertEquals(PlayLaunch(button, atMs = 5_000L), PlayLaunchOrigin.take(nowMs = 5_100L))

        PlayLaunchOrigin.offer(null)
        PlayLaunchOrigin.launchFromOffer(nowMs = 6_000L)
        assertNull(PlayLaunchOrigin.take(nowMs = 6_100L))
    }
}
