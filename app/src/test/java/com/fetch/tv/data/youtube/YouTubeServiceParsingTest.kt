package com.fetch.tv.data.youtube

import io.mockk.mockk
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    fun `tracking links are read from a player response`() {
        val links = service.parseWatchTracking(
            json(
                """{"playbackTracking": {
                    "videostatsPlaybackUrl": {"baseUrl": "https://s.youtube.com/api/stats/playback?docid=v&ei=E&of=O&vm=V"},
                    "videostatsWatchtimeUrl": {"baseUrl": "https://s.youtube.com/api/stats/watchtime?docid=v&ei=E&of=O&vm=V"}
                }}"""
            )
        )

        assertEquals("https://s.youtube.com/api/stats/playback?docid=v&ei=E&of=O&vm=V", links?.first)
        assertNull(service.parseWatchTracking(json("""{"playabilityStatus": {"status": "OK"}}""")))
    }

    @Test
    fun `a created playlist's id is read`() {
        assertEquals(
            "PLnew",
            service.parseCreatedPlaylistId(json("""{"responseContext": {}, "playlistId": "PLnew"}"""))
        )
        assertNull(service.parseCreatedPlaylistId(json("""{"responseContext": {}}""")))
        assertNull(service.parseCreatedPlaylistId(json("""{"playlistId": ""}""")))
        assertNull(service.parseCreatedPlaylistId(null))
    }

    @Test
    fun `a watch time report keeps the tracking params and adds the position`() {
        val session = YouTubeWatchSession("vid", "p", "w", clientPlaybackNonce = "NONCE", lastPositionSec = 0f)
        val base = "https://s.youtube.com/api/stats/watchtime?ei=E&of=O&len=1"

        val parsed = service.watchTrackingUrl(base, session, lengthSec = 600f, cmt = 120f, from = 60f, to = 120f)
            .toHttpUrl()

        assertEquals("E", parsed.queryParameter("ei"))
        assertEquals("O", parsed.queryParameter("of"))
        assertEquals("600.000", parsed.queryParameter("len"))
        assertEquals("120.000", parsed.queryParameter("cmt"))
        assertEquals("60.000", parsed.queryParameter("st"))
        assertEquals("120.000", parsed.queryParameter("et"))
        assertEquals("NONCE", parsed.queryParameter("cpn"))
        assertEquals("vid", parsed.queryParameter("docid"))
        assertNull(parsed.queryParameter("final"))
    }

    @Test
    fun `a refresh response may leave the refresh token out`() {
        val token = service.parseToken(json("""{"access_token": "new", "expires_in": 3600}"""))

        assertEquals(YouTubeToken("new", null, 3600), token)
    }
}
