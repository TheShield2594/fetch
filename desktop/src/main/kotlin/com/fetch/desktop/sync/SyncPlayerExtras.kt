package com.fetch.desktop.sync

import com.fetch.desktop.data.PlayerExtras
import com.fetch.desktop.data.SubtitleOrganization
import com.fetch.desktop.player.Argb
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The [PlayerExtras] the TV syncs, with its keys. Null means "not in the blob" on a pull and
 * "leave as it is" on a push. The Anime-Skip client id, centre mix level, audio delay per device,
 * stats overlay, auto sync and 4K trailers stay on each device, as on TV.
 */
data class RemotePlayerExtras(
    /** `player_settings.subtitle_show_only_preferred_languages`. */
    val subtitleOnlyPreferred: Boolean? = null,
    /** `player_settings.subtitle_organization_mode`: NONE, BY_LANGUAGE or BY_ADDON. */
    val subtitleOrganization: String? = null,
    /** `player_settings.subtitle_hdr_style_enabled`. */
    val subtitleHdrStyle: Boolean? = null,
    /** `player_settings.subtitle_hdr_text_color`, an ARGB int. */
    val subtitleHdrColor: Int? = null,
    /** `player_settings.subtitle_hdr_brightness`, 0–100. */
    val subtitleHdrBrightness: Int? = null,
    /** `player_settings.skip_silence`. */
    val skipSilence: Boolean? = null,
    /** `animeskip_settings.animeskip_enabled`. */
    val animeSkipEnabled: Boolean? = null,
    /** `trailer_settings.trailer_enabled`. */
    val trailerEnabled: Boolean? = null,
    /** `trailer_settings.trailer_delay_seconds`. */
    val trailerDelaySeconds: Int? = null
) {
    /** These over [extras]; what's null keeps its value. */
    fun applyTo(extras: PlayerExtras): PlayerExtras = extras.copy(
        subtitleOnlyPreferredLanguages = subtitleOnlyPreferred ?: extras.subtitleOnlyPreferredLanguages,
        subtitleOrganization = subtitleOrganization?.let { name -> SubtitleOrganization.entries.firstOrNull { it.name == name } } ?: extras.subtitleOrganization,
        subtitleHdrStyle = subtitleHdrStyle ?: extras.subtitleHdrStyle,
        subtitleHdrColor = subtitleHdrColor?.let(Argb::format) ?: extras.subtitleHdrColor,
        subtitleHdrBrightness = subtitleHdrBrightness?.coerceIn(0, 100) ?: extras.subtitleHdrBrightness,
        skipSilence = skipSilence ?: extras.skipSilence,
        animeSkipEnabled = animeSkipEnabled ?: extras.animeSkipEnabled,
        trailerAutoplay = trailerEnabled ?: extras.trailerAutoplay,
        trailerDelaySeconds = trailerDelaySeconds?.coerceIn(0, 60) ?: extras.trailerDelaySeconds
    )

    /** Only what differs from [before]; null when nothing does. */
    fun changedFrom(before: RemotePlayerExtras?): RemotePlayerExtras? {
        val then = before ?: RemotePlayerExtras()
        fun <T> pick(now: T?, old: T?): T? = now.takeIf { it != old }
        val diff = RemotePlayerExtras(
            subtitleOnlyPreferred = pick(subtitleOnlyPreferred, then.subtitleOnlyPreferred),
            subtitleOrganization = pick(subtitleOrganization, then.subtitleOrganization),
            subtitleHdrStyle = pick(subtitleHdrStyle, then.subtitleHdrStyle),
            subtitleHdrColor = pick(subtitleHdrColor, then.subtitleHdrColor),
            subtitleHdrBrightness = pick(subtitleHdrBrightness, then.subtitleHdrBrightness),
            skipSilence = pick(skipSilence, then.skipSilence),
            animeSkipEnabled = pick(animeSkipEnabled, then.animeSkipEnabled),
            trailerEnabled = pick(trailerEnabled, then.trailerEnabled),
            trailerDelaySeconds = pick(trailerDelaySeconds, then.trailerDelaySeconds)
        )
        return diff.takeUnless { it == RemotePlayerExtras() }
    }

    /** The blob entries to write, as (feature, key, typed value). */
    fun edits(): List<Triple<String, String, JsonObject>> = buildList {
        fun bool(feature: String, key: String, v: Boolean?) { if (v != null) add(Triple(feature, key, typed("boolean", JsonPrimitive(v)))) }
        fun int(feature: String, key: String, v: Int?) { if (v != null) add(Triple(feature, key, typed("int", JsonPrimitive(v)))) }
        fun string(feature: String, key: String, v: String?) { if (v != null) add(Triple(feature, key, typed("string", JsonPrimitive(v)))) }
        bool(PLAYER, "subtitle_show_only_preferred_languages", subtitleOnlyPreferred)
        string(PLAYER, "subtitle_organization_mode", subtitleOrganization)
        bool(PLAYER, "subtitle_hdr_style_enabled", subtitleHdrStyle)
        int(PLAYER, "subtitle_hdr_text_color", subtitleHdrColor)
        int(PLAYER, "subtitle_hdr_brightness", subtitleHdrBrightness)
        bool(PLAYER, "skip_silence", skipSilence)
        bool(ANIMESKIP, "animeskip_enabled", animeSkipEnabled)
        bool(TRAILER, "trailer_enabled", trailerEnabled)
        int(TRAILER, "trailer_delay_seconds", trailerDelaySeconds)
    }

    companion object {
        const val PLAYER = SyncSettingsBlob.PLAYER
        const val ANIMESKIP = "animeskip_settings"
        const val TRAILER = "trailer_settings"

        fun from(extras: PlayerExtras): RemotePlayerExtras = RemotePlayerExtras(
            subtitleOnlyPreferred = extras.subtitleOnlyPreferredLanguages,
            subtitleOrganization = extras.subtitleOrganization.name,
            subtitleHdrStyle = extras.subtitleHdrStyle,
            subtitleHdrColor = Argb.parse(extras.subtitleHdrColor) ?: Argb.WHITE,
            subtitleHdrBrightness = extras.subtitleHdrBrightness,
            skipSilence = extras.skipSilence,
            animeSkipEnabled = extras.animeSkipEnabled,
            trailerEnabled = extras.trailerAutoplay,
            trailerDelaySeconds = extras.trailerDelaySeconds
        )

        /** What the blob's `features` hold of these; null when none of them. */
        fun read(features: JsonObject?): RemotePlayerExtras? {
            fun value(feature: String, key: String, type: String): JsonElement? {
                val entry = (features?.get(feature) as? JsonObject)?.get(key) as? JsonObject ?: return null
                if ((entry["type"] as? JsonPrimitive)?.contentOrNull != type) return null
                return entry["value"]?.takeUnless { it is JsonNull }
            }
            fun bool(f: String, k: String) = (value(f, k, "boolean") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            fun int(f: String, k: String) = (value(f, k, "int") as? JsonPrimitive)?.intOrNull
            fun string(f: String, k: String) = (value(f, k, "string") as? JsonPrimitive)?.contentOrNull
            return RemotePlayerExtras(
                subtitleOnlyPreferred = bool(PLAYER, "subtitle_show_only_preferred_languages"),
                subtitleOrganization = string(PLAYER, "subtitle_organization_mode"),
                subtitleHdrStyle = bool(PLAYER, "subtitle_hdr_style_enabled"),
                subtitleHdrColor = int(PLAYER, "subtitle_hdr_text_color"),
                subtitleHdrBrightness = int(PLAYER, "subtitle_hdr_brightness"),
                skipSilence = bool(PLAYER, "skip_silence"),
                animeSkipEnabled = bool(ANIMESKIP, "animeskip_enabled"),
                trailerEnabled = bool(TRAILER, "trailer_enabled"),
                trailerDelaySeconds = int(TRAILER, "trailer_delay_seconds")
            ).takeUnless { it == RemotePlayerExtras() }
        }

        private fun typed(type: String, value: JsonPrimitive) = buildJsonObject {
            put("type", type)
            put("value", value)
        }
    }
}
