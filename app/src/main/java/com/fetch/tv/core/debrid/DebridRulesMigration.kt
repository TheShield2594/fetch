package com.fetch.tv.core.debrid

import android.util.Log
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.streams.ranking.AudioFormat
import com.fetch.tv.core.streams.ranking.ReleaseSource
import com.fetch.tv.core.streams.ranking.StreamLanguage
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.core.streams.ranking.StreamResolution
import com.fetch.tv.core.streams.ranking.VideoCodec
import com.fetch.tv.data.local.DebridSettingsDataStore
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.domain.model.DebridStreamAudioTag
import com.fetch.tv.domain.model.DebridStreamEncode
import com.fetch.tv.domain.model.DebridStreamPreferences
import com.fetch.tv.domain.model.DebridStreamQuality
import com.fetch.tv.domain.model.DebridStreamResolution
import com.fetch.tv.domain.model.DebridStreamVisualTag
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debrid stream rules used to be a second rules model that only applied to debrid results and
 * hid what it filtered. They now live in [StreamRankingRules] with everything else, so each
 * profile's saved debrid rules are folded in once and then cleared.
 */
@Singleton
class DebridRulesMigration @Inject constructor(
    private val debridSettings: DebridSettingsDataStore,
    private val playerSettings: PlayerSettingsDataStore,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            profileManager.activeProfileId.collect { profileId ->
                try {
                    migrate(profileId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Moving debrid stream rules for profile $profileId failed", error)
                }
            }
        }
    }

    private suspend fun migrate(profileId: Int) {
        val preferences = debridSettings.settings.first().streamPreferences
        if (preferences == DebridStreamPreferences()) return
        val rules = playerSettings.playerSettings.first().streamRankingRules
        playerSettings.setStreamRankingRules(merge(rules, preferences))
        debridSettings.clearStreamRules()
        Log.i(TAG, "Moved debrid stream rules into stream ranking for profile $profileId")
    }

    companion object {
        private const val TAG = "DebridRulesMigration"

        /**
         * [rules] with [preferences] added. What both set keeps the stricter or the ranking
         * value; debrid sort orders and audio channel rules have no ranking equivalent and are
         * dropped, since Best match orders streams by score.
         */
        fun merge(rules: StreamRankingRules, preferences: DebridStreamPreferences): StreamRankingRules {
            val default = DebridStreamPreferences()
            var merged = rules

            val required = preferences.requiredResolutions.mapNotNull { it.toRanking() }
            if (required.isNotEmpty()) {
                val highest = required.maxBy { it.height }
                val lowest = required.minBy { it.height }
                if (highest.height < merged.maxResolution.height) merged = merged.copy(maxResolution = highest)
                if (lowest.height > merged.minResolution.height) merged = merged.copy(minResolution = lowest)
            }
            merged = merged.copy(
                excludedResolutions = merged.excludedResolutions + preferences.excludedResolutions.mapNotNull { it.toRanking() }
            )
            if (preferences.preferredResolutions != default.preferredResolutions &&
                merged.preferredResolution == StreamRankingRules().preferredResolution
            ) {
                preferences.preferredResolutions.firstNotNullOfOrNull { it.toRanking() }
                    ?.let { merged = merged.copy(preferredResolution = it) }
            }

            val requiredSources = preferences.requiredQualities.mapNotNull { it.toRanking() }.toSet()
            val sourcesOutsideRequired = if (requiredSources.isEmpty()) {
                emptySet()
            } else {
                ReleaseSource.entries.filter { it != ReleaseSource.UNKNOWN && it !in requiredSources }.toSet()
            }
            merged = merged.copy(
                excludedSources = merged.excludedSources + sourcesOutsideRequired +
                    preferences.excludedQualities.mapNotNull { it.toRanking() }
            )

            val visual = preferences.excludedVisualTags
            if (visual.any { it in DOLBY_VISION_TAGS }) merged = merged.copy(excludeDolbyVision = true)
            if (visual.any { it in HDR_TAGS }) merged = merged.copy(excludeHdr = true)
            if (DebridStreamVisualTag.THREE_D in visual) merged = merged.copy(exclude3d = true)
            if (preferences.requiredVisualTags.any { it in DOLBY_VISION_TAGS || it in HDR_TAGS }) {
                merged = merged.copy(preferHdr = true)
            }

            val requiredAudio = preferences.requiredAudioTags.mapNotNull { it.toRanking() }.toSet()
            val audioOutsideRequired = if (requiredAudio.isEmpty()) emptySet() else AudioFormat.entries.toSet() - requiredAudio
            merged = merged.copy(
                excludedAudio = merged.excludedAudio + audioOutsideRequired +
                    preferences.excludedAudioTags.mapNotNull { it.toRanking() }
            )
            if (preferences.preferredAudioTags != default.preferredAudioTags && merged.preferredAudio.isEmpty()) {
                merged = merged.copy(preferredAudio = preferences.preferredAudioTags.mapNotNull { it.toRanking() }.distinct())
            }

            val requiredCodecs = preferences.requiredEncodes.mapNotNull { it.toRanking() }.toSet()
            val codecsOutsideRequired = if (requiredCodecs.isEmpty()) {
                emptySet()
            } else {
                VideoCodec.entries.filter { it != VideoCodec.UNKNOWN && it !in requiredCodecs }.toSet()
            }
            merged = merged.copy(
                excludedCodecs = merged.excludedCodecs + codecsOutsideRequired +
                    preferences.excludedEncodes.mapNotNull { it.toRanking() }
            )
            if (preferences.preferredEncodes != default.preferredEncodes && merged.preferredCodecs.isEmpty()) {
                merged = merged.copy(preferredCodecs = preferences.preferredEncodes.mapNotNull { it.toRanking() }.distinct())
            }

            merged = merged.copy(
                preferredLanguages = (merged.preferredLanguages + preferences.preferredLanguages.mapNotNull { StreamLanguage.fromCode(it.code) }).distinct(),
                requiredLanguages = merged.requiredLanguages + preferences.requiredLanguages.mapNotNull { StreamLanguage.fromCode(it.code) },
                excludedLanguages = merged.excludedLanguages + preferences.excludedLanguages.mapNotNull { StreamLanguage.fromCode(it.code) },
                // Ranking has no "only these groups": a required group becomes a strong preference.
                preferredReleaseGroups = (merged.preferredReleaseGroups + preferences.requiredReleaseGroups)
                    .distinctBy { it.lowercase() },
                excludedReleaseGroups = (merged.excludedReleaseGroups + preferences.excludedReleaseGroups)
                    .distinctBy { it.lowercase() }
            )

            if (preferences.sizeMinGb > 0) {
                merged = merged.copy(minSizeGb = maxOf(merged.minSizeGb ?: 0.0, preferences.sizeMinGb.toDouble()))
            }
            if (preferences.sizeMaxGb > 0) {
                merged = merged.copy(maxSizeGb = minOf(merged.maxSizeGb ?: Double.MAX_VALUE, preferences.sizeMaxGb.toDouble()))
            }
            return merged.copy(
                maxResults = stricterLimit(merged.maxResults, preferences.maxResults),
                maxPerResolution = stricterLimit(merged.maxPerResolution, preferences.maxPerResolution),
                maxPerSource = stricterLimit(merged.maxPerSource, preferences.maxPerQuality)
            )
        }

        private val DOLBY_VISION_TAGS = setOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.DV_ONLY, DebridStreamVisualTag.HDR_DV)
        private val HDR_TAGS = setOf(
            DebridStreamVisualTag.HDR,
            DebridStreamVisualTag.HDR10,
            DebridStreamVisualTag.HDR10_PLUS,
            DebridStreamVisualTag.HLG,
            DebridStreamVisualTag.HDR_ONLY
        )

        private fun stricterLimit(current: Int, other: Int): Int = when {
            current <= 0 -> other.coerceAtLeast(0)
            other <= 0 -> current
            else -> minOf(current, other)
        }

        private fun DebridStreamResolution.toRanking(): StreamResolution? =
            StreamResolution.entries.firstOrNull { it.height == value && it != StreamResolution.UNKNOWN }

        private fun DebridStreamQuality.toRanking(): ReleaseSource? = when (this) {
            DebridStreamQuality.BLURAY_REMUX -> ReleaseSource.REMUX
            DebridStreamQuality.BLURAY -> ReleaseSource.BLURAY
            DebridStreamQuality.WEB_DL -> ReleaseSource.WEB_DL
            DebridStreamQuality.WEBRIP -> ReleaseSource.WEBRIP
            DebridStreamQuality.DVDRIP -> ReleaseSource.DVD
            DebridStreamQuality.HDTV -> ReleaseSource.HDTV
            DebridStreamQuality.CAM -> ReleaseSource.CAM
            DebridStreamQuality.TS,
            DebridStreamQuality.TC -> ReleaseSource.TELESYNC
            DebridStreamQuality.HDRIP,
            DebridStreamQuality.HD_RIP,
            DebridStreamQuality.SCR,
            DebridStreamQuality.UNKNOWN -> null
        }

        private fun DebridStreamAudioTag.toRanking(): AudioFormat? = when (this) {
            DebridStreamAudioTag.DD_PLUS -> AudioFormat.EAC3
            DebridStreamAudioTag.DD -> AudioFormat.AC3
            DebridStreamAudioTag.DTS_X -> AudioFormat.DTS_X
            DebridStreamAudioTag.DTS_HD_MA,
            DebridStreamAudioTag.DTS_HD -> AudioFormat.DTS_HD_MA
            DebridStreamAudioTag.DTS_ES,
            DebridStreamAudioTag.DTS -> AudioFormat.DTS
            DebridStreamAudioTag.TRUEHD -> AudioFormat.TRUEHD
            DebridStreamAudioTag.OPUS -> AudioFormat.OPUS
            DebridStreamAudioTag.FLAC -> AudioFormat.FLAC
            DebridStreamAudioTag.AAC -> AudioFormat.AAC
            DebridStreamAudioTag.ATMOS,
            DebridStreamAudioTag.UNKNOWN -> null
        }

        private fun DebridStreamEncode.toRanking(): VideoCodec? = when (this) {
            DebridStreamEncode.AV1 -> VideoCodec.AV1
            DebridStreamEncode.HEVC -> VideoCodec.HEVC
            DebridStreamEncode.AVC -> VideoCodec.AVC
            DebridStreamEncode.XVID,
            DebridStreamEncode.DIVX,
            DebridStreamEncode.UNKNOWN -> null
        }
    }
}
