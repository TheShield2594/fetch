package com.fetch.tv.ui.screens.player

/**
 * Whether a speed change must restart the ExoPlayer audio path.
 *
 * A passthrough (bitstream) track cannot change speed, so [PlaybackSpeedAwareAudioSink] decodes it
 * to PCM away from 1x. Media3 only picks passthrough or decode when the audio renderer is enabled:
 * a capabilities change that keeps the same track selected is a no-op, so without a restart the
 * renderer keeps the bitstream (speed stays 1x) or keeps PCM after returning to 1x.
 */
internal object PlaybackSpeedAudioPathPolicy {
    fun needsRestart(
        oldSpeed: Float,
        newSpeed: Float,
        encodedOutputActive: Boolean,
        canRestorePassthrough: Boolean
    ): Boolean {
        val wasNormal = oldSpeed == 1f
        val isNormal = newSpeed == 1f
        return when {
            wasNormal && !isNormal -> encodedOutputActive
            !wasNormal && isNormal -> canRestorePassthrough && !encodedOutputActive
            else -> false
        }
    }
}
