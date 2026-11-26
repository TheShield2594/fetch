package com.fetch.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TheShield2594/fetch#741: each cause of a failed extraction gets its own reason. */
class YouTubeExtractionFailureTest {

    private val extractor = InAppYouTubeExtractor()

    @Test
    fun `a playable response is not a failure`() {
        assertNull(failureForPlayability("OK", null))
        assertNull(failureForPlayability(null, null))
    }

    @Test
    fun `playability statuses map to distinct reasons`() {
        assertEquals(
            YouTubeExtractionFailure.AGE_RESTRICTED,
            failureForPlayability("LOGIN_REQUIRED", "Sign in to confirm your age")
        )
        assertEquals(
            YouTubeExtractionFailure.RATE_LIMITED,
            failureForPlayability("LOGIN_REQUIRED", "Sign in to confirm you’re not a bot")
        )
        assertEquals(
            YouTubeExtractionFailure.SIGN_IN_REQUIRED,
            failureForPlayability("LOGIN_REQUIRED", "This video is private")
        )
        assertEquals(
            YouTubeExtractionFailure.REGION_BLOCKED,
            failureForPlayability("UNPLAYABLE", "The uploader has not made this video available in your country")
        )
        assertEquals(
            YouTubeExtractionFailure.SIGN_IN_REQUIRED,
            failureForPlayability("UNPLAYABLE", "Join this channel to get access to members-only content")
        )
        assertEquals(
            YouTubeExtractionFailure.LIVE_NOT_STARTED,
            failureForPlayability("LIVE_STREAM_OFFLINE", "Premieres in 2 hours")
        )
        assertEquals(
            YouTubeExtractionFailure.UNAVAILABLE,
            failureForPlayability("ERROR", "Video unavailable")
        )
    }

    @Test
    fun `the most specific refusal wins over a vague one`() {
        assertEquals(
            YouTubeExtractionFailure.AGE_RESTRICTED,
            mostSpecificFailure(listOf(YouTubeExtractionFailure.UNAVAILABLE, YouTubeExtractionFailure.AGE_RESTRICTED))
        )
        assertEquals(
            YouTubeExtractionFailure.REGION_BLOCKED,
            mostSpecificFailure(listOf(YouTubeExtractionFailure.REGION_BLOCKED, YouTubeExtractionFailure.SIGN_IN_REQUIRED))
        )
        assertNull(mostSpecificFailure(emptyList()))
    }

    @Test
    fun `a live video only uses the HLS manifest`() {
        assertEquals(listOf(PlaybackSourceKind.HLS_MANIFEST), extractor.sourcePreference(singleUrl = false, isLive = true))
        assertEquals(listOf(PlaybackSourceKind.HLS_MANIFEST), extractor.sourcePreference(singleUrl = true, isLive = true))
    }
}
