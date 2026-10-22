package com.fetch.tv.ui.screens.stream

import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.core.streams.ranking.StreamTitleParser
import com.fetch.tv.domain.model.Stream

/**
 * A rule picked from a source's long-press menu. These edit the same stream rules the phone hub
 * edits, so either place shows what the other saved.
 */
internal sealed interface SourceRule {
    fun applyTo(rules: StreamRankingRules): StreamRankingRules

    data class PreferAddon(val addon: String) : SourceRule {
        override fun applyTo(rules: StreamRankingRules) = rules.copy(
            preferredAddons = listOf(addon) + rules.preferredAddons.filterNot { it.equals(addon, ignoreCase = true) }
        )
    }

    data class NeverReleaseGroup(val group: String) : SourceRule {
        override fun applyTo(rules: StreamRankingRules) = rules.copy(
            excludedReleaseGroups = rules.excludedReleaseGroups.filterNot { it.equals(group, ignoreCase = true) } + group,
            preferredReleaseGroups = rules.preferredReleaseGroups.filterNot { it.equals(group, ignoreCase = true) }
        )
    }
}

/** The rules [stream] can still add: none that [rules] already hold. */
internal fun sourceRulesFor(stream: Stream, rules: StreamRankingRules): List<SourceRule> = buildList {
    val addon = stream.addonName.trim()
    if (addon.isNotEmpty() && rules.preferredAddons.none { it.equals(addon, ignoreCase = true) }) {
        add(SourceRule.PreferAddon(addon))
    }
    // Files from the user's own library are never skipped for taste rules, so the rule would do nothing.
    val group = if (stream.serverTarget == null) StreamTitleParser.parse(stream).releaseGroup else null
    if (group != null && rules.excludedReleaseGroups.none { it.equals(group, ignoreCase = true) }) {
        add(SourceRule.NeverReleaseGroup(group))
    }
}
