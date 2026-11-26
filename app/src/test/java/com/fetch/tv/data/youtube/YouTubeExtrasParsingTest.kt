package com.fetch.tv.data.youtube

import com.fetch.tv.data.local.YouTubeAuthDataStore
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search suggestions, DeArrow branding on a video, and watch sessions. */
class YouTubeExtrasParsingTest {

    private val service = YouTubeService(mockk<YouTubeAuthDataStore>(relaxed = true))

    @Test
    fun `suggestions are read from the suggest service's script`() {
        // As the service answers, trimmed.
        val body = """window.google.ac.h(["nasa art",[["nasa artemis",0,[512,433]],["nasa artemis lego",0,[512]],["nasa artemis",0,[512]]],{"k":1,"q":"x"}])"""
        assertEquals(listOf("nasa artemis", "nasa artemis lego"), service.parseSuggestions(body))
    }

    @Test
    fun `an unreadable suggest answer gives none`() {
        assertTrue(service.parseSuggestions("").isEmpty())
        assertTrue(service.parseSuggestions("<html>").isEmpty())
        assertTrue(service.parseSuggestions("""window.google.ac.h(["q"])""").isEmpty())
    }

    @Test
    fun `branding replaces only what it has`() {
        val video = YouTubeVideo(videoId = "v", title = "YouTube title", thumbnailUrl = "yt.jpg")
        assertEquals("New", video.withBranding(YouTubeBranding("New", null)).title)
        assertEquals("yt.jpg", video.withBranding(YouTubeBranding("New", null)).thumbnailUrl)
        assertEquals(video, video.withBranding(null))
    }

    @Test
    fun `a session resumed past three minutes reports from there`() {
        val info = YouTubePlayerInfo(trackingLinks = "https://s.youtube.com/a" to "https://s.youtube.com/b", captions = null)
        val session = service.newWatchSession("v", info, startPositionSec = 0f)!!
        session.startAt(600f)
        assertEquals(600f, session.lastPositionSec)
        session.startAt(60f)
        assertEquals(0f, session.lastPositionSec)
        session.recorded = true
        session.startAt(900f)
        assertEquals("ignored once reported", 0f, session.lastPositionSec)
    }
}
