package com.fetch.tv.data.website

import com.fetch.tv.core.tracking.TrackingExternalIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchWebsiteContentIdsTest {
    @Test
    fun `content ids are spelled as the website spells them`() {
        assertEquals("tt0111161", fetchWebsiteContentId("tt0111161"))
        assertEquals("tt0111161", fetchWebsiteContentId(" TT0111161 "))
        assertEquals("tt0111161", fetchWebsiteContentId("imdb:tt0111161"))
        assertEquals("tmdb:603", fetchWebsiteContentId("TMDB:0603"))
        assertEquals("kitsu:1376", fetchWebsiteContentId("kitsu:1376"))
        assertEquals("mal:1", fetchWebsiteContentId("mal:1"))
        assertEquals("anilist:1", fetchWebsiteContentId("anilist:1"))
    }

    @Test
    fun `a video id gives its title's id`() {
        assertEquals("tt0903747", fetchWebsiteContentId("tt0903747:2:5"))
        assertEquals("kitsu:1376", fetchWebsiteContentId("kitsu:1376:5"))
        assertEquals("tmdb:1396", fetchWebsiteContentId("tmdb:1396:1:1"))
    }

    @Test
    fun `ids the website can't key are null`() {
        assertNull(fetchWebsiteContentId(null))
        assertNull(fetchWebsiteContentId(""))
        assertNull(fetchWebsiteContentId("tt"))
        assertNull(fetchWebsiteContentId("tt-show"))
        assertNull(fetchWebsiteContentId("yt_id:abc"))
        assertNull(fetchWebsiteContentId("kitsu"))
        assertNull(fetchWebsiteContentId("kitsu:abc"))
        assertNull(fetchWebsiteContentId("1376"))
        assertTrue(isFetchWebsiteContentId("tmdb:603"))
        assertFalse(isFetchWebsiteContentId("TMDB:603"))
    }

    @Test
    fun `external ids prefer IMDb, then TMDB, then an anime catalog`() {
        assertEquals("tt0133093", TrackingExternalIds(imdb = "tt0133093", tmdb = 603).fetchWebsiteContentId())
        assertEquals("tmdb:603", TrackingExternalIds(tmdb = 603, kitsu = 1).fetchWebsiteContentId())
        assertEquals("kitsu:1376", TrackingExternalIds(kitsu = 1376, mal = 1).fetchWebsiteContentId())
        assertEquals("mal:1", TrackingExternalIds(mal = 1, trakt = 2).fetchWebsiteContentId())
        assertEquals("tvdb:76885", TrackingExternalIds(tvdb = "76885").fetchWebsiteContentId())
        assertNull(TrackingExternalIds(imdb = "not-imdb").fetchWebsiteContentId())
        assertNull(TrackingExternalIds().fetchWebsiteContentId())
    }
}
