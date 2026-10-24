package com.fetch.tv.core.streams

import androidx.compose.runtime.Immutable
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.player.StreamAutoPlaySelector
import com.fetch.tv.core.streams.ranking.ExclusionReason
import com.fetch.tv.core.streams.ranking.StreamDescriptor
import com.fetch.tv.core.streams.ranking.StreamTitleParser
import com.fetch.tv.data.local.BingeGroupCacheDataStore
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.local.StreamAutoPlayMode
import com.fetch.tv.data.local.StreamLinkCacheDataStore
import com.fetch.tv.data.local.BestMatchCacheDataStore
import com.fetch.tv.domain.model.AddonStreams
import com.fetch.tv.domain.model.enabledAddons
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.domain.repository.StreamRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** What "Best match" will play for a video, shown on the Play button before it is pressed. */
@Immutable
data class BestMatchPreview(
    val videoId: String,
    /** What matters about the pick, most important first ("4K", "DV P8", "TrueHD Atmos", "18.2 GB"). */
    val labels: List<String>,
    /** [labels] without the size, for a one-line spec ("4K · DV · Atmos"). */
    val specLabels: List<String> = labels,
    val isCached: Boolean,
    val addonName: String?,
    val sourceCount: Int,
    /** False while addons are still answering; the pick can still change. */
    val isFinal: Boolean,
    /** A better-looking file Best match passed over, and why, so the pick is easier to trust. */
    val skipped: SkippedStream? = null
)

/** A file Best match ruled out: its picture ("4K DV P5") and the rule or device limit that did it. */
@Immutable
data class SkippedStream(
    val pictureLabels: List<String>,
    val reason: ExclusionReason,
    val descriptor: StreamDescriptor
)

/**
 * Runs the same stream search and the same auto-play selection the Stream screen runs, so the
 * Play button can say what it will start. The search goes through [StreamRepository]'s session
 * cache, so pressing Play afterwards joins it instead of asking every addon again.
 */
@Singleton
class BestMatchPreviewer @Inject constructor(
    private val streamRepository: StreamRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val addonRepository: AddonRepository,
    private val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val bestMatchCacheDataStore: BestMatchCacheDataStore
) {
    /**
     * Emits the best pick so far as addons answer, ending with a final one. Emits nothing when
     * the Stream screen would choose by something other than ranking (another auto-play mode, a
     * reused link or a remembered binge group), since the preview could then name the wrong file.
     */
    fun preview(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        contentId: String?
    ): Flow<BestMatchPreview> = flow {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (settings.streamAutoPlayMode != StreamAutoPlayMode.BEST_MATCH) return@flow
        if (!settings.bestMatchPreviewEnabled) return@flow
        if (settings.streamReuseLastLinkEnabled &&
            streamLinkCacheDataStore.getValid(
                contentKey = "${type.lowercase()}|$videoId",
                maxAgeMs = settings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
            ) != null
        ) {
            return@flow
        }
        if (settings.streamAutoPlayPreferBingeGroupForNextEpisode &&
            settings.streamAutoPlayReuseBingeGroup &&
            contentId != null &&
            bingeGroupCacheDataStore.get(contentId) != null
        ) {
            return@flow
        }

        val installedAddonNames = addonRepository.getInstalledAddons().first()
            .enabledAddons()
            .map { it.displayName }
            .toSet()

        fun pick(groups: List<AddonStreams>, isFinal: Boolean): BestMatchPreview? {
            val streams = groups.flatMap { it.streams }
            val stream = StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = settings.streamAutoPlayMode,
                regexPattern = settings.streamAutoPlayRegex,
                installedAddonNames = installedAddonNames,
                selectedAddons = settings.streamAutoPlaySelectedAddons,
                rankingRules = settings.streamRankingRules
            ) ?: return null
            val descriptor: StreamDescriptor = StreamTitleParser.parse(stream)
            // Only once every addon has answered: the comparison costs a second ranking pass.
            val skipped = if (isFinal) {
                StreamAutoPlaySelector.skippedBetterLooking(
                    streams = streams,
                    picked = stream,
                    installedAddonNames = installedAddonNames,
                    selectedAddons = settings.streamAutoPlaySelectedAddons,
                    rankingRules = settings.streamRankingRules
                )?.let { SkippedStream(it.descriptor.pictureLabels(), it.exclusion ?: return@let null, it.descriptor) }
            } else {
                null
            }
            return BestMatchPreview(
                videoId = videoId,
                labels = descriptor.playButtonLabels(),
                specLabels = descriptor.specLineLabels(),
                isCached = descriptor.isDebridCached,
                addonName = stream.addonName.takeIf { it.isNotBlank() },
                sourceCount = streams.size,
                isFinal = isFinal,
                skipped = skipped
            )
        }

        var latest: List<AddonStreams> = emptyList()
        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = videoId,
            season = season,
            episode = episode
        ).collect { result ->
            if (result is NetworkResult.Success) {
                latest = result.data
                pick(latest, isFinal = false)?.let { emit(it) }
            }
        }
        pick(latest, isFinal = true)?.let { final ->
            // Home's hero reads this instead of asking addons again.
            bestMatchCacheDataStore.save(type, videoId, final.specLabels, final.isCached)
            emit(final)
        }
    }
}

/** A shorter cut of [StreamDescriptor.summaryLabels] for one line: picture, HDR, sound, size. */
internal fun StreamDescriptor.playButtonLabels(): List<String> {
    val all = summaryLabels()
    val codecAndSource = setOf(videoCodec.label, source.label).filter { it.isNotEmpty() }
    return all.filterNot { it in codecAndSource }
}

/** [playButtonLabels] without the size: what a spec line names ("4K · DV · Atmos"). */
internal fun StreamDescriptor.specLineLabels(): List<String> {
    val size = sizeBytes?.let(StreamDescriptor::formatSize)
    return playButtonLabels().filterNot { it == size }
}
