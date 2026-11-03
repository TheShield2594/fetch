package com.fetch.desktop.data

import com.fetch.desktop.sync.SyncSettingsBlob
import com.fetch.desktop.sync.applyTo
import com.fetch.desktop.sync.changedFrom
import com.fetch.desktop.sync.toRemoteSharedSettings
import com.fetch.tv.domain.model.ContinueWatchingCardStyle
import com.fetch.tv.domain.model.DetailImdbRatingsVisibility
import com.fetch.tv.domain.model.EpisodeOptionsOverlayStyle
import com.fetch.tv.domain.model.HomeImdbRatingsVisibility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutSyncTest {
    private fun blob(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `the TV's layout and trailer entries read into Layout`() {
        val tv = blob(
            """{"version":1,"features":{
                "layout_settings":{
                  "continue_watching_card_style":{"type":"string","value":"WIDE"},
                  "use_episode_thumbnails_in_cw":{"type":"boolean","value":false},
                  "blur_continue_watching_next_up":{"type":"boolean","value":true},
                  "hero_section_enabled":{"type":"boolean","value":false},
                  "hero_catalog_keys":{"type":"string","value":"[\"com.addon_movie_top\",\"com.addon_series_top\"]"},
                  "custom_catalog_titles":{"type":"string","value":"{\"com.addon_movie_top\":\"Best films\"}"},
                  "poster_card_width_dp":{"type":"int","value":150},
                  "poster_card_corner_radius_dp":{"type":"int","value":-1},
                  "focused_poster_backdrop_expand_delay_seconds":{"type":"int","value":5},
                  "episode_options_overlay_style":{"type":"string","value":"ARTWORK"},
                  "home_imdb_ratings_visibility":{"type":"string","value":"HIDE_ALL"},
                  "detail_imdb_ratings_visibility":{"type":"string","value":"HIDE_ALL"},
                  "random_episode_enabled":{"type":"boolean","value":true},
                  "custom_poster_url_pattern":{"type":"string","value":"https://p.example/{imdb_id}.jpg"},
                  "custom_poster_enabled_screens":{"type":"string","value":"home,library"},
                  "unknown_key":{"type":"boolean","value":true}
                },
                "trailer_settings":{"trailer_delay_seconds":{"type":"int","value":3}},
                "player_settings":{"ready_check_enabled":{"type":"boolean","value":false}}
            }}"""
        )
        val shared = SyncSettingsBlob.sharedSettings(tv)
        val l = shared.applyTo(DesktopSettings()).layout
        assertEquals(ContinueWatchingCardStyle.WIDE, l.continueWatchingCardStyle)
        assertFalse(l.episodeThumbnailsInContinueWatching)
        assertTrue(l.blurContinueWatchingNextUp)
        assertFalse(l.heroEnabled)
        assertEquals(listOf("com.addon_movie_top", "com.addon_series_top"), l.heroCatalogKeys)
        assertEquals(mapOf("com.addon_movie_top" to "Best films"), l.customCatalogTitles)
        assertEquals(150, l.posterCardWidthDp)
        assertEquals(5, l.expandFocusedPosterDelaySeconds)
        assertEquals(EpisodeOptionsOverlayStyle.ARTWORK, l.episodeOptionsOverlay)
        assertEquals(HomeImdbRatingsVisibility.HIDE_ALL, l.homeRatings)
        // The TV reads an overall "hide all" on episodes as "hide episodes".
        assertEquals(DetailImdbRatingsVisibility.HIDE_EPISODES, l.detailRatings)
        assertTrue(l.randomEpisodeEnabled)
        assertEquals(3, l.trailerDelaySeconds)
        assertFalse(l.readyCheckEnabled)
        assertEquals("https://p.example/{imdb_id}.jpg", l.customPosterUrlPattern)
        assertEquals(setOf("home", "library"), l.customPosterScreens)
        // Keys the blob lacks keep their value.
        assertTrue(l.showFullReleaseDate)
    }

    @Test
    fun `a change here is pushed with the TV's keys and types, alone`() {
        val before = DesktopSettings()
        val after = before.copy(layout = before.layout.copy(continueWatchingCardStyle = ContinueWatchingCardStyle.POSTER, posterCardHeightDp = 207))
        val diff = after.toRemoteSharedSettings().changedFrom(before.toRemoteSharedSettings())!!
        assertEquals(setOf("layout_settings/continue_watching_card_style", "layout_settings/poster_card_height_dp"), diff.layoutValues!!.keys)
        val written = SyncSettingsBlob.withSharedSettings(null, diff)
        val layout = written["features"]!!.jsonObject["layout_settings"]!!.jsonObject
        assertEquals("string", layout["continue_watching_card_style"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("POSTER", layout["continue_watching_card_style"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("int", layout["poster_card_height_dp"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(207, layout["poster_card_height_dp"]!!.jsonObject["value"]!!.jsonPrimitive.content.toInt())
        assertNull(diff.theme)
    }

    @Test
    fun `layout round-trips through the blob`() {
        val prefs = LayoutPrefs(
            heroCatalogKeys = listOf("a_movie_b"),
            customCatalogTitles = mapOf("a_movie_b" to "Mine"),
            customPosterScreens = emptySet(),
            focusedPosterTrailer = true,
            landscapePosters = true
        )
        val settings = DesktopSettings(layout = prefs)
        val written = SyncSettingsBlob.withSharedSettings(null, settings.toRemoteSharedSettings())
        val read = SyncSettingsBlob.sharedSettings(written).applyTo(DesktopSettings())
        assertEquals(prefs.copy(homeOrder = emptyList()), read.layout)
        val screens = written["features"]!!.jsonObject["layout_settings"]!!.jsonObject["custom_poster_enabled_screens"] as JsonObject
        assertEquals("none", screens["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `every screen is written as a full list and read back as all`() {
        val all = LayoutSync.encode(LayoutPrefs())["layout_settings/custom_poster_enabled_screens"]!!
        assertTrue(all["value"]!!.jsonPrimitive.content.split(",").containsAll(listOf("home", "details", "tv_recommendations")))
        assertNull(LayoutSync.apply(mapOf("layout_settings/custom_poster_enabled_screens" to all), LayoutPrefs(customPosterScreens = setOf("home"))).customPosterScreens)
    }

    @Test
    fun `entries of the wrong type are ignored`() {
        val wrong = mapOf("layout_settings/poster_card_width_dp" to LayoutSync.entry("string", "150"))
        assertEquals(LayoutPrefs(), LayoutSync.apply(wrong, LayoutPrefs()))
    }

    @Test
    fun `TMDB's Home switches sync with the TV's keys`() {
        val tv = blob(
            """{"features":{"tmdb_settings":{"tmdb_modern_home_enabled":{"type":"boolean","value":true},"tmdb_enrich_continue_watching":{"type":"boolean","value":false}}}}"""
        )
        val t = SyncSettingsBlob.sharedSettings(tv).applyTo(DesktopSettings()).tmdb
        assertTrue(t.modernHomeEnabled)
        assertFalse(t.enrichContinueWatching)
        val pushed = DesktopSettings(tmdb = t).toRemoteSharedSettings().tmdbFlags!!
        assertEquals(true, pushed["tmdb_modern_home_enabled"])
        assertEquals(false, pushed["tmdb_enrich_continue_watching"])
    }
}
