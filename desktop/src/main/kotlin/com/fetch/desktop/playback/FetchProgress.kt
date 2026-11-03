package com.fetch.desktop.playback

import com.fetch.desktop.data.AddonStreamResult

/** Where one addon is with its answer (the TV's `SourceChipStatus`). */
enum class AddonFetchStatus { LOADING, SUCCESS, ERROR, TIMED_OUT }

/** One addon's chip in the fetching panel: its name, status and how many sources it sent. */
data class AddonProgress(
    val name: String,
    val status: AddonFetchStatus = AddonFetchStatus.LOADING,
    val count: Int = 0,
    /** When it answered, for ordering the chips as they came in. */
    val answeredOrder: Int = -1
) {
    /** "Torrentio · 12", "Comet · timed out", as the TV's `sourceChipLabel`. */
    val label: String
        get() = when (status) {
            AddonFetchStatus.LOADING -> name
            AddonFetchStatus.SUCCESS -> "$name · $count"
            AddonFetchStatus.ERROR -> "$name · failed"
            AddonFetchStatus.TIMED_OUT -> "$name · timed out"
        }
}

object FetchProgress {
    /** Every addon waiting, in addon order. */
    fun start(names: List<String>): List<AddonProgress> = names.distinct().map { AddonProgress(it) }

    /** [progress] with [result] in: its status and count, numbered in the order answers came. */
    fun answered(progress: List<AddonProgress>, result: AddonStreamResult): List<AddonProgress> {
        val name = result.addon.displayName
        val status = when {
            result.error == null -> AddonFetchStatus.SUCCESS
            result.error.contains("timed out", ignoreCase = true) -> AddonFetchStatus.TIMED_OUT
            else -> AddonFetchStatus.ERROR
        }
        val order = progress.count { it.status != AddonFetchStatus.LOADING }
        val updated = AddonProgress(name, status, result.streams.size, order)
        return if (progress.any { it.name == name }) progress.map { if (it.name == name && it.status == AddonFetchStatus.LOADING) updated else it }
        else progress + updated
    }

    /** Whatever hasn't answered when the search ends counts as failed, as on TV. */
    fun finished(progress: List<AddonProgress>): List<AddonProgress> {
        var order = progress.count { it.status != AddonFetchStatus.LOADING }
        return progress.map { if (it.status == AddonFetchStatus.LOADING) it.copy(status = AddonFetchStatus.ERROR, answeredOrder = order++) else it }
    }

    /** Answered chips first, in the order they answered, then the ones still looking. */
    fun inAnswerOrder(progress: List<AddonProgress>): List<AddonProgress> {
        val (done, waiting) = progress.partition { it.status != AddonFetchStatus.LOADING }
        return done.sortedBy { it.answeredOrder } + waiting
    }

    /** "Fetching… 2 answered, 1 addon still looking" or "All 3 addons answered". */
    fun headline(progress: List<AddonProgress>): String {
        val answered = progress.count { it.status != AddonFetchStatus.LOADING }
        val waiting = progress.size - answered
        return when {
            progress.isEmpty() -> "Asking your addons…"
            waiting == 0 -> "All $answered ${if (answered == 1) "addon" else "addons"} answered"
            else -> "Fetching… $answered answered, $waiting ${if (waiting == 1) "addon" else "addons"} still looking"
        }
    }
}
