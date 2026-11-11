package com.fetch.tv.core.streams

import androidx.compose.runtime.Immutable
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.player.StreamAutoPlaySelector
import com.fetch.tv.core.streams.ranking.ExclusionReason
import com.fetch.tv.core.streams.ranking.AudioFormat
import com.fetch.tv.core.streams.ranking.StreamDescriptor
import com.fetch.tv.core.streams.ranking.StreamTitleParser
import com.fetch.tv.data.local.BingeGroupCacheDataStore
import com.fetch.tv.data.local.PlayerSettings
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.local.StreamAutoPlayMode
import com.fetch.tv.data.local.StreamLinkCacheDataStore
import com.fetch.tv.data.local.BestMatchCacheDataStore
import com.fetch.tv.data.local.READY_WINDOW_MS
import com.fetch.tv.data.local.isFresh
import com.fetch.tv.data.mediaserver.ServerStreams
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
    /** The Play button's plain-words summary ("4K", "Dolby Vision", "Atmos"); the rest is on Sources. */
    val buttonLabels: List<String> = specLabels,
    val isCached: Boolean,
    val addonName: String?,
    val sourceCount: Int,
    /** False while addons are still answering; the pick can still change. */
    val isFinal: Boolean,
    /** A better-looking file Best match passed over, and why, so the pick is easier to trust. */
    val skipped: SkippedStream? = null,
    /**
     * False on the final answer when the search finished without a pick: no addon returned a
     * stream ([sourceCount] 0) or the rules ruled every one out. Lets the UI say so instead of
     * looking as if nothing was searched. The other fields name no file then.
     */
    val hasPick: Boolean = true,
    /**
     * What decides the file Play starts. Anything but [BestMatchDecider.RANKING] means Best match
     * is skipped, so [labels] is empty and the button says why instead of naming a file.
     */
    val decidedBy: BestMatchDecider = BestMatchDecider.RANKING
)

