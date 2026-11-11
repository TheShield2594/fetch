package com.fetch.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import com.fetch.tv.ui.navigation.Screen
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

internal data class PlayerNavigationArgs(
    /** Names this session's [PlaybackRequest] in [PlaybackRequests]. */
    val requestId: String?,
    /** From the [PlaybackRequest]; empty when the request is gone (process restore). */
    val streamUrl: String,
    val title: String,
    val streamName: String?,
    val year: String?,
    val headersJson: String?,
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String?,
    val initialSeason: Int?,
    val initialEpisode: Int?,
    val initialEpisodeTitle: String?,
    val bingeGroup: String?,
    val filename: String?,
    val videoHash: String?,
    val videoSize: Long?,
    val startFromBeginning: Boolean,
    val addonName: String?,
    val addonLogo: String?,
    val streamDescription: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val contentLanguage: String?,
    val rememberedAudioLanguage: String?,
    val rememberedAudioName: String?,
    val launchStartedAtMs: Long?,
    val profileId: Int?,
    val audioDelayMs: Int? = null
) {
    companion object {
        fun from(savedStateHandle: SavedStateHandle): PlayerNavigationArgs {
            fun decodedOrNull(key: String): String? {
                val value = savedStateHandle.get<String>(key) ?: return null
                if (value.isEmpty()) return null
                // Stream metadata occasionally contains stray `%` or malformed escapes
                // Fall back to the raw value rather
                // than crashing the player on launch.
                return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }

            val requestId = savedStateHandle.get<String>("requestId")?.takeIf { it.isNotEmpty() }
            val request = PlaybackRequests.get(requestId)
            return PlayerNavigationArgs(
                requestId = requestId,
                streamUrl = request?.streamUrl ?: "",
                title = decodedOrNull("title") ?: "",
                streamName = decodedOrNull("streamName"),
                year = decodedOrNull("year"),
                headersJson = request?.headers?.takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() },
                // NavController already decodes these IDs.
                contentId = savedStateHandle.get<String>("contentId")?.takeIf { it.isNotEmpty() },
                contentType = savedStateHandle.get<String>("contentType")?.takeIf { it.isNotEmpty() },
                contentName = decodedOrNull("contentName"),
                poster = decodedOrNull("poster"),
                backdrop = decodedOrNull("backdrop"),
                logo = decodedOrNull("logo"),
                videoId = savedStateHandle.get<String>("videoId")?.takeIf { it.isNotEmpty() },
                initialSeason = savedStateHandle.get<String>("season")?.toIntOrNull(),
                initialEpisode = savedStateHandle.get<String>("episode")?.toIntOrNull(),
                initialEpisodeTitle = decodedOrNull("episodeTitle"),
                bingeGroup = savedStateHandle.get<String>("bingeGroup")?.takeIf { it.isNotEmpty() },
                filename = decodedOrNull("filename"),
                videoHash = savedStateHandle.get<String>("videoHash")?.takeIf { it.isNotEmpty() },
                videoSize = savedStateHandle.get<String>("videoSize")?.toLongOrNull(),
                startFromBeginning = savedStateHandle.get<String>("startFromBeginning")?.toBooleanStrictOrNull() == true,
                addonName = decodedOrNull("addonName"),
                addonLogo = decodedOrNull("addonLogo"),
                streamDescription = decodedOrNull("streamDescription"),
                infoHash = savedStateHandle.get<String>("infoHash")?.takeIf { it.isNotEmpty() },
                fileIdx = savedStateHandle.get<String>("fileIdx")?.toIntOrNull(),
                contentLanguage = decodedOrNull("contentLanguage"),
                rememberedAudioLanguage = decodedOrNull("rememberedAudioLanguage"),
                rememberedAudioName = decodedOrNull("rememberedAudioName"),
                launchStartedAtMs = savedStateHandle.get<String>("launchStartedAtMs")?.toLongOrNull(),
                profileId = savedStateHandle.get<String>("profileId")?.toIntOrNull(),
                audioDelayMs = savedStateHandle.get<String>("audioDelayMs")?.toIntOrNull()
            )
        }
    }
}

/**
 * Points the saved navigation args at the episode now playing. The player switches episodes in
 * place, so without this a process restore (for example after the TV's screensaver let the
 * system kill the app) rebuilds the player from the episode the session started on
 * (NuvioTV#3502). Keys that [PlayerNavigationArgs.from] URL-decodes are stored encoded.
 *
 * The stream itself (URL, headers, sources) goes to [PlaybackRequests] under [requestId], never
 * into saved state: a restore resolves the stream again (see [playerRestoreStreamRoute]).
 */
internal fun SavedStateHandle.rememberEpisodeForRestore(
    requestId: String?,
    request: PlaybackRequest,
    videoId: String?,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    streamName: String?,
    filename: String?,
    bingeGroup: String?,
    videoHash: String?,
    videoSize: Long?,
    addonName: String?,
    addonLogo: String?,
    streamDescription: String?,
    infoHash: String?,
    fileIdx: Int?,
    audioDelayMs: Int?
) {
    fun encoded(value: String?): String =
        value?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: ""

    PlaybackRequests.update(requestId, request)
    this["videoId"] = videoId ?: ""
    this["season"] = season?.toString() ?: ""
    this["episode"] = episode?.toString() ?: ""
    this["episodeTitle"] = encoded(episodeTitle)
    this["streamName"] = encoded(streamName)
    this["filename"] = encoded(filename)
    this["bingeGroup"] = bingeGroup ?: ""
    this["videoHash"] = videoHash ?: ""
    this["videoSize"] = videoSize?.toString() ?: ""
    this["addonName"] = encoded(addonName)
    this["infoHash"] = infoHash ?: ""
    this["fileIdx"] = fileIdx?.toString() ?: ""
    this["audioDelayMs"] = audioDelayMs?.toString() ?: ""
    this["addonLogo"] = encoded(addonLogo)
    this["streamDescription"] = encoded(streamDescription)
    // The restored episode resumes from its saved progress, not from the start.
    this["startFromBeginning"] = "false"
}

/**
 * The Stream screen route that resolves the stream again for a player restored without its
 * [PlaybackRequest] (the process was killed), for the episode the player was last on. Null when
 * the saved state doesn't say what was playing.
 */
internal fun playerRestoreStreamRoute(savedStateHandle: SavedStateHandle): String? {
    val args = PlayerNavigationArgs.from(savedStateHandle)
    val contentType = args.contentType ?: return null
    val videoId = args.videoId ?: args.contentId ?: return null
    fun flag(key: String) = savedStateHandle.get<String>(key)?.toBooleanStrictOrNull() == true
    return Screen.Stream.createRoute(
        videoId = videoId,
        contentType = contentType,
        title = args.contentName ?: args.title,
        poster = args.poster,
        backdrop = args.backdrop,
        logo = args.logo,
        season = args.initialSeason,
        episode = args.initialEpisode,
        episodeName = args.initialEpisodeTitle,
        year = args.year,
        contentId = args.contentId,
        contentName = args.contentName,
        // Resume where the session left off; its progress was saved as it played.
        startFromBeginning = false,
        returnToDetailOnBack = flag("returnToDetailOnBack"),
        returnToHomeOnBack = flag("returnToHomeOnBack"),
        contentLanguage = args.contentLanguage,
        profileId = args.profileId
    )
}
