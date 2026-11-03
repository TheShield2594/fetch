package com.fetch.tv.ui.screens.player

import com.fetch.tv.domain.model.MetaCastMember

/**
 * Who the pause overlay's X-Ray-style cast strip shows, for a row with room for [limit] people.
 *
 * Nothing public says who is on screen at a given moment (Prime Video's X-Ray comes from IMDb's
 * own scene tagging), so the strip shows who is in what is playing: the title's billed cast,
 * then, for an episode, that episode's guest stars. A series' billed cast is often longer than
 * the row, so up to half the row is kept for guest stars, and either side gets the other's unused
 * room.
 *
 * Each person appears once (see [isSamePerson]). A guest who is also in the shown part of the
 * main cast is folded into that entry, which takes the guest copy's photo if it has none and the
 * guest copy's character, since that is the role in this episode. A guest whose main-cast entry
 * falls outside the row keeps a guest slot.
 */
internal fun pauseOverlayCast(
    cast: List<MetaCastMember>,
    episodeGuestStars: List<MetaCastMember>,
    limit: Int = Int.MAX_VALUE
): List<MetaCastMember> {
    if (limit <= 0) return emptyList()
    val main = cast.distinctPeople()
    val guests = episodeGuestStars.distinctPeople()

    // Guests that duplicate a shown main entry free their slot, which can let the main cast
    // grow, which can absorb more guests. Each round only grows mainCount, so this settles.
    var mainCount = minOf(main.size, limit - minOf(guests.size, limit / 2))
    var shownMain: List<MetaCastMember>
    var guestsLeft: List<MetaCastMember>
    while (true) {
        shownMain = main.take(mainCount)
        guestsLeft = guests.filterNot { guest -> shownMain.any { isSamePerson(it, guest) } }
        val next = minOf(main.size, limit - minOf(guestsLeft.size, limit / 2))
        if (next <= mainCount) break
        mainCount = next
    }

    val mergedMain = shownMain.map { member ->
        val guest = guests.firstOrNull { isSamePerson(member, it) } ?: return@map member
        member.copy(
            photo = member.photo?.takeIf { it.isNotBlank() } ?: guest.photo,
            character = guest.character ?: member.character,
            tmdbId = member.tmdbId ?: guest.tmdbId
        )
    }
    return mergedMain + guestsLeft.take(limit - mergedMain.size)
}

/**
 * Merges a second cast source into [current]. Each person in [current] who has no photo takes
 * the photo of the same person in [incoming], so TMDB's headshots fill an addon's names-only
 * entries whichever request finishes first. If [current] still has no photos at all (the names
 * didn't line up), a photographed [incoming] list replaces it.
 */
internal fun preferCastWithPhotos(
    current: List<MetaCastMember>,
    incoming: List<MetaCastMember>
): List<MetaCastMember> {
    if (incoming.isEmpty()) return current
    if (current.isEmpty()) return incoming
    val merged = current.map { member ->
        if (!member.photo.isNullOrBlank()) return@map member
        val match = incoming.firstOrNull { !it.photo.isNullOrBlank() && isSamePerson(member, it) }
            ?: return@map member
        member.copy(
            photo = match.photo,
            character = member.character ?: match.character,
            tmdbId = member.tmdbId ?: match.tmdbId
        )
    }
    val mergedHasPhotos = merged.any { !it.photo.isNullOrBlank() }
    val incomingHasPhotos = incoming.any { !it.photo.isNullOrBlank() }
    return if (!mergedHasPhotos && incomingHasPhotos) incoming else merged
}

/**
 * Same TMDB id when both have one; otherwise the same name, ignoring case and outer spaces.
 * Two namesakes with different TMDB ids are different people.
 */
internal fun isSamePerson(a: MetaCastMember, b: MetaCastMember): Boolean {
    val idA = a.tmdbId
    val idB = b.tmdbId
    if (idA != null && idB != null) return idA == idB
    return a.name.trim().equals(b.name.trim(), ignoreCase = true)
}

/** Drops repeat entries, keeping the first; a later copy fills in a photo the first lacks. */
private fun List<MetaCastMember>.distinctPeople(): List<MetaCastMember> {
    val kept = ArrayList<MetaCastMember>(size)
    for (member in this) {
        val index = kept.indexOfFirst { isSamePerson(it, member) }
        if (index < 0) {
            kept += member
        } else if (kept[index].photo.isNullOrBlank() && !member.photo.isNullOrBlank()) {
            kept[index] = kept[index].copy(photo = member.photo)
        }
    }
    return kept
}