/** What chooses the stream Play starts when auto-play is set to Best match. */
enum class BestMatchDecider {
    /** Best match ranks every source, as previewed. */
    RANKING,
    /** "Reuse last link" replays the link saved for this video. */
    REUSED_LINK,
    /** The release group remembered from the last episode (binge group) is preferred. */
    BINGE_GROUP
}

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
    private val bestMatchCacheDataStore: BestMatchCacheDataStore,
    private val serverStreams: ServerStreams
) {
    /**
     * The pick saved for [videoId] in the last [READY_WINDOW_MS], shown as a final preview so the
     * page can name the file without asking any addon. Null when there is none, or when the
     * Stream screen would choose by something other than ranking (see [preview]).
     */
    suspend fun cached(type: String, videoId: String, contentId: String?): BestMatchPreview? {
        val settings = playerSettingsDataStore.playerSettings.first()
        when (decider(settings, type, videoId, contentId)) {
            null -> return null
            BestMatchDecider.RANKING -> Unit
            else -> return otherDecider(settings, type, videoId, contentId)
        }
        val entry = bestMatchCacheDataStore.entries.first()[BestMatchCacheDataStore.contentKey(type, videoId)]
            ?.takeIf { it.isFresh(System.currentTimeMillis()) }
            ?: return null
        return BestMatchPreview(
            videoId = videoId,
            labels = entry.labels,
            isCached = entry.isCached,
            addonName = null,
            // Not saved with the pick; the Sources button then names no count.
            sourceCount = 0,
            isFinal = true
        )
    }

    /**
     * Emits the best pick so far as addons answer, ending with a final one. Emits nothing when
     * auto-play is not Best match or the preview is off. When a reused link or a remembered binge
     * group will choose instead, emits one final preview naming that ([BestMatchPreview.decidedBy])
     * and searches nothing, since a ranked pick could name the wrong file.
     * When the search ends without a pick, the last emission has [BestMatchPreview.hasPick] false.
     */
    fun preview(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        contentId: String?
    ): Flow<BestMatchPreview> = flow {
        val settings = playerSettingsDataStore.playerSettings.first()
        when (decider(settings, type, videoId, contentId)) {
            null -> return@flow
            BestMatchDecider.RANKING -> Unit
            else -> {
                otherDecider(settings, type, videoId, contentId)?.let { emit(it) }
                return@flow
            }
        }

        val installedAddonOrder = if (serverStreams.isNativeRequest(videoId)) {
            emptyList()
        } else {
            addonRepository.getInstalledAddons().first()
                .enabledAddons()
                .map { it.displayName }
        }
        val installedAddonNames = installedAddonOrder.toSet()
        val preferredServerNames = serverStreams.preferredSourceNames(type, videoId)

        fun pick(groups: List<AddonStreams>, isFinal: Boolean): BestMatchPreview? {
            // Same order the Stream screen ranks in, so ties go to the same file Play starts.
            val streams = StreamAutoPlaySelector.orderAddonStreams(groups, installedAddonOrder, preferredServerNames)
                .flatMap { it.streams }
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
                buttonLabels = descriptor.playButtonSummary(),
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
        val final = pick(latest, isFinal = true)
        if (final != null) {
            // Home's hero reads this instead of asking addons again.
            bestMatchCacheDataStore.save(type, videoId, final.specLabels, final.isCached)
            emit(final)
        } else {
            emit(
                BestMatchPreview(
                    videoId = videoId,
                    labels = emptyList(),
                    isCached = false,
                    addonName = null,
                    sourceCount = latest.sumOf { it.streams.size },
                    isFinal = true,
                    hasPick = false
                )
            )
        }
    }

    /** The final preview for a video whose file is chosen by a reused link or a binge group. */
    private suspend fun otherDecider(
        settings: PlayerSettings,
        type: String,
        videoId: String,
        contentId: String?
    ): BestMatchPreview? {
        val decidedBy = decider(settings, type, videoId, contentId)
            ?.takeIf { it != BestMatchDecider.RANKING }
            ?: return null
        return BestMatchPreview(
            videoId = videoId,
            labels = emptyList(),
            isCached = false,
            addonName = null,
            sourceCount = 0,
            isFinal = true,
            decidedBy = decidedBy
        )
    }

    /**
     * What will choose the file when Best match and its preview are on, or null when either is
     * off (nothing to preview then).
     */
    private suspend fun decider(
        settings: PlayerSettings,
        type: String,
        videoId: String,
        contentId: String?
    ): BestMatchDecider? {
        if (settings.streamAutoPlayMode != StreamAutoPlayMode.BEST_MATCH) return null
        if (!settings.bestMatchPreviewEnabled) return null
        if (settings.streamReuseLastLinkEnabled &&
            streamLinkCacheDataStore.getValid(
                contentKey = "${type.lowercase()}|$videoId",
                maxAgeMs = settings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
            ) != null
        ) {
            return BestMatchDecider.REUSED_LINK
        }
        if (settings.streamAutoPlayPreferBingeGroupForNextEpisode &&
            settings.streamAutoPlayReuseBingeGroup &&
            contentId != null &&
            bingeGroupCacheDataStore.get(contentId) != null
        ) {
            return BestMatchDecider.BINGE_GROUP
        }
        return BestMatchDecider.RANKING
    }
}

/** A shorter cut of [StreamDescriptor.summaryLabels] for one line: picture, HDR, sound, size. */
internal fun StreamDescriptor.playButtonLabels(): List<String> {
    val all = summaryLabels()
    val codecAndSource = setOf(videoCodec.label, source.label).filter { it.isNotEmpty() }
    return all.filterNot { it in codecAndSource }
}

/**
 * What the Play button names, in plain words: resolution, the best HDR format and object audio
 * ("4K · Dolby Vision · Atmos"). The Dolby Vision profile, codec, audio codec and size stay on
 * the Sources screen, for anyone who wants them.
 */
internal fun StreamDescriptor.playButtonSummary(): List<String> = buildList {
    resolution.label.takeIf { it.isNotEmpty() }?.let(::add)
    if (hasDolbyVision) {
        add("Dolby Vision")
    } else {
        hdr.minByOrNull { it.ordinal }?.let { add(it.label) }
    }
    when {
        hasAtmos -> add("Atmos")
        AudioFormat.DTS_X in audio -> add(AudioFormat.DTS_X.label)
    }
}

/** [playButtonLabels] without the size: what a spec line names ("4K · DV · Atmos"). */
internal fun StreamDescriptor.specLineLabels(): List<String> {
    val size = sizeBytes?.let(StreamDescriptor::formatSize)
    return playButtonLabels().filterNot { it == size }
}
