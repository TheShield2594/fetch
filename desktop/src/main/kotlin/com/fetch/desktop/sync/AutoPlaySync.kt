package com.fetch.desktop.sync

import com.fetch.desktop.data.AutoPlayMode
import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.NextEpisodeThresholdMode
import com.fetch.desktop.playback.PlaybackQueue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The TV's Playback › Auto-play settings in its `player_settings` feature, and the playback queue
 * (`episode_queue.items`), synced with their TV keys and types. Values are kept as the blob's
 * JSON primitives, by key, so the shared settings can carry, compare and write them generically.
 */
object AutoPlaySync {
    const val QUEUE_FEATURE = "episode_queue"
    const val QUEUE_KEY = "items"

    /** A `string_set` of addon names, so it travels beside [KEYS] rather than among them. */
    const val ALLOWED_ADDONS_KEY = "stream_auto_play_selected_addons"

    /** Each synced `player_settings` key and the type the TV stores it as. */
    val KEYS: Map<String, String> = linkedMapOf(
        "stream_auto_play_mode" to "string",
        "stream_auto_play_regex" to "string",
        "stream_auto_play_timeout_seconds" to "int",
        "still_watching_enabled" to "boolean",
        "still_watching_episode_threshold" to "int",
        "next_episode_threshold_mode" to "string",
        "next_episode_threshold_percent_v2" to "float",
        "next_episode_threshold_minutes_before_end_v2" to "float",
        "stream_reuse_last_link_enabled" to "boolean",
        "stream_reuse_last_link_cache_hours" to "int",
        "preload_next_episode_sources" to "boolean",
        "stream_auto_play_next_episode_fallback_enabled" to "boolean",
        "stream_auto_play_prefer_bingegroup_next_episode" to "boolean",
        "stream_auto_play_reuse_binge_group" to "boolean",
        "stream_auto_play_start_early" to "boolean",
        "stream_auto_play_next_source_on_failure" to "boolean",
        "best_match_preview_enabled" to "boolean",
        "post_play_recommendations_enabled" to "boolean",
        "post_play_movie_threshold_percent" to "int",
        "parental_guide_enabled" to "boolean",
    )

    /** The synced values in a feature's entries, by key; only those with the TV's type. */
    fun read(player: JsonObject?): Map<String, JsonPrimitive>? {
        player ?: return null
        val values = KEYS.mapNotNull { (key, type) ->
            val entry = player[key] as? JsonObject ?: return@mapNotNull null
            if ((entry["type"] as? JsonPrimitive)?.contentOrNull != type) return@mapNotNull null
            val value = entry["value"] as? JsonPrimitive ?: return@mapNotNull null
            if (value is JsonNull) null else key to value
        }.toMap()
        return values.takeIf { it.isNotEmpty() }
    }

    /** One entry as the TV writes it: `{"type":"int","value":3}`. */
    fun encode(key: String, value: JsonPrimitive): JsonObject? {
        val type = KEYS[key] ?: return null
        return buildJsonObject {
            put("type", type)
            put("value", value)
        }
    }

