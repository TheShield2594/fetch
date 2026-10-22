package com.fetch.tv.core.recommendations

import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem

/** A title the profile finished, used to seed a "Because you watched" row. */
data class BecauseYouWatchedSeed(
    val contentId: String,
    val contentType: String,
    val title: String,
    val completedAt: Long
)

/** A TMDB person on a title's credits. */
data class CreditPerson(val id: Int, val name: String)

/**
 * The credits a reason can be computed from.
 *
 * [cast] is in billing order. [directors] holds movie directors or a show's creators.
 */
data class TitleCredits(
    val directors: List<CreditPerson> = emptyList(),
    val writers: List<CreditPerson> = emptyList(),
    val cast: List<CreditPerson> = emptyList()
)

/** Why a candidate is on the row. Each one names the shared person or collection. */
sealed interface BecauseYouWatchedReason {
    data class SameCollection(val collectionName: String) : BecauseYouWatchedReason
    data class SameDirector(val name: String) : BecauseYouWatchedReason
    data class SameCreator(val name: String) : BecauseYouWatchedReason
    data class SameWriter(val name: String) : BecauseYouWatchedReason
    data class SameLeadCast(val name: String) : BecauseYouWatchedReason
}

/** How many top-billed seed actors count as its leads. */
internal const val SEED_LEAD_CAST_COUNT = 3

/** How far down a candidate's billing a seed lead may appear and still count. */
internal const val CANDIDATE_BILLED_CAST_COUNT = 5

/**
 * Returns the strongest reason backed by TMDB data, or null when the candidate shares nothing
 * with the seed. Candidates without a reason are dropped, so there is no generic fallback.
 */
fun becauseYouWatchedReason(
    seed: TitleCredits,
    candidate: TitleCredits,
    isSeries: Boolean,
    sharedCollectionName: String? = null
): BecauseYouWatchedReason? {
    sharedCollectionName?.takeIf { it.isNotBlank() }?.let {
        return BecauseYouWatchedReason.SameCollection(it)
    }
    firstShared(seed.directors, candidate.directors)?.let { person ->
        return if (isSeries) {
            BecauseYouWatchedReason.SameCreator(person.name)
        } else {
            BecauseYouWatchedReason.SameDirector(person.name)
        }
    }
    firstShared(seed.writers, candidate.writers)?.let { person ->
        return BecauseYouWatchedReason.SameWriter(person.name)
    }
    firstShared(
        seed.cast.take(SEED_LEAD_CAST_COUNT),
        candidate.cast.take(CANDIDATE_BILLED_CAST_COUNT)
    )?.let { person ->
        return BecauseYouWatchedReason.SameLeadCast(person.name)
    }
    return null
}

private fun firstShared(seedPeople: List<CreditPerson>, candidatePeople: List<CreditPerson>): CreditPerson? {
    if (seedPeople.isEmpty() || candidatePeople.isEmpty()) return null
    val candidateIds = candidatePeople.mapTo(HashSet()) { it.id }
    return seedPeople.firstOrNull { it.id in candidateIds && it.name.isNotBlank() }
}

/**
 * The most recently completed distinct titles for the active profile, newest first.
 *
 * Completed playback and watched-history entries both count. Episodes seed their series.
 */
fun latestCompletedSeeds(
    progress: List<WatchProgress>,
    watchedItems: List<WatchedItem>,
    limit: Int
): List<BecauseYouWatchedSeed> {
    if (limit <= 0) return emptyList()
    val fromProgress = progress
        .asSequence()
        .filter { it.isCompleted() && it.name.isNotBlank() }
        .map { BecauseYouWatchedSeed(it.contentId, it.contentType, it.name, it.lastWatched) }
    val fromHistory = watchedItems
        .asSequence()
        .filter { it.title.isNotBlank() }
        .map { BecauseYouWatchedSeed(it.contentId, it.contentType, it.title, it.watchedAt) }
    return (fromProgress + fromHistory)
        .filter { it.contentId.isNotBlank() }
        .groupBy { it.contentId.lowercase() }
        .values
        .map { entries -> entries.maxBy { it.completedAt } }
        .sortedByDescending { it.completedAt }
        .take(limit)
}
