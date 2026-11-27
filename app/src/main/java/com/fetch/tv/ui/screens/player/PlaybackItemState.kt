package com.fetch.tv.ui.screens.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** Which title, and which episode of it, a [PlaybackItemState] belongs to. */
internal data class PlaybackItemKey(
    val videoId: String?,
    val season: Int?,
    val episode: Int?
)

/**
 * State that belongs to one playback item, the episode or film on screen (#777). It is replaced
 * as a whole when the item changes, instead of field by field, so nothing from the last episode
 * can leak into the next and nothing a retry of the same item depends on is wiped by the retry.
 *
 * - [job] parents the item's own coroutines (see [PlayerRuntimeController.itemScope]); replacing
 *   the item cancels them, so a late result for the old episode never lands on the new one.
 * - The completion flags say whether this item has been marked watched. They outlive re-inits and
 *   source switches of the same item.
 * - [attempt] holds the retry budgets of one stream. Automatic retries of that stream reuse it,
 *   so a retry cannot refill the budget that allowed it; a different stream gets a fresh one.
 */
internal class PlaybackItemState(
    val key: PlaybackItemKey,
    /** Bumped for every new item; a save that captured an older value no longer owns the flags. */
    val generation: Long,
    parent: Job?
) {
    val job: Job = SupervisorJob(parent)

    /**
     * Bumped when the item is started over. A save that began before the restart still stores
     * the finished pass, but no longer owns the completion flags of the new one.
     */
    var passGeneration: Long = 0L

    var hasMarkedCompleted: Boolean = false
    /** The completion mark came from the natural end, so later in-progress saves are stale. */
    var completionIsFinal: Boolean = false

    var attempt: PlaybackAttemptState = PlaybackAttemptState(streamUrl = null)
        private set

    /** The retry budgets for [streamUrl]: the current ones for the same stream, fresh ones for another. */
    fun attemptFor(streamUrl: String?): PlaybackAttemptState {
        if (attempt.streamUrl != streamUrl) attempt = PlaybackAttemptState(streamUrl)
        return attempt
    }

    /** Budgets back to full for the same stream, for an explicit Retry or a stream the user picked again. */
    fun resetAttempt() {
        attempt = PlaybackAttemptState(attempt.streamUrl)
    }

    fun cancel() = job.cancel()
}

/** The retry budgets of one stream of a [PlaybackItemState]. */
internal class PlaybackAttemptState(val streamUrl: String?) {
    var timeoutRecoveryAttempts: Int = 0
    var hasRetriedAfter416: Boolean = false
    var hasRetriedAfterUnexpectedNpe: Boolean = false
    var hasRetriedAfterMediaPeriodHolderCrash: Boolean = false
}

/** A scope for work that belongs to [item] alone; it ends when the item is replaced. */
internal fun CoroutineScope.childScopeFor(item: PlaybackItemState): CoroutineScope =
    CoroutineScope(coroutineContext + item.job)
