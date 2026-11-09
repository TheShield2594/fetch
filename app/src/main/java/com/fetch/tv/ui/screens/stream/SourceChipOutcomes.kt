package com.fetch.tv.ui.screens.stream

import com.fetch.tv.core.streams.AddonHealth
import com.fetch.tv.core.streams.AddonRequestKind
import com.fetch.tv.core.streams.AddonRequestOutcome
import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus

/**
 * The chip status an addon's finished stream request settles on, or null for a success: a
 * successful addon's chip turns green when its streams arrive, not from its health outcome.
 */
internal fun AddonRequestOutcome.settledSourceChipStatus(): SourceChipStatus? = when (this) {
    AddonRequestOutcome.TIMEOUT -> SourceChipStatus.TIMED_OUT
    AddonRequestOutcome.ERROR -> SourceChipStatus.ERROR
    AddonRequestOutcome.EMPTY -> SourceChipStatus.NO_RESULTS
    AddonRequestOutcome.SUCCESS -> null
}

/**
 * Settles every chip still loading from the outcome its addon recorded since [sinceMs].
 *
 * The live outcome events are a non-replaying SharedFlow, so the last ones can still be in
 * flight when the search finishes. The tracker's health map is updated synchronously before the
 * repository finishes an addon, so reading it at the end gives every addon's final outcome.
 */
internal fun settlePendingSourceChips(
    chips: List<SourceChipItem>,
    health: Map<String, AddonHealth>,
    sinceMs: Long
): List<SourceChipItem> = chips.map { chip ->
    if (chip.status != SourceChipStatus.LOADING) return@map chip
    val outcome = health[chip.name]?.samples
        ?.lastOrNull { it.kind == AddonRequestKind.STREAM && it.atMs >= sinceMs }
        ?.outcome
    outcome?.settledSourceChipStatus()?.let { chip.copy(status = it) } ?: chip
}