    /** The queue in a blob's `episode_queue` feature: null without the feature, empty when it has no items. */
    fun readQueue(feature: JsonElement?): String? {
        val obj = feature as? JsonObject ?: return null
        val entry = obj[QUEUE_KEY] as? JsonObject ?: return "[]"
        if ((entry["type"] as? JsonPrimitive)?.contentOrNull != "string") return "[]"
        return (entry["value"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull ?: "[]"
    }

    fun encodeQueue(raw: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("value", raw)
    }

    // --- DesktopSettings <-> values ---

    fun values(settings: DesktopSettings): Map<String, JsonPrimitive> {
        val ap = settings.autoPlay
        val p = settings.player
        return mapOf(
            "stream_auto_play_mode" to JsonPrimitive(settings.autoPlayMode.name),
            "stream_auto_play_regex" to JsonPrimitive(settings.autoPlayRegex),
            "stream_auto_play_timeout_seconds" to JsonPrimitive(AutoPlaySettings.normalizeTimeout(settings.addonTimeoutSeconds)),
            "still_watching_enabled" to JsonPrimitive(p.stillWatching),
            "still_watching_episode_threshold" to JsonPrimitive(p.stillWatchingEpisodes),
            "next_episode_threshold_mode" to JsonPrimitive(ap.nextEpisodeThresholdMode.name),
            "next_episode_threshold_percent_v2" to JsonPrimitive(ap.nextEpisodeThresholdPercent),
            "next_episode_threshold_minutes_before_end_v2" to JsonPrimitive(ap.nextEpisodeThresholdMinutesBeforeEnd),
            "stream_reuse_last_link_enabled" to JsonPrimitive(ap.reuseLastLink),
            "stream_reuse_last_link_cache_hours" to JsonPrimitive(ap.reuseLastLinkCacheHours),
            "preload_next_episode_sources" to JsonPrimitive(ap.preloadNextEpisode),
            "stream_auto_play_next_episode_fallback_enabled" to JsonPrimitive(ap.nextEpisodeFallback),
            "stream_auto_play_prefer_bingegroup_next_episode" to JsonPrimitive(ap.preferBingeGroup),
            "stream_auto_play_reuse_binge_group" to JsonPrimitive(ap.reuseBingeGroup),
            "stream_auto_play_start_early" to JsonPrimitive(ap.startEarly),
            "stream_auto_play_next_source_on_failure" to JsonPrimitive(ap.nextSourceOnFailure),
            "best_match_preview_enabled" to JsonPrimitive(ap.bestMatchPreview),
            "post_play_recommendations_enabled" to JsonPrimitive(ap.postPlayRecommendations),
            "post_play_movie_threshold_percent" to JsonPrimitive(ap.postPlayMovieThresholdPercent),
            "parental_guide_enabled" to JsonPrimitive(ap.parentalGuide),
        )
    }

    /**
     * [settings] with [values] applied, clamped to the TV's ranges. Keys missing or unreadable
     * leave the setting as it is.
     */
    fun apply(values: Map<String, JsonPrimitive>, settings: DesktopSettings): DesktopSettings {
        fun bool(key: String) = values[key]?.booleanOrNull
        fun int(key: String) = values[key]?.intOrNull
        fun float(key: String) = values[key]?.floatOrNull
        fun string(key: String) = values[key]?.takeIf { it.isString }?.contentOrNull
        var s = settings
        string("stream_auto_play_mode")?.let { name -> AutoPlayMode.entries.firstOrNull { it.name == name } }?.let { s = s.copy(autoPlayMode = it) }
        string("stream_auto_play_regex")?.let { s = s.copy(autoPlayRegex = it) }
        int("stream_auto_play_timeout_seconds")?.let { s = s.copy(addonTimeoutSeconds = legacyTimeout(it)) }
        var p = s.player
        bool("still_watching_enabled")?.let { p = p.copy(stillWatching = it) }
        int("still_watching_episode_threshold")?.let { p = p.copy(stillWatchingEpisodes = it.coerceIn(2, 6)) }
        var ap = s.autoPlay
        string("next_episode_threshold_mode")?.let { name -> NextEpisodeThresholdMode.entries.firstOrNull { it.name == name } }?.let { ap = ap.copy(nextEpisodeThresholdMode = it) }
        float("next_episode_threshold_percent_v2")?.let { ap = ap.copy(nextEpisodeThresholdPercent = AutoPlaySettings.halfStep(it, 97f, 100f)) }
        float("next_episode_threshold_minutes_before_end_v2")?.let { ap = ap.copy(nextEpisodeThresholdMinutesBeforeEnd = AutoPlaySettings.halfStep(it, 0f, 3.5f)) }
        bool("stream_reuse_last_link_enabled")?.let { ap = ap.copy(reuseLastLink = it) }
        int("stream_reuse_last_link_cache_hours")?.let { ap = ap.copy(reuseLastLinkCacheHours = it.coerceAtLeast(1)) }
        bool("preload_next_episode_sources")?.let { ap = ap.copy(preloadNextEpisode = it) }
        bool("stream_auto_play_next_episode_fallback_enabled")?.let { ap = ap.copy(nextEpisodeFallback = it) }
        bool("stream_auto_play_prefer_bingegroup_next_episode")?.let { ap = ap.copy(preferBingeGroup = it) }
        bool("stream_auto_play_reuse_binge_group")?.let { ap = ap.copy(reuseBingeGroup = it) }
        bool("stream_auto_play_start_early")?.let { ap = ap.copy(startEarly = it) }
        bool("stream_auto_play_next_source_on_failure")?.let { ap = ap.copy(nextSourceOnFailure = it) }
        bool("best_match_preview_enabled")?.let { ap = ap.copy(bestMatchPreview = it) }
        bool("post_play_recommendations_enabled")?.let { ap = ap.copy(postPlayRecommendations = it) }
        int("post_play_movie_threshold_percent")?.let {
            ap = ap.copy(postPlayMovieThresholdPercent = it.coerceIn(AutoPlaySettings.MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT, AutoPlaySettings.MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT))
        }
        bool("parental_guide_enabled")?.let { ap = ap.copy(parentalGuide = it) }
        return s.copy(player = p, autoPlay = ap)
    }

    /** As the TV reads a stored timeout: its old 11 meant Unlimited; anything else snaps to a choice. */
    fun legacyTimeout(raw: Int): Int = if (raw == 11) AutoPlaySettings.TIMEOUT_UNLIMITED else AutoPlaySettings.normalizeTimeout(raw)

    fun queueOf(settings: DesktopSettings): String = PlaybackQueue.encode(settings.episodeQueue)

    fun applyQueue(raw: String, settings: DesktopSettings): DesktopSettings = settings.copy(episodeQueue = PlaybackQueue.decode(raw))
}
