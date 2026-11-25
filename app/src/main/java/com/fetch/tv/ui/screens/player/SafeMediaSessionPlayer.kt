@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.fetch.tv.ui.screens.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Wraps a [Player] so that [getBufferedPercentage] never throws when
 * Media3 [MediaSession] internals call [androidx.media3.common.util.Util.percentInt].
 *
 * Play and pause from the session (Assistant, Google Home, a phone remote) go to [onPlay] and
 * [onPause] when given, so they take the same path as the remote's keys. Calling the raw player
 * left the app's pause flag unset, and the next seek or rebuffer resumed playback (#705).
 */
internal class SafeMediaSessionPlayer(
    player: Player,
    private val onPlay: (() -> Unit)? = null,
    private val onPause: (() -> Unit)? = null,
) : ForwardingPlayer(player) {

    override fun getBufferedPercentage(): Int {
        return try {
            super.getBufferedPercentage().coerceIn(0, 100)
        } catch (_: IllegalArgumentException) {
            // Util.percentInt overflow — live stream with huge bufferedPosition
            100
        }
    }

    override fun play() {
        val route = onPlay
        if (route != null) route() else super.play()
    }

    override fun pause() {
        val route = onPause
        if (route != null) route() else super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }
}
