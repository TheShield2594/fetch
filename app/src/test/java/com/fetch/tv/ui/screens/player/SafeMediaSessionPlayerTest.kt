package com.fetch.tv.ui.screens.player

import androidx.media3.common.Player
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class SafeMediaSessionPlayerTest {
    @Test
    fun sessionPlayAndPauseTakeTheAppsPath() {
        val player = mockk<Player>(relaxed = true)
        val calls = mutableListOf<String>()
        val wrapped = SafeMediaSessionPlayer(player, onPlay = { calls += "play" }, onPause = { calls += "pause" })

        wrapped.pause()
        wrapped.play()
        wrapped.setPlayWhenReady(false)
        wrapped.setPlayWhenReady(true)

        assertEquals(listOf("pause", "play", "pause", "play"), calls)
        verify(exactly = 0) { player.pause() }
        verify(exactly = 0) { player.play() }
        verify(exactly = 0) { player.setPlayWhenReady(any()) }
    }

    @Test
    fun withoutRoutesTheRawPlayerIsUsed() {
        val player = mockk<Player>(relaxed = true)
        val wrapped = SafeMediaSessionPlayer(player)

        wrapped.pause()
        wrapped.play()

        verify(exactly = 1) { player.pause() }
        verify(exactly = 1) { player.play() }
    }
}
