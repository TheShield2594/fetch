package com.fetch.tv.core.player

import com.fetch.tv.core.build.AppFeaturePolicy

/**
 * The shared [StreamAutoPlaySelection] with the TV's playable test: a stream with a link, a
 * media-server file, or, on builds that play YouTube in the app, a YouTube id to resolve.
 */
object StreamAutoPlaySelector : StreamAutoPlaySelection(isPlayable = ::isPlayableOnTv)

private fun isPlayableOnTv(stream: com.fetch.tv.domain.model.Stream): Boolean {
    // External URL streams (e.g. error pages, web links) are not playable.
    if (stream.isExternal()) return false
    if (stream.serverTarget != null) return true
    if (stream.getStreamUrl() != null) return true
    // A YouTube id is resolved to a playable URL on the device when the stream is played.
    // Builds without in-app YouTube playback open the watch page instead, an external link.
    return AppFeaturePolicy.inAppTrailerPlaybackEnabled && stream.youTubeIdToResolve() != null
}
