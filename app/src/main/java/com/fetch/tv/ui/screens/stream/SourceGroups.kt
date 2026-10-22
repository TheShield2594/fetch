package com.fetch.tv.ui.screens.stream

import com.fetch.tv.core.streams.ranking.ExclusionReason
import com.fetch.tv.core.streams.ranking.StreamResolution
import com.fetch.tv.core.streams.ranking.StreamTitleParser
import com.fetch.tv.domain.model.Stream

/** The sections the source list is split into, in the order they appear. */
internal enum class SourceGroup { BEST_MATCH, UHD, FHD, OTHER, PASSED_OVER }

/** One section of the source list: positions in the ranked list, in ranked order. */
internal data class SourceSection(val group: SourceGroup, val indices: List<Int>)

/**
 * Splits a ranked source list into quality sections without reordering within them, so each
 * section is still best first. Best match's pick leads on its own when [bestMatchFirst] is set;
 * streams Best match passed over stay together at the end, as the ranker already places them.
 *
 * [resolutions] and [passedOver] are per stream, in the list's order.
 */
internal fun groupSourcesByQuality(
    resolutions: List<StreamResolution>,
    passedOver: List<Boolean>,
    bestMatchFirst: Boolean
): List<SourceSection> {
    val buckets = SourceGroup.entries.associateWith { mutableListOf<Int>() }
    resolutions.indices.forEach { index ->
        val group = when {
            passedOver.getOrElse(index) { false } -> SourceGroup.PASSED_OVER
            bestMatchFirst && index == 0 -> SourceGroup.BEST_MATCH
            resolutions[index] == StreamResolution.UHD_2160 -> SourceGroup.UHD
            resolutions[index] == StreamResolution.FHD_1080 ||
                resolutions[index] == StreamResolution.QHD_1440 -> SourceGroup.FHD
            else -> SourceGroup.OTHER
        }
        buckets.getValue(group) += index
    }
    return SourceGroup.entries.mapNotNull { group ->
        buckets.getValue(group).takeIf { it.isNotEmpty() }?.let { SourceSection(group, it) }
    }
}

/**
 * The source list's sections for [streams]. Best match's pick leads only on the All tab
 * ([addonFilter] null), the one Best match orders; addon tabs keep the addon's own order.
 */
internal fun sourceSections(
    streams: List<Stream>,
    exclusions: Map<String, ExclusionReason>,
    bestMatchFirst: Boolean,
    addonFilter: String?
): List<SourceSection> = groupSourcesByQuality(
    resolutions = streams.map { StreamTitleParser.parse(it).resolution },
    passedOver = streams.map { exclusions.isNotEmpty() && exclusions.containsKey(it.stableKey()) },
    bestMatchFirst = bestMatchFirst && addonFilter == null
)

/** The stream shown first, which is not always the first in ranked order. */
internal fun List<SourceSection>.firstShownIndex(): Int? = firstOrNull()?.indices?.firstOrNull()
