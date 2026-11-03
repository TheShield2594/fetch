package com.fetch.desktop.data

import kotlinx.serialization.Serializable

/**
 * More of the TV's playback settings: anime skip sources, subtitle organisation and HDR style,
 * automatic subtitle sync, dialogue boost, skip silence, audio delay per output, the stats
 * overlay and trailers. Kept in [PlayerSettings.extras]; the TV keys each maps to are noted, and
 * the synced ones are in [com.fetch.desktop.sync.RemotePlayerExtras].
 */
@Serializable
data class PlayerExtras(
    // --- Skip Segments ---
    /** `animeskip_settings.animeskip_enabled` (synced): ask Anime-Skip too. */
    val animeSkipEnabled: Boolean = false,
    /** `animeskip_client_id`: your own Anime-Skip client id. Not synced, as on TV (a credential). */
    val animeSkipClientId: String = "",

    // --- Subtitles ---
    /** `subtitle_show_only_preferred_languages` (synced): hide other languages in the player's list. */
    val subtitleOnlyPreferredLanguages: Boolean = false,
    /** `subtitle_organization_mode` (synced): how the player's subtitle list is grouped. */
    val subtitleOrganization: SubtitleOrganization = SubtitleOrganization.BY_LANGUAGE,
    /** `subtitle_hdr_style_enabled` (synced): another text color and dimmer subtitles on HDR video. */
    val subtitleHdrStyle: Boolean = false,
    /** `subtitle_hdr_text_color` (synced, as ARGB there): "#RRGGBB" or "#AARRGGBB". */
    val subtitleHdrColor: String = "#FFFFFF",
    /** `subtitle_hdr_brightness` (synced): 0–100, mapped to 30–100 % brightness. */
    val subtitleHdrBrightness: Int = HDR_BRIGHTNESS_DEFAULT,
    /** Automatic subtitle sync (the TV keeps these on the device): match addon subtitles to the video's own. */
    val autoSync: Boolean = false,
    /** Keep the original timing when the correction is this small or smaller (ms, 0 = off). */
    val autoSyncToleranceMs: Int = 0,
    /** Thorough search: keep looking for a closer match before applying. */
    val autoSyncThorough: Boolean = true,

    // --- Audio ---
    /** `center_mix_level_db` (on the device, as on TV): dialogue (centre channel) level in a downmix, −10 to +30 dB. */
    val centerMixLevelDb: Int = 0,
    /** `skip_silence` (synced): hurry through silent stretches. */
    val skipSilence: Boolean = false,
    /** `remember_audio_delay_per_device` (on the device): the audio delay is kept for each output. */
    val rememberAudioDelayPerDevice: Boolean = true,

    // --- Advanced ---
    /** `player_stats_hud_enabled` (device only): a Stats overlay button in the built-in player's stream info. */
    val statsHudButton: Boolean = false,
    /** `player_stats_hud_active`: the overlay is on. */
    val statsHudActive: Boolean = false,

    // --- Trailers ---
    /** `trailer_enabled` (synced): play a title's trailer on its page after [trailerDelaySeconds]. */
    val trailerAutoplay: Boolean = false,
    /** `trailer_delay_seconds` (synced). */
    val trailerDelaySeconds: Int = 7,
    /** `trailer_allow_4k` (device only): trailers above 1080p. */
    val trailer4k: Boolean = false
) {
    companion object {
        const val HDR_BRIGHTNESS_DEFAULT = 40
        const val CENTER_MIX_MIN_DB = -10
        const val CENTER_MIX_MAX_DB = 30
        val autoSyncToleranceOptionsMs = listOf(0, 100, 200, 300, 400, 500)
    }
}

/** The TV's SubtitleOrganizationMode: one list, by language (the TV's language rail), or by source. */
enum class SubtitleOrganization(val label: String) {
    NONE("One list"),
    BY_LANGUAGE("By language"),
    BY_ADDON("By source")
}
