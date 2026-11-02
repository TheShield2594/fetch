package com.fetch.desktop.sync

import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Reads and edits the TV app's profile settings blob (`ProfileSettingsSyncService` there):
 *
 * ```
 * {"version":1,
 *  "features":{"layout_settings":{"poster_labels_enabled":{"type":"boolean","value":true}, ...}, ...},
 *  "parental":{"isKidsProfile":false,"maxRating":"TEENS","allowUnrated":true}}
 * ```
 *
 * Each feature is one of the TV's DataStore files and each entry one typed preference
 * (`string`, `boolean`, `int`, `long`, `float`, `double`, `string_set`). When the TV applies a
 * blob it clears every feature the blob has and keeps only the keys in it, so a writer must send
 * the whole blob back: [withSharedSettings] and [withParentalLimits] change only their own keys of
 * the blob they are given and keep everything else. A key's type must be the one the TV stores,
 * or the TV can't read it.
 */
object SyncSettingsBlob {
    /** The settings platform the TV app reads and writes; the desktop shares its blob. */
    const val PLATFORM_TV = "tv"

    const val FEATURES = "features"
    const val PARENTAL = "parental"

    const val THEME = "theme_settings"
    const val LAYOUT = "layout_settings"
    const val PLAYER = "player_settings"

    private val rankingJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** The shared settings in [blob]; fields the blob doesn't have are null. */
    fun sharedSettings(blob: JsonObject?): RemoteSharedSettings {
        val features = blob?.get(FEATURES) as? JsonObject
        fun value(feature: String, key: String): Pair<String, JsonElement>? {
            val entry = (features?.get(feature) as? JsonObject)?.get(key) as? JsonObject ?: return null
            val type = (entry["type"] as? JsonPrimitive)?.contentOrNull ?: return null
            return type to (entry["value"] ?: JsonNull)
        }
        fun string(feature: String, key: String): String? =
            value(feature, key)?.takeIf { it.first == "string" }?.second?.let { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.contentOrNull }
        fun boolean(feature: String, key: String): Boolean? =
            value(feature, key)?.takeIf { it.first == "boolean" }?.second?.let { (it as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() }
        fun stringSet(feature: String, key: String): Set<String>? =
            value(feature, key)?.takeIf { it.first == "string_set" }?.second?.let { element ->
                (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
            }

        return RemoteSharedSettings(
            theme = string(THEME, "selected_theme"),
            showContinueWatching = boolean(LAYOUT, "continue_watching_enabled"),
            showCatalogType = boolean(LAYOUT, "catalog_type_suffix_enabled"),
            showAddonName = boolean(LAYOUT, "catalog_addon_name_enabled"),
            showPosterLabels = boolean(LAYOUT, "poster_labels_enabled"),
            discoverLocation = string(LAYOUT, "discover_location"),
            audioLanguage = string(PLAYER, "preferred_audio_language"),
            subtitleLanguage = string(PLAYER, "subtitle_preferred_language"),
            rankingRules = string(PLAYER, "stream_ranking_rules")?.let { raw ->
                runCatching { rankingJson.decodeFromString(StreamRankingRules.serializer(), raw) }.getOrNull()
            },
            autoplayNextEpisode = boolean(PLAYER, "stream_auto_play_next_episode_enabled"),
            autoSkipSegments = stringSet(PLAYER, "auto_skip_segment_types"),
        )
    }

    /** [blob] (or a new one) with every non-null field of [settings] set, and everything else kept. */
    fun withSharedSettings(blob: JsonObject?, settings: RemoteSharedSettings): JsonObject {
        val edits = mutableListOf<Triple<String, String, JsonObject>>()
        fun set(feature: String, key: String, encoded: JsonObject?) {
            if (encoded != null) edits += Triple(feature, key, encoded)
        }
        set(THEME, "selected_theme", settings.theme?.let(::stringValue))
        set(LAYOUT, "continue_watching_enabled", settings.showContinueWatching?.let(::booleanValue))
        set(LAYOUT, "catalog_type_suffix_enabled", settings.showCatalogType?.let(::booleanValue))
        set(LAYOUT, "catalog_addon_name_enabled", settings.showAddonName?.let(::booleanValue))
        set(LAYOUT, "poster_labels_enabled", settings.showPosterLabels?.let(::booleanValue))
        set(LAYOUT, "discover_location", settings.discoverLocation?.let(::stringValue))
        set(PLAYER, "preferred_audio_language", settings.audioLanguage?.let(::stringValue))
        set(PLAYER, "subtitle_preferred_language", settings.subtitleLanguage?.let(::stringValue))
        set(PLAYER, "stream_ranking_rules", settings.rankingRules?.let {
            stringValue(rankingJson.encodeToString(StreamRankingRules.serializer(), it))
        })
        set(PLAYER, "stream_auto_play_next_episode_enabled", settings.autoplayNextEpisode?.let(::booleanValue))
        set(PLAYER, "auto_skip_segment_types", settings.autoSkipSegments?.let(::stringSetValue))

        val base = blob ?: JsonObject(emptyMap())
        val features = (base[FEATURES] as? JsonObject).orEmpty().toMutableMap()
        edits.groupBy { it.first }.forEach { (feature, entries) ->
            val current = (features[feature] as? JsonObject).orEmpty().toMutableMap()
            entries.forEach { (_, key, encoded) -> current[key] = encoded }
            features[feature] = JsonObject(current)
        }
        return base.with(FEATURES, JsonObject(features))
    }

    /**
     * The parental limits in [blob], or null when it has none (written by a client that doesn't
     * sync them). As on TV, a rating this build doesn't know reads as the strictest one, so an
     * unreadable limit can't loosen anything, and "allow unrated" means nothing without a limit.
     */
    fun parentalLimits(blob: JsonObject?): RemoteParentalLimits? {
        val parental = blob?.get(PARENTAL) as? JsonObject ?: return null
        fun bool(key: String): Boolean? = (parental[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.booleanOrNull
        val rawRating = (parental["maxRating"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
        val level = rawRating?.let { CertificationLevel.fromName(it) ?: CertificationLevel.ALL_AGES }
        val allowUnrated = bool("allowUnrated") ?: true
        return RemoteParentalLimits(
            isKidsProfile = bool("isKidsProfile") ?: false,
            maxRating = level?.name,
            allowUnrated = level == null || allowUnrated,
        )
    }

    /** [blob] (or a new one) with its `parental` object set to [limits], everything else kept. */
    fun withParentalLimits(blob: JsonObject?, limits: RemoteParentalLimits): JsonObject =
        (blob ?: JsonObject(emptyMap())).with(PARENTAL, buildJsonObject {
            put("isKidsProfile", limits.isKidsProfile)
            put("maxRating", limits.maxRating)
            put("allowUnrated", limits.allowUnrated)
        })

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject {
        val map = toMutableMap()
        if ("version" !in map) map["version"] = JsonPrimitive(1)
        map[key] = value
        return JsonObject(map)
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    private fun stringValue(value: String) = buildJsonObject { put("type", "string"); put("value", value) }

    private fun booleanValue(value: Boolean) = buildJsonObject { put("type", "boolean"); put("value", value) }

    /** Sorted, as the TV app writes them. */
    private fun stringSetValue(values: Set<String>) = buildJsonObject {
        put("type", "string_set")
        put("value", JsonArray(values.sorted().map { JsonPrimitive(it) }))
    }
}
