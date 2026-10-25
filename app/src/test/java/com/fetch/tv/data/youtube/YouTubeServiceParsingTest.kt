package com.fetch.tv.data.youtube

import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeServiceParsingTest {

    private val service = YouTubeService(authDataStore = mockk(relaxed = true))

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `device code response is read`() {
        val code = service.parseDeviceCode(
            json(
                """{"device_code": "dev", "user_code": "ABC-DEF-GHI", "expires_in": 1800,
                    "interval": 5, "verification_url": "https://www.google.com/device"}"""
            )
        )

        assertEquals(YouTubeDeviceCode("dev", "ABC-DEF-GHI", "https://www.google.com/device", 1800, 5), code)
    }

    @Test
    fun `device code without a url points at youtube activate`() {
        val code = service.parseDeviceCode(json("""{"device_code": "dev", "user_code": "CODE"}"""))

        assertEquals(YouTubeService.ACTIVATION_URL, code?.verificationUrl)
    }

    @Test
    fun `an error instead of a device code is no code`() {
        assertNull(service.parseDeviceCode(json("""{"error": "invalid_client"}""")))
        assertNull(service.parseDeviceCode(null))
    }

    @Test
    fun `an approved poll returns the token`() {
        val poll = service.parseTokenPoll(
            json("""{"access_token": "at", "refresh_token": "rt", "expires_in": 3599, "token_type": "Bearer"}""")
        )

        assertEquals(YouTubeService.TokenPoll.Token(YouTubeToken("at", "rt", 3599)), poll)
    }

    @Test
    fun `poll errors map to their states`() {
        fun status(error: String) =
            (service.parseTokenPoll(json("""{"error": "$error"}""")) as YouTubeService.TokenPoll.Status).status

        assertEquals(YouTubeTokenPollResult.Pending, status("authorization_pending"))
        assertEquals(YouTubeTokenPollResult.SlowDown(10), status("slow_down"))
        assertEquals(YouTubeTokenPollResult.Expired, status("expired_token"))
        assertEquals(YouTubeTokenPollResult.Denied, status("access_denied"))
        assertTrue(status("server_error") is YouTubeTokenPollResult.Failed)
    }

    @Test
    fun `the tv client is read from the first match in the tv script`() {
        // The script wraps lines, so the secret can follow a newline.
        val script = "var a={clientId:\"111-tv.apps.googleusercontent.com\",\nzx:\"tvSecret\"};" +
            """var b={clientId:"222-other.apps.googleusercontent.com",q:"otherSecret"};"""

        assertEquals("111-tv.apps.googleusercontent.com" to "tvSecret", service.parseClientCredentials(script))
        assertNull(service.parseClientCredentials("no client here"))
    }

    @Test
    fun `a refresh response may leave the refresh token out`() {
        val token = service.parseToken(json("""{"access_token": "new", "expires_in": 3600}"""))

        assertEquals(YouTubeToken("new", null, 3600), token)
    }
}
