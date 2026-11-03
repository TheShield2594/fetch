package com.fetch.desktop.party

import com.fetch.tv.domain.model.Stream

/**
 * What the watch party says about one title's (or episode's) sources, as the TV's Sources screen
 * shows it: the source the party's host plays ([source], marked "Party source" wherever it's in the
 * list) and, for a member, the stream the host shares ([shared], offered first as "Shared by …").
 */
data class PartySourceMarks(
    val source: PartySource?,
    val shared: PartyStream?,
    val hostName: String?
) {
    fun isPartySource(stream: Stream): Boolean = source?.matches(stream) == true

    val isEmpty: Boolean get() = source == null && shared == null

    companion object {
        /**
         * The marks for the sources of [mediaKey] ([partyMediaKey]): none unless the party is
         * watching it. The shared stream is a member's only; the host plays it already.
         */
        fun of(party: ActiveParty?, sharedStream: PartyStream?, mediaKey: String): PartySourceMarks? {
            val current = party ?: return null
            val source = current.media.source?.takeIf { current.media.key == mediaKey }
            val shared = sharedStream?.takeIf { !current.isHost && it.mediaKey == mediaKey }
            return PartySourceMarks(source, shared, current.member(current.hostMemberId)?.displayName).takeUnless { it.isEmpty }
        }
    }
}
