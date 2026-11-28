package com.fetch.tv.ui.screens.player

import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class MpvPlatformIntegrationTest {

    @Test
    fun `a permanent focus loss pauses only while playing`() {
        assertEquals(MpvFocusAction.PAUSE, mpvFocusAction(AudioManager.AUDIOFOCUS_LOSS, true, false, false))
        assertEquals(MpvFocusAction.NONE, mpvFocusAction(AudioManager.AUDIOFOCUS_LOSS, false, false, false))
    }

    @Test
    fun `a transient loss pauses and the gain resumes`() {
        assertEquals(
            MpvFocusAction.PAUSE_FOR_GAIN,
            mpvFocusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true, false, false)
        )
        assertEquals(MpvFocusAction.RESUME, mpvFocusAction(AudioManager.AUDIOFOCUS_GAIN, false, true, false))
    }

    @Test
    fun `a gain never resumes in the background or after a user pause`() {
        assertEquals(MpvFocusAction.NONE, mpvFocusAction(AudioManager.AUDIOFOCUS_GAIN, false, true, true))
        assertEquals(MpvFocusAction.NONE, mpvFocusAction(AudioManager.AUDIOFOCUS_GAIN, false, false, false))
    }

    @Test
    fun `a duck keeps a video playing`() {
        assertEquals(
            MpvFocusAction.NONE,
            mpvFocusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, true, false, false)
        )
    }

    @Test
    fun `the session player reports mpv's state and routes commands back`() {
        var snapshot = snapshot(isPlaying = true)
        val calls = mutableListOf<String>()
        val player = MpvSessionPlayer(
            looper = Looper.getMainLooper(),
            snapshot = { snapshot },
            onPlay = { calls += "play" },
            onPause = { calls += "pause" },
            onSeekTo = { calls += "seek:$it" },
            onStop = { calls += "stop" }
        )

        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.playWhenReady)
        assertEquals(60_000L, player.duration)
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))

        player.pause()
        player.seekTo(30_000L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("pause", "seek:30000"), calls)

        snapshot = snapshot(isPlaying = false, hasMedia = false)
        player.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `live streams are not seekable from the session`() {
        val player = MpvSessionPlayer(
            looper = Looper.getMainLooper(),
            snapshot = { snapshot(isPlaying = true, isLive = true) },
            onPlay = {}, onPause = {}, onSeekTo = {}, onStop = {}
        )
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    }

    private fun snapshot(isPlaying: Boolean, hasMedia: Boolean = true, isLive: Boolean = false) =
        MpvSessionSnapshot(
            isPlaying = isPlaying,
            isBuffering = false,
            hasMedia = hasMedia,
            positionMs = 10_000L,
            durationMs = 60_000L,
            isLive = isLive,
            speed = 1f,
            metadata = MediaMetadata.Builder().setTitle("Title").build(),
            mediaId = "item"
        )
}
