package com.fetch.tv.ui.screens.settings

import androidx.annotation.StringRes
import com.fetch.tv.R
import com.fetch.tv.data.local.AutoSkipSegmentType
import com.fetch.tv.data.local.PlayerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One searchable settings row and the part of a settings snapshot [S] it shows. The row counts as
 * changed when that part differs between the profile's settings and the defaults.
 */
internal class SettingsChangeRule<S>(
    val key: SettingsSearchKey,
    private val value: (S) -> Any?
) {
    fun isChanged(current: S, default: S): Boolean = value(current) != value(default)
}

internal fun <S> rule(category: SettingsCategory, @StringRes title: Int, value: (S) -> Any?) =
    SettingsChangeRule(SettingsSearchKey(category, title), value)

/** The rows of [rules] whose value in this flow's snapshot differs from [default]. */
internal fun <S> Flow<S>.changedRows(default: S, rules: List<SettingsChangeRule<S>>): Flow<Set<SettingsSearchKey>> =
    map { current -> rules.filter { it.isChanged(current, default) }.mapTo(HashSet()) { it.key } }
        .distinctUntilChanged()

/** A row backed by a flow of its own, changed when the flow's value isn't [default]. */
internal fun <T> Flow<T>.changedRow(key: SettingsSearchKey, default: T): Flow<Set<SettingsSearchKey>> =
    map { value -> if (value != default) setOf(key) else emptySet() }.distinctUntilChanged()

