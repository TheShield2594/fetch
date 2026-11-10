package com.fetch.tv.ui.screens.player

import com.fetch.tv.domain.model.Stream

/**
 * What to do when a stream fails after it has started playing: most often an expired debrid
 * link after a long pause. The same source is looked up again first (its addon mints a fresh
 * link), then the next ranked sources, and only then is the error left on screen.
 */
internal object MidPlaybackRecoveryPolicy {
    /** Next-ranked sources tried after the same source couldn't be refreshed. */
    const val MAX_NEXT_SOURCES = 3

    enum class Step { REFRESH_SAME_SOURCE, NEXT_SOURCE, SHOW_ERROR }

    /**
     * Whether a failure after the first frame is one a fresh link or another source can fix:
     * an expired or refused link (401/403/404/410), or any other source (network/IO) error.
     * Decoder and renderer errors keep their own recovery and their error.
     */
    fun applies(httpStatus: Int?, isSourceError: Boolean): Boolean =
        httpStatus in EXPIRED_LINK_STATUSES || isSourceError

    fun nextStep(refreshTried: Boolean, nextSourcesTried: Int, nextSourceEnabled: Boolean): Step = when {
        !refreshTried -> Step.REFRESH_SAME_SOURCE
        nextSourceEnabled && nextSourcesTried < MAX_NEXT_SOURCES -> Step.NEXT_SOURCE
        else -> Step.SHOW_ERROR
    }

    /**
     * The failed source in a fresh answer from the addons: the same addon and the same file
     * (info hash and file index), else the same name and description, else the same filename.
     * The url is not compared, since a fresh link is the point.
     */
    fun findSameSource(
        streams: List<Stream>,
        addonName: String?,
        name: String?,
        description: String?,
        infoHash: String?,
        fileIdx: Int?,
        filename: String?
    ): Stream? {
        val sameAddon = streams.filter { addonName.isNullOrBlank() || it.addonName == addonName }
        if (sameAddon.isEmpty()) return null
        if (!infoHash.isNullOrBlank()) {
            sameAddon.firstOrNull {
                it.getEffectiveInfoHash().equals(infoHash, ignoreCase = true) &&
                    (fileIdx == null || it.getEffectiveFileIdx() == fileIdx)
            }?.let { return it }
        }
        if (!name.isNullOrBlank()) {
            sameAddon.firstOrNull { it.name == name && it.description == description }?.let { return it }
        }
        if (!filename.isNullOrBlank()) {
            sameAddon.firstOrNull { it.behaviorHints?.filename == filename }?.let { return it }
        }
        return null
    }

    private val EXPIRED_LINK_STATUSES = setOf(401, 403, 404, 410)
}

/** A recovery under way for one title, kept until playback is healthy again or the title changes. */
internal data class MidPlaybackRecovery(
    val requestKey: String,
    /** Where playback was when the stream failed; every replacement starts here. */
    val positionMs: Long,
    val refreshTried: Boolean = false,
    val nextSourcesTried: Int = 0
)
