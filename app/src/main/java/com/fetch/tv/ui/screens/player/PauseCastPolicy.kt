package com.fetch.tv.ui.screens.player

import com.fetch.tv.domain.model.MetaCastMember

/**
 * Who the pause overlay's X-Ray-style cast strip shows, for a row with room for [limit] people.
 *
 * Nothing public says who is on screen at a given moment (Prime Video's X-Ray comes from IMDb's
 * own scene tagging), so the strip shows who is in what is playing: the title's billed cast,
 * then, for an episode, that episode's guest stars. A series' billed cast is often longer than
 * the row, so up to half the row is kept for guest stars, and either side gets the other's unused
 * room. Each person appears once, matched by TMDB id when both sides have one and by name otherwise.
 */
internal fun pauseOverlayCast(
    cast: List<MetaCastMember>,
    episodeGuestStars: List<MetaCastMember>,
    limit: Int = Int.MAX_VALUE
): List<MetaCastMember> {
    if (limit <= 0) return emptyList()
    val seenIds = HashSet<Int>()
    val seenNames = HashSet<String>()
    fun List<MetaCastMember>.unseen(): List<MetaCastMember> = filter { member ->
        val nameKey = member.name.trim().lowercase()
        val id = member.tmdbId
        if ((id != null && id in seenIds) || nameKey in seenNames) return@filter false
        if (id != null) seenIds += id
        seenNames += nameKey
        true
    }
    val main = cast.unseen()
    val guests = episodeGuestStars.unseen()
    val guestSlots = minOf(guests.size, limit / 2)
    val mainTake = minOf(main.size, limit - guestSlots)
    val guestTake = minOf(guests.size, limit - mainTake)
    return main.take(mainTake) + guests.take(guestTake)
}

/**
 * Picks the cast list to keep when a second source arrives. A list with headshots wins over one
 * without, so TMDB's photos aren't replaced by an addon's names-only cast (or the other way round,
 * depending on which request finishes first). Otherwise the current list is kept unless empty.
 */
internal fun preferCastWithPhotos(
    current: List<MetaCastMember>,
    incoming: List<MetaCastMember>
): List<MetaCastMember> {
    if (incoming.isEmpty()) return current
    if (current.isEmpty()) return incoming
    val currentHasPhotos = current.any { !it.photo.isNullOrBlank() }
    val incomingHasPhotos = incoming.any { !it.photo.isNullOrBlank() }
    return if (incomingHasPhotos && !currentHasPhotos) incoming else current
}
