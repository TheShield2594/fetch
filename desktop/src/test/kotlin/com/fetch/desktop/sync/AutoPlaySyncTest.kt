package com.fetch.desktop.sync

import com.fetch.desktop.data.AutoPlayMode
import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.NextEpisodeThresholdMode
import com.fetch.desktop.playback.QueueItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlaySyncTest {
    private val tvBlob = Json.parseToJsonElement(
        """
        {"version":1,"features":{
          "player_settings":{
            "stream_auto_play_mode":{"type":"string","value":"FIRST_STREAM"},
            "stream_auto_play_timeout_seconds":{"type":"int","value":11},
            "next_episode_threshold_mode":{"type":"string","value":"MINUTES_BEFORE_END"},
            "next_episode_threshold_minutes_before_end_v2":{"type":"float","value":3.0},
            "stream_reuse_last_link_enabled":{"type":"boolean","value":true},
            "stream_reuse_last_link_cache_hours":{"type":"int","value":6},
            "stream_auto_play_reuse_binge_group":{"type":"boolean","value":true},
            "post_play_movie_threshold_percent":{"type":"int","value":50},
            "parental_guide_enabled":{"type":"string","value":"wrong type"},
            "decoder_priority":{"type":"int","value":2}
          },
          "episode_queue":{"items":{"type":"string","value":"[{\"contentId\":\"tt1\",\"contentType\":\"series\",\"videoId\":\"tt1:1:2\",\"name\":\"Show\",\"season\":1,\"episode\":2,\"origin\":\"MANUAL\",\"addedAtMs\":1}]"}}
        }}
        """.trimIndent()
    ).jsonObject

    @Test
    fun `the TV's auto-play settings and queue apply here, clamped, wrong types ignored`() {
        val shared = SyncSettingsBlob.sharedSettings(tvBlob)
        val applied = shared.applyTo(DesktopSettings())
        assertEquals(AutoPlayMode.FIRST_STREAM, applied.autoPlayMode)
        assertEquals("the TV's old 11 means Unlimited", AutoPlaySettings.TIMEOUT_UNLIMITED, applied.addonTimeoutSeconds)
        assertEquals(NextEpisodeThresholdMode.MINUTES_BEFORE_END, applied.autoPlay.nextEpisodeThresholdMode)
        assertEquals(3f, applied.autoPlay.nextEpisodeThresholdMinutesBeforeEnd)
        assertTrue(applied.autoPlay.reuseLastLink)
        assertEquals(6, applied.autoPlay.reuseLastLinkCacheHours)
        assertTrue(applied.autoPlay.reuseBingeGroup)
        assertEquals(80, applied.autoPlay.postPlayMovieThresholdPercent)
        assertTrue("left as it was", applied.autoPlay.parentalGuide)
        assertEquals(listOf("tt1:1:2"), applied.episodeQueue.map { it.videoId })
    }

    @Test
    fun `a push writes only what changed, with the TV's types, keeping the TV's other keys`() {
        val before = DesktopSettings()
        val after = before.copy(
            autoPlay = before.autoPlay.copy(startEarly = true, nextEpisodeThresholdPercent = 98.5f),
            episodeQueue = listOf(QueueItem("tt2", "movie", "tt2", "Film"))
        )
        val diff = after.toRemoteSharedSettings().changedFrom(before.toRemoteSharedSettings())!!
        assertEquals(setOf("stream_auto_play_start_early", "next_episode_threshold_percent_v2"), diff.autoPlay!!.keys)
        val blob = SyncSettingsBlob.withSharedSettings(tvBlob, diff)
        val player = blob["features"]!!.jsonObject["player_settings"]!!.jsonObject
        assertEquals("boolean", player["stream_auto_play_start_early"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("float", player["next_episode_threshold_percent_v2"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(98.5f, player["next_episode_threshold_percent_v2"]!!.jsonObject["value"]!!.jsonPrimitive.content.toFloat())
        assertEquals("the TV's own keys stay", JsonPrimitive(2), player["decoder_priority"]!!.jsonObject["value"])
        val queue = blob["features"]!!.jsonObject["episode_queue"]!!.jsonObject["items"]!!.jsonObject["value"]!!.jsonPrimitive.content
        assertEquals("tt2", com.fetch.desktop.playback.PlaybackQueue.decode(queue).single().videoId)
        // And back.
        assertEquals(after.autoPlay, SyncSettingsBlob.sharedSettings(blob).applyTo(before).autoPlay.copy(
            nextEpisodeThresholdMode = after.autoPlay.nextEpisodeThresholdMode
        ).let { it.copy(reuseLastLink = false, reuseLastLinkCacheHours = 1, reuseBingeGroup = false, postPlayMovieThresholdPercent = 96, nextEpisodeThresholdMinutesBeforeEnd = 2f) })
    }

    @Test
    fun `nothing changed means nothing to push, and a blob without the features leaves them`() {
        val s = DesktopSettings()
        assertNull(s.toRemoteSharedSettings().changedFrom(s.toRemoteSharedSettings()))
        val shared = SyncSettingsBlob.sharedSettings(JsonObject(mapOf("features" to JsonObject(emptyMap()))))
        assertNull(shared.autoPlay)
        assertNull(shared.episodeQueue)
        val queued = s.copy(episodeQueue = listOf(QueueItem("tt2", "movie", "tt2", "Film")))
        assertEquals(queued.episodeQueue, shared.applyTo(queued).episodeQueue)
    }
}
