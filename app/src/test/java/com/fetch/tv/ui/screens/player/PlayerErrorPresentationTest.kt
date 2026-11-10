package com.fetch.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's error screen leads with plain words, never a code (#406). */
class PlayerErrorPresentationTest {

    @Test
    fun `HTTP statuses map to plain kinds`() {
        assertEquals(PlayerErrorKind.LINK_EXPIRED, playerErrorKindForHttpStatus(401))
        assertEquals(PlayerErrorKind.LINK_EXPIRED, playerErrorKindForHttpStatus(410))
        assertEquals(PlayerErrorKind.ACCESS_BLOCKED, playerErrorKindForHttpStatus(403))
        assertEquals(PlayerErrorKind.LINK_REMOVED, playerErrorKindForHttpStatus(404))
        assertEquals(PlayerErrorKind.RATE_LIMITED, playerErrorKindForHttpStatus(429))
        assertEquals(PlayerErrorKind.SERVER_UNAVAILABLE, playerErrorKindForHttpStatus(503))
        assertNull(playerErrorKindForHttpStatus(200))
    }

    @Test
    fun `every error class gets a kind`() {
        assertEquals(
            PlayerErrorKind.UNSUPPORTED_FORMAT,
            playerErrorKindForErrorCode(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        )
        assertEquals(
            PlayerErrorKind.DECODER_FAILED,
            playerErrorKindForErrorCode(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        )
        assertEquals(
            PlayerErrorKind.AUDIO_FAILED,
            playerErrorKindForErrorCode(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
        )
        assertEquals(
            PlayerErrorKind.NETWORK,
            playerErrorKindForErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        assertEquals(PlayerErrorKind.TIMEOUT, playerErrorKindForErrorCode(PlaybackException.ERROR_CODE_TIMEOUT))
        assertEquals(
            PlayerErrorKind.PROTECTED,
            playerErrorKindForErrorCode(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED)
        )
        assertEquals(PlayerErrorKind.GENERIC, playerErrorKindForErrorCode(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun `mpv failures map to kinds`() {
        assertEquals(PlayerErrorKind.LINK_REMOVED, mpvPlayerErrorKind(404, null))
        assertEquals(PlayerErrorKind.GENERIC, mpvPlayerErrorKind(302, null))
        assertEquals(
            PlayerErrorKind.NOT_A_VIDEO,
            mpvPlayerErrorKind(null, MpvNonHttpExplanationKind.InvalidContent)
        )
        assertNull(mpvPlayerErrorKind(null, null))
    }

    @Test
    fun `an HTTP error leads with its kind and keeps the code in the detail`() {
        val display = playerErrorDisplay(
            "HTTP 404 Not Found [ERROR_CODE_IO_BAD_HTTP_STATUS]",
            PlayerErrorKind.LINK_REMOVED
        )
        assertEquals(PlayerErrorKind.LINK_REMOVED, display.kind)
        assertNull(display.plainHeadline)
        assertEquals("HTTP 404 Not Found [ERROR_CODE_IO_BAD_HTTP_STATUS]", display.detail)
    }

    @Test
    fun `an mpv error drops its plain explanation for the kind's and keeps the technical part`() {
        val display = playerErrorDisplay(
            "The stream link has expired. Choose another source.\n\nHTTP 410 Gone [loading failed]",
            PlayerErrorKind.LINK_EXPIRED
        )
        assertEquals(PlayerErrorKind.LINK_EXPIRED, display.kind)
        assertEquals("HTTP 410 Gone [loading failed]", display.detail)
    }

    @Test
    fun `with a kind and no technical paragraph the detail is what follows the explanation`() {
        val display = playerErrorDisplay(
            "This stream uses a format your device may not support.\n\nloading failed",
            PlayerErrorKind.UNSUPPORTED_FORMAT
        )
        assertEquals("loading failed", display.detail)
    }

    @Test
    fun `a plain message without a kind leads as it is`() {
        val display = playerErrorDisplay("This source has no stream link. Choose another source.", null)
        assertNull(display.kind)
        assertEquals("This source has no stream link. Choose another source.", display.plainHeadline)
        assertNull(display.detail)
    }

    @Test
    fun `a technical message without a kind never becomes the headline`() {
        val message = "java.io.EOFException [ERROR_CODE_IO_UNSPECIFIED]"
        val display = playerErrorDisplay(message, null)
        assertEquals(PlayerErrorKind.GENERIC, display.kind)
        assertNull(display.plainHeadline)
        assertEquals(message, display.detail)
    }

    @Test
    fun `codes, statuses and exception names read as technical`() {
        assertTrue(looksTechnical("Decoder failed [ERROR_CODE_DECODING_FAILED]"))
        assertTrue(looksTechnical("HTTP 503 Service Unavailable"))
        assertTrue(looksTechnical("IllegalStateException: surface released"))
        assertTrue(looksTechnical("Failed to open https://example.com/a.mkv"))
        assertFalse(looksTechnical("This source has no stream link. Choose another source."))
        assertFalse(looksTechnical("Unable to start the player. Restart Fetch and try again."))
    }

    @Test
    fun `the kind is only used for the error it was worked out for`() {
        val tagged = PlayerUiState(
            error = "HTTP 404 [X]",
            errorTag = PlayerErrorTag("HTTP 404 [X]", PlayerErrorKind.LINK_REMOVED)
        )
        assertEquals(PlayerErrorKind.LINK_REMOVED, tagged.currentErrorKind())
        assertNull(tagged.copy(error = "Something else").currentErrorKind())
    }
}
