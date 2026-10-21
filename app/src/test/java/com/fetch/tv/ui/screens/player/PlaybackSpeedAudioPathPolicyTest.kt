package com.fetch.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedAudioPathPolicyTest {

    @Test
    fun `speeding up a passthrough track restarts the audio path`() {
        assertTrue(
            PlaybackSpeedAudioPathPolicy.needsRestart(
                oldSpeed = 1f,
                newSpeed = 1.5f,
                encodedOutputActive = true,
                canRestorePassthrough = true
            )
        )
    }

    @Test
    fun `speeding up a decoded track keeps playing`() {
        assertFalse(
            PlaybackSpeedAudioPathPolicy.needsRestart(
                oldSpeed = 1f,
                newSpeed = 1.5f,
                encodedOutputActive = false,
                canRestorePassthrough = true
            )
        )
    }

    @Test
    fun `back to normal speed restores passthrough for a bitstream track`() {
        assertTrue(
            PlaybackSpeedAudioPathPolicy.needsRestart(
                oldSpeed = 1.5f,
                newSpeed = 1f,
                encodedOutputActive = false,
                canRestorePassthrough = true
            )
        )
    }

    @Test
    fun `back to normal speed keeps PCM when passthrough cannot come back`() {
        assertFalse(
            PlaybackSpeedAudioPathPolicy.needsRestart(
                oldSpeed = 1.5f,
                newSpeed = 1f,
                encodedOutputActive = false,
                canRestorePassthrough = false
            )
        )
    }

    @Test
    fun `changing between non-normal speeds keeps playing`() {
        assertFalse(
            PlaybackSpeedAudioPathPolicy.needsRestart(
                oldSpeed = 1.5f,
                newSpeed = 2f,
                encodedOutputActive = false,
                canRestorePassthrough = true
            )
        )
    }

    @Test
    fun `sink reports encoded output only for a bitstream input`() {
        val sink = PlaybackSpeedAwareAudioSink(mockk<AudioSink>(relaxed = true))
        assertFalse(sink.isEncodedOutputActive())

        sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build(), 0, null)
        assertTrue(sink.isEncodedOutputActive())

        sink.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setCodecs("ec-3")
                .build(),
            0,
            null
        )
        assertFalse(sink.isEncodedOutputActive())
    }

    @Test
    fun `passthrough is not restored for a sink built for PCM or bluetooth`() {
        val eac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build()
        val aac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()

        assertTrue(PlaybackSpeedAwareAudioSink(mockk(relaxed = true)).canRestorePassthroughAtNormalSpeed(eac3))
        assertFalse(PlaybackSpeedAwareAudioSink(mockk(relaxed = true)).canRestorePassthroughAtNormalSpeed(aac))
        assertFalse(PlaybackSpeedAwareAudioSink(mockk(relaxed = true)).canRestorePassthroughAtNormalSpeed(null))
        assertFalse(
            PlaybackSpeedAwareAudioSink(mockk(relaxed = true), initialForcePcm = true)
                .canRestorePassthroughAtNormalSpeed(eac3)
        )
        assertFalse(
            PlaybackSpeedAwareAudioSink(mockk(relaxed = true), forcePcmForBluetooth = true)
                .canRestorePassthroughAtNormalSpeed(eac3)
        )
    }
}
