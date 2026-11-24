package com.fetch.tv.ui.screens.player

/**
 * Sizes mpv's demuxer cache (`demuxer-max-bytes` / `demuxer-max-back-bytes`) from the same
 * inputs ExoPlayer's buffer uses: the user's target buffer when they set one, and this
 * session's memory budget ([com.fetch.tv.ui.screens.settings.MemoryBudget.playbackBudgetMb]).
 *
 * The forward cache is what rides out a network hiccup, so it takes most of the total; the
 * back cache only saves a short seek back from going to the network.
 */
internal object MpvDemuxerCachePolicy {
    /** Same floor ExoPlayer's budget can drop to under memory pressure. */
    const val MIN_TOTAL_MB = 50
    /** mpv's cache is native memory; past this nothing more is gained on a TV box. */
    const val MAX_TOTAL_MB = 1024
    /** The back cache's share of the total, as 1/[BACK_SHARE_DEN]. */
    const val BACK_SHARE_DEN = 4

    data class Limits(val forwardMb: Int, val backMb: Int) {
        val forwardBytes: Long get() = forwardMb.toLong() * 1024L * 1024L
        val backBytes: Long get() = backMb.toLong() * 1024L * 1024L
    }

    /**
     * @param userTargetMb the user's own target buffer, or null when the buffer is left to the
     *   memory budget (custom buffers off, or budget-managed).
     * @param playbackBudgetMb what this session may spend on buffers.
     * @param allowAboveBudget the user allowed a target above the budget.
     */
    fun limits(userTargetMb: Int?, playbackBudgetMb: Int, allowAboveBudget: Boolean): Limits {
        val requested = when {
            userTargetMb == null || userTargetMb <= 0 -> playbackBudgetMb
            allowAboveBudget -> userTargetMb
            else -> userTargetMb.coerceAtMost(playbackBudgetMb)
        }
        val total = requested.coerceIn(MIN_TOTAL_MB, MAX_TOTAL_MB)
        val back = total / BACK_SHARE_DEN
        return Limits(forwardMb = total - back, backMb = back)
    }
}
