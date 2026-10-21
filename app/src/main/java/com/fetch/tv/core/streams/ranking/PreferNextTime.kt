package com.fetch.tv.core.streams.ranking

import com.fetch.tv.domain.model.Stream
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One thing the user can tell Best match to prefer after picking a stream over its choice. */
sealed interface PreferenceSuggestion {
    fun applyTo(rules: StreamRankingRules): StreamRankingRules

    data class ReleaseGroup(val group: String) : PreferenceSuggestion {
        override fun applyTo(rules: StreamRankingRules) = rules.copy(
            preferredReleaseGroups = listOf(group) + rules.preferredReleaseGroups.filterNot { it.equals(group, ignoreCase = true) },
            excludedReleaseGroups = rules.excludedReleaseGroups.filterNot { it.equals(group, ignoreCase = true) }
        )
    }

    data class Resolution(val resolution: StreamResolution) : PreferenceSuggestion {
        override fun applyTo(rules: StreamRankingRules) = rules.copy(preferredResolution = resolution)
    }

    /** Prefer files up to [gb], for someone who keeps choosing smaller files. */
    data class SizeUpTo(val gb: Double) : PreferenceSuggestion {
        override fun applyTo(rules: StreamRankingRules) = rules.copy(preferredMaxSizeGb = gb)
    }

    data class Language(val language: StreamLanguage) : PreferenceSuggestion {
        override fun applyTo(rules: StreamRankingRules) = rules.copy(
            preferredLanguages = listOf(language) + rules.preferredLanguages.filterNot { it == language }
        )
    }
}

/** Suggestions waiting for the player to offer them, after the user overrode Best match. */
class PendingPreference(
    val suggestions: List<PreferenceSuggestion>,
    val recordedAtMs: Long
)

/**
 * "Prefer this next time": when the user starts a stream other than the one Best match would
 * have played, remember what set their pick apart so the player can offer to prefer it.
 */
object PreferNextTime {
    private const val MAX_SUGGESTIONS = 2
    private const val SMALLER_FILE_RATIO = 0.75

    private val mutablePending = MutableStateFlow<PendingPreference?>(null)
    val pending: StateFlow<PendingPreference?> = mutablePending.asStateFlow()

    /**
     * Records [picked] when it isn't Best match's choice among [streams]. Does nothing when
     * the pick has nothing to prefer that the rules don't already prefer.
     */
    fun recordManualPick(
        picked: Stream,
        streams: List<Stream>,
        rules: StreamRankingRules,
        device: DeviceDecodeCapabilities,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val best = StreamRanker.best(streams, rules, device) ?: return
        if (best.stream.stableKey() == picked.stableKey()) return
        val suggestions = suggest(StreamTitleParser.parse(picked), best.descriptor, rules)
        mutablePending.value = suggestions.takeIf { it.isNotEmpty() }?.let { PendingPreference(it, nowMs) }
    }

    /** Hands the waiting suggestions to the caller once, if they are recent enough to still make sense. */
    fun take(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): PendingPreference? {
        val pending = mutablePending.value ?: return null
        mutablePending.value = null
        return pending.takeIf { nowMs - it.recordedAtMs <= maxAgeMs }
    }

    fun clear() {
        mutablePending.value = null
    }

    internal fun suggest(
        picked: StreamDescriptor,
        best: StreamDescriptor,
        rules: StreamRankingRules
    ): List<PreferenceSuggestion> = buildList {
        val group = picked.releaseGroup
        if (group != null &&
            !group.equals(best.releaseGroup, ignoreCase = true) &&
            rules.preferredReleaseGroups.none { it.equals(group, ignoreCase = true) }
        ) {
            add(PreferenceSuggestion.ReleaseGroup(group))
        }
        val language = (picked.languages - best.languages - StreamLanguage.MULTI)
            .firstOrNull { it !in rules.preferredLanguages }
        if (language != null) add(PreferenceSuggestion.Language(language))
        if (picked.resolution != StreamResolution.UNKNOWN &&
            picked.resolution != best.resolution &&
            picked.resolution != rules.preferredResolution
        ) {
            add(PreferenceSuggestion.Resolution(picked.resolution))
        }
        val pickedSize = picked.sizeBytes
        val bestSize = best.sizeBytes
        if (pickedSize != null && bestSize != null && pickedSize < bestSize * SMALLER_FILE_RATIO) {
            val gb = ceil(pickedSize / 1_073_741_824.0).coerceAtLeast(1.0)
            if (rules.preferredMaxSizeGb == null || gb < rules.preferredMaxSizeGb) add(PreferenceSuggestion.SizeUpTo(gb))
        }
    }.take(MAX_SUGGESTIONS)
}
