package com.fetch.tv.ui.screens.player

import com.fetch.tv.domain.model.MetaCastMember

/**
 * Who the pause overlay's X-Ray-style cast strip shows.
 *
 * Nothing public says who is on screen at a given moment (Prime Video's X-Ray comes from IMDb's
 * own scene tagging), so the strip shows who is in what is playing: the title's billed cast,
 * then, for an episode, that episode's guest stars. Each person appears once, matched by TMDB id
 * when both sides have one and by name otherwise.
 */
internal fun pauseOverlayCast(
    cast: List<MetaCastMember>,
    episodeGuestStars: List<MetaCastMember>
): List<MetaCastMember> {
    if (episodeGuestStars.isEmpty()) return cast
    val seenIds = HashSet<Int>()
    val seenNames = HashSet<String>()
    val merged = ArrayList<MetaCastMember>(cast.size + episodeGuestStars.size)
    for (member in cast + episodeGuestStars) {
        val nameKey = member.name.trim().lowercase()
        val id = member.tmdbId
        if (id != null && id in seenIds) continue
        if (nameKey in seenNames) continue
        if (id != null) seenIds += id
        seenNames += nameKey
        merged += member
    }
    return merged
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
