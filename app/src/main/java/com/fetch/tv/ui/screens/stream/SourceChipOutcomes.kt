package com.fetch.tv.ui.screens.stream

import com.fetch.tv.domain.model.StreamSourceOutcome
import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus

/**
 * The chip status a finished source settles on, or null when it answered with streams: that
 * chip turns green (or "unplayable only") when its streams arrive, not from its outcome.
 */
internal fun StreamSourceOutcome.settledSourceChipStatus(): SourceChipStatus? = when (this) {
    StreamSourceOutcome.TIMED_OUT -> SourceChipStatus.TIMED_OUT
    StreamSourceOutcome.FAILED -> SourceChipStatus.ERROR
    StreamSourceOutcome.EMPTY -> SourceChipStatus.NO_RESULTS
    StreamSourceOutcome.STREAMS -> null
}

/**
 * Settles every chip still loading whose source has reported how it finished. The search
 * reports every addon and media server, and a search replayed from the session cache brings its
 * outcomes back, so a chip is only left loading for a source still running.
 */
internal fun settlePendingSourceChips(
    chips: List<SourceChipItem>,
    outcomes: Map<String, StreamSourceOutcome>
): List<SourceChipItem> {
    if (outcomes.isEmpty() || chips.none { it.status == SourceChipStatus.LOADING }) return chips
    return chips.map { chip ->
        if (chip.status != SourceChipStatus.LOADING) return@map chip
        outcomes[chip.name]?.settledSourceChipStatus()?.let { chip.copy(status = it) } ?: chip
    }
}
