package com.fetch.desktop.party

import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TV's Sources-screen party marks: the host's source, and the stream the host shares with members. */
class PartySourceMarksTest {

    private val media = PartyMedia(
        type = "series", metaId = "tt1", metaType = "series", season = 1, episode = 2, title = "Show",
        source = PartySource(infoHash = "ABCDEF", fileIdx = 3, filename = "Show.S01E02.mkv")
    )

    private fun party(self: String) = ActiveParty(
        id = "p1", code = "ABC234", memberId = self, hostMemberId = "h",
        members = listOf(PartyMember("h", "Hannah"), PartyMember("m", "Max")),
        presence = emptyMap(), media = media, maxMembers = 3, connected = true
    )

    private fun stream(hash: String? = null, idx: Int? = null, file: String? = null) = Stream(
        name = "S", title = null, description = null, url = if (hash == null) "https://x/y" else null, ytId = null,
        infoHash = hash, fileIdx = idx, externalUrl = null,
        behaviorHints = file?.let { StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, filename = it) }, addonName = "Addon", addonLogo = null
    )

    private val key = partyMediaKey("series", "tt1", 1, 2)

    @Test
    fun `the host's source is marked by torrent and file, else by file name`() {
        val marks = PartySourceMarks.of(party("m"), null, key)!!
        assertTrue(marks.isPartySource(stream(hash = "abcdef", idx = 3)))
        assertFalse(marks.isPartySource(stream(hash = "abcdef", idx = 4)))
        assertTrue(marks.isPartySource(stream(file = "show.s01e02.mkv")))
        assertFalse(marks.isPartySource(stream(file = "other.mkv")))
        assertEquals("Hannah", marks.hostName)
    }

    @Test
    fun `another episode, or no party, has no marks`() {
        assertNull(PartySourceMarks.of(party("m"), null, partyMediaKey("series", "tt1", 1, 3)))
        assertNull(PartySourceMarks.of(null, null, key))
    }

    @Test
    fun `members are offered the host's shared stream first, the host is not`() {
        val shared = PartyStream(mediaKey = key, url = "https://debrid/file.mkv", addonName = "Debrid")
        assertEquals(shared, PartySourceMarks.of(party("m"), shared, key)!!.shared)
        assertNull(PartySourceMarks.of(party("h"), shared, key)!!.shared)
        // A stream shared for something else isn't this title's.
        assertNull(PartySourceMarks.of(party("m"), shared.copy(mediaKey = "movie|tt9||"), key)!!.shared)
    }
}