// Rows that combine fields with others (downmix only applying to the FFmpeg decoder, say) compare
// the field the row edits, not the combination it shows: that is what the profile changed.
internal val playerSettingsChangeRules: List<SettingsChangeRule<PlayerSettings>> = buildList {
    val essential = SettingsCategory.PLAYBACK
    add(rule(essential, R.string.essential_stream_selection) { it.streamAutoPlayMode })
    add(rule(essential, R.string.essential_autoplay_next_episode) { it.streamAutoPlayNextEpisodeEnabled })
    add(rule(essential, R.string.autoplay_post_play_recommendations) { it.postPlayRecommendationsEnabled })
    add(rule(essential, R.string.essential_subtitle_language) {
        it.subtitleStyle.preferredLanguage to it.subtitleStyle.isPreferredLanguageSystemDefault
    })
    add(rule(essential, R.string.sub_use_forced_subtitles) { it.subtitleStyle.useForcedSubtitles })
    add(rule(essential, R.string.essential_audio_language) { it.preferredAudioLanguage })
    add(rule(essential, R.string.audio_decoder_priority) { it.decoderPriority })
    add(rule(essential, R.string.playback_afr_mode) { it.frameRateMatchingMode })

    val player = SettingsCategory.PLAYBACK_PLAYER
    add(rule(player, R.string.playback_default_player) { it.playerPreference })
    add(rule(player, R.string.playback_internal_player_engine) { it.internalPlayerEngine })
    add(rule(player, R.string.playback_auto_switch_internal_player_on_error) { it.autoSwitchInternalPlayerOnError })
    add(rule(player, R.string.playback_external_forward_subtitles) { it.externalPlayerForwardSubtitles })
    add(rule(player, R.string.playback_external_send_skip_segments) { it.externalPlayerSendSkipSegments })

    val streams = SettingsCategory.PLAYBACK_STREAM_SELECTION
    add(rule(streams, R.string.autoplay_stream_selection) { it.streamAutoPlayMode })
    add(rule(streams, R.string.autoplay_start_early_title) { it.streamAutoPlayStartEarly })
    add(rule(streams, R.string.best_match_preview_title) { it.bestMatchPreviewEnabled })
    add(rule(streams, R.string.ready_check_title) { it.readyCheckEnabled })
    add(rule(streams, R.string.autoplay_regex_title) { it.streamAutoPlayRegex })
    add(rule(streams, R.string.autoplay_allowed_addons) { it.streamAutoPlaySelectedAddons })
    add(rule(streams, R.string.autoplay_timeout_title) { it.streamAutoPlayTimeoutSeconds })
    add(rule(streams, R.string.autoplay_preload_next_episode) { it.preloadNextEpisodeSources })
    add(rule(streams, R.string.autoplay_reuse_last_link) { it.streamReuseLastLinkEnabled })
    add(rule(streams, R.string.autoplay_last_link_cache) { it.streamReuseLastLinkCacheHours })
    add(rule(streams, R.string.ranking_preferred_quality) { it.streamRankingRules.preferredResolution })
    add(rule(streams, R.string.ranking_preferred_codec) { it.streamRankingRules.preferredCodecs })
    add(rule(streams, R.string.ranking_preferred_size) { it.streamRankingRules.preferredMaxSizeGb })
    add(rule(streams, R.string.ranking_preferred_languages) { it.streamRankingRules.preferredLanguages })
    add(rule(streams, R.string.ranking_preferred_groups) { it.streamRankingRules.preferredReleaseGroups })
    add(rule(streams, R.string.ranking_skip_dv5) { 5 in it.streamRankingRules.excludedDolbyVisionProfiles })
    add(rule(streams, R.string.ranking_prefer_cached) { it.streamRankingRules.preferCachedDebrid })
    add(rule(streams, R.string.ranking_prefer_library) { it.streamRankingRules.preferOwnLibrary })
    add(rule(streams, R.string.ranking_max_quality) { it.streamRankingRules.maxResolution })
    add(rule(streams, R.string.ranking_max_size) { it.streamRankingRules.maxSizeGb })
    add(rule(streams, R.string.ranking_min_size) { it.streamRankingRules.minSizeGb })
    add(rule(streams, R.string.ranking_excluded_languages) { it.streamRankingRules.excludedLanguages })
    add(rule(streams, R.string.ranking_excluded_sources) { it.streamRankingRules.excludedSources })
    add(rule(streams, R.string.ranking_excluded_audio) { it.streamRankingRules.excludedAudio })
    add(rule(streams, R.string.ranking_excluded_groups) { it.streamRankingRules.excludedReleaseGroups })
    add(rule(streams, R.string.ranking_max_results) { it.streamRankingRules.maxResults })
    add(rule(streams, R.string.ranking_max_per_resolution) { it.streamRankingRules.maxPerResolution })
    add(rule(streams, R.string.ranking_hide_cam) { it.streamRankingRules.excludeCamAndTelesync })
    add(rule(streams, R.string.ranking_drop_undecodable) { it.streamRankingRules.dropUndecodable })

    val upNext = SettingsCategory.PLAYBACK_UP_NEXT
    add(rule(upNext, R.string.autoplay_next_episode) { it.streamAutoPlayNextEpisodeEnabled })
    add(rule(upNext, R.string.autoplay_next_episode_fallback) { it.streamAutoPlayNextEpisodeFallbackEnabled })
    add(rule(upNext, R.string.autoplay_threshold_mode) { it.nextEpisodeThresholdMode })
    add(rule(upNext, R.string.autoplay_threshold_pct_title) { it.nextEpisodeThresholdPercent })
    add(rule(upNext, R.string.autoplay_threshold_min_title) { it.nextEpisodeThresholdMinutesBeforeEnd })
    add(rule(upNext, R.string.still_watching_setting_title) { it.stillWatchingEnabled })
    add(rule(upNext, R.string.still_watching_threshold_title) { it.stillWatchingEpisodeThreshold })
    add(rule(upNext, R.string.autoplay_prefer_binge_group) { it.streamAutoPlayPreferBingeGroupForNextEpisode })
    add(rule(upNext, R.string.autoplay_reuse_binge_group) { it.streamAutoPlayReuseBingeGroup })
    add(rule(upNext, R.string.autoplay_post_play_recommendations) { it.postPlayRecommendationsEnabled })
    add(rule(upNext, R.string.autoplay_post_play_movie_threshold) { it.postPlayMovieThresholdPercent })

    val skip = SettingsCategory.PLAYBACK_SKIP_SEGMENTS
    add(rule(skip, R.string.playback_skip_intro) { it.skipIntroEnabled })
    add(rule(skip, R.string.auto_skip_intro) { AutoSkipSegmentType.INTRO in it.autoSkipSegmentTypes })
    add(rule(skip, R.string.auto_skip_recap) { AutoSkipSegmentType.RECAP in it.autoSkipSegmentTypes })
    add(rule(skip, R.string.auto_skip_outro) { AutoSkipSegmentType.OUTRO in it.autoSkipSegmentTypes })
    add(rule(skip, R.string.auto_skip_movie_credits) { AutoSkipSegmentType.MOVIE_CREDITS in it.autoSkipSegmentTypes })

    val ui = SettingsCategory.PLAYBACK_PLAYER_INTERFACE
    add(rule(ui, R.string.playback_loading_overlay) { it.loadingOverlayEnabled })
    add(rule(ui, R.string.playback_show_loading_status) { it.showPlayerLoadingStatus })
    add(rule(ui, R.string.playback_pause_overlay) { it.pauseOverlayEnabled })
    add(rule(ui, R.string.playback_osd_clock) { it.osdClockEnabled })
    add(rule(ui, R.string.playback_parental_guide) { it.parentalGuideEnabled })

    val audio = SettingsCategory.PLAYBACK_AUDIO
    add(rule(audio, R.string.audio_preferred_lang) { it.preferredAudioLanguage })
    add(rule(audio, R.string.sub_secondary_lang) { it.secondaryPreferredAudioLanguage })
    add(rule(audio, R.string.audio_skip_silence) { it.skipSilence })
    add(rule(audio, R.string.audio_remember_delay_per_device) { it.rememberAudioDelayPerDevice })
    add(rule(audio, R.string.audio_decoder_priority) { it.decoderPriority })
    add(rule(audio, R.string.audio_enable_downmix_title) { it.downmixEnabled })
    add(rule(audio, R.string.audio_number_of_channels) { it.audioOutputChannels })
    add(rule(audio, R.string.audio_maintain_original_audio_on_downmix_title) { it.maintainOriginalAudioOnDownmix })
    add(rule(audio, R.string.audio_tunneled) { it.tunnelingEnabled })
    add(rule(audio, R.string.audio_force_optical_passthrough) { it.forceOpticalPassthrough })

    val subtitles = SettingsCategory.PLAYBACK_SUBTITLES
    add(rule(subtitles, R.string.sub_preferred_lang) {
        it.subtitleStyle.preferredLanguage to it.subtitleStyle.isPreferredLanguageSystemDefault
    })
    add(rule(subtitles, R.string.sub_secondary_lang) { it.subtitleStyle.secondaryPreferredLanguage })
    add(rule(subtitles, R.string.sub_use_forced_subtitles) { it.subtitleStyle.useForcedSubtitles })
    add(rule(subtitles, R.string.sub_show_only_preferred_languages) { it.subtitleStyle.showOnlyPreferredLanguages })
    add(rule(subtitles, R.string.sub_strip_sdh) { it.subtitleStyle.stripSdh })
    add(rule(subtitles, R.string.sub_size) { it.subtitleStyle.size })
    add(rule(subtitles, R.string.sub_vertical_offset) { it.subtitleStyle.verticalOffset })
    add(rule(subtitles, R.string.sub_bold) { it.subtitleStyle.bold })
    add(rule(subtitles, R.string.sub_text_color) { it.subtitleStyle.textColor })
    add(rule(subtitles, R.string.sub_bg_color) { it.subtitleStyle.backgroundColor })
    add(rule(subtitles, R.string.sub_outline) { it.subtitleStyle.outlineEnabled })
    add(rule(subtitles, R.string.sub_outline_color) { it.subtitleStyle.outlineColor })
    add(rule(subtitles, R.string.sub_hdr_style) { it.subtitleStyle.hdrStyleEnabled })
    add(rule(subtitles, R.string.sub_hdr_text_color) { it.subtitleStyle.hdrTextColor })
    add(rule(subtitles, R.string.sub_hdr_brightness) { it.subtitleStyle.hdrBrightness })
    add(rule(subtitles, R.string.sub_libass) { it.useLibass })
    add(rule(subtitles, R.string.sub_libass_mode) { it.libassRenderType })

    val video = SettingsCategory.PLAYBACK_VIDEO
    add(rule(video, R.string.playback_afr_mode) { it.frameRateMatchingMode })
    add(rule(video, R.string.playback_resolution_matching) { it.resolutionMatchingEnabled })
    add(rule(video, R.string.dv7_handling_title) { it.dv7HandlingMode })
    add(rule(video, R.string.audio_dv7_preserve_mapping_title) { it.dv7ToDv81PreserveMappingEnabled })
    add(rule(video, R.string.audio_dv5_to_dv81_title) { it.dv5ToDv81Enabled })
    add(rule(video, R.string.audio_strip_hdr10plus_title) { it.stripHdr10PlusSei })
    add(rule(video, R.string.audio_mpv_hwdec_title) { it.mpvHardwareDecodeMode })
    add(rule(video, R.string.audio_mpv_hi10p_gnext_sw_title) { it.mpvHi10pGnextSoftwareFallbackEnabled })

    val buffer = SettingsCategory.PLAYBACK_BUFFER_NETWORK
    add(rule(buffer, R.string.playback_net_fetch_performance_mode) { it.fetchPerformanceModeEnabled })
    add(rule(buffer, R.string.playback_buffer_custom) { it.bufferEngineEnabled })
    add(rule(buffer, R.string.playback_net_custom) { it.parallelNetworkEnabled })
    add(rule(buffer, R.string.playback_buffer_min) { it.bufferSettings.minBufferMs })
    add(rule(buffer, R.string.playback_buffer_max) { it.bufferSettings.maxBufferMs })
    add(rule(buffer, R.string.playback_buffer_initial) { it.bufferSettings.bufferForPlaybackMs })
    add(rule(buffer, R.string.playback_buffer_after_rebuffer) { it.bufferSettings.bufferForPlaybackAfterRebufferMs })
    add(rule(buffer, R.string.playback_buffer_back) { it.bufferSettings.backBufferDurationMs })
    add(rule(buffer, R.string.playback_buffer_managed) { it.bufferBudgetManaged })
    add(rule(buffer, R.string.playback_buffer_target) { it.bufferSettings.targetBufferSizeMb })
    add(rule(buffer, R.string.playback_buffer_allow_large) { it.allowLargeTargetBuffer })
    add(rule(buffer, R.string.playback_cache_vod) { it.vodCacheEnabled })
    add(rule(buffer, R.string.playback_cache_auto_size) { it.vodCacheSizeMode })
    add(rule(buffer, R.string.playback_cache_vod_size) { it.vodCacheSizeMb })
    add(rule(buffer, R.string.playback_net_parallel) { it.useParallelConnections })
    add(rule(buffer, R.string.playback_net_connection_count) { it.parallelConnectionCount })
    add(rule(buffer, R.string.playback_net_chunk_size) { it.parallelChunkSizeKb })

    val advanced = SettingsCategory.ADVANCED
    add(rule(advanced, R.string.advanced_playback_issue_reports) { it.playbackIssueReportsEnabled })
    add(rule(advanced, R.string.dv7_libdovi_mode_row_title) { it.dv7LibdoviModeOverride })
}
