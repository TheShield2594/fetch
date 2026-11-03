package com.fetch.desktop.party

import com.fetch.tv.domain.model.Stream
import com.fetch.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PartySyncTest {
    private fun playback(paused: Boolean = false, position: Long = 10_000, at: Long = 1_000, rate: Float = 1f, seq: Long = 1, member: String? = "a", waiting: Boolean = false) =
        PartyPlayback(seq = seq, memberId = member, paused = paused, positionMs = position, rate = rate, at = at, mediaKey = "movie|tt1||", waiting = waiting)

    @Test
    fun `expected position runs with the clock and rate, and stops when paused`() {
        assertEquals(12_000L, PartySync.expectedPositionMs(playback(), nowMs = 3_000))
        assertEquals(14_000L, PartySync.expectedPositionMs(playback(rate = 2f), nowMs = 3_000))
        assertEquals(10_000L, PartySync.expectedPositionMs(playback(paused = true), nowMs = 3_000))
        assertEquals(11_000L, PartySync.expectedPositionMs(playback(), nowMs = 3_000, durationMs = 11_000))
        assertEquals(10_000L, PartySync.expectedPositionMs(playback(), nowMs = 0)) // a clock behind the stamp
    }

    @Test
    fun `corrections use the TV's tolerances`() {
        val playing = playback()
        assertEquals(PartyCorrection.InSync, PartySync.correction(10_200, 10_000, playing, nudging = false))
        // 300 ms ahead: slow down by 5%.
        assertEquals(PartyCorrection.Nudge(0.95f), PartySync.correction(10_300, 10_000, playing, nudging = false))
        // 300 ms behind: speed up.
        assertEquals(PartyCorrection.Nudge(1.05f), PartySync.correction(9_700, 10_000, playing, nudging = false))
        // While nudging, keep at it until within 80 ms.
        assertEquals(PartyCorrection.Nudge(0.95f), PartySync.correction(10_100, 10_000, playing, nudging = true))
        assertEquals(PartyCorrection.InSync, PartySync.correction(10_050, 10_000, playing, nudging = true))
        assertEquals(PartyCorrection.Seek(10_000), PartySync.correction(12_000, 10_000, playing, nudging = false))
        val paused = playback(paused = true)
        assertEquals(PartyCorrection.InSync, PartySync.correction(10_400, 10_000, paused, nudging = false))
        assertEquals(PartyCorrection.Seek(10_000), PartySync.correction(10_600, 10_000, paused, nudging = false))
    }

    @Test
    fun `newer playback by sequence, then member id`() {
        assertTrue(PartySync.isNewer(playback(seq = 2), playback(seq = 1)))
        assertFalse(PartySync.isNewer(playback(seq = 1), playback(seq = 2)))
        assertTrue(PartySync.isNewer(playback(seq = 2, member = "b"), playback(seq = 2, member = "a")))
        assertTrue(PartySync.isNewer(playback(), null))
    }

    @Test
    fun `notices name what changed`() {
        val before = playback()
        assertEquals(PartyAction.Paused, PartySync.actionOf(before, playback(paused = true, seq = 2, at = 2_000, position = 11_000)))
        assertEquals(PartyAction.Resumed, PartySync.actionOf(playback(paused = true), playback(seq = 2)))
        assertEquals(PartyAction.Speed, PartySync.actionOf(before, playback(rate = 1.5f, seq = 2)))
        assertEquals(PartyAction.Seeked, PartySync.actionOf(before, playback(position = 60_000, at = 2_000, seq = 2)))
        assertNull(PartySync.actionOf(before, playback(position = 11_000, at = 2_000, seq = 2)))
        assertNull(PartySync.actionOf(playback(waiting = true), playback(paused = true, seq = 2)))
        assertNull(PartySync.actionOf(null, before))
    }

    @Test
    fun `own seeks when the host controls playback`() {
        assertEquals(OwnSeek.Behind(5_000), PartySync.ownSeek(5_000, partyPositionMs = 60_000, behind = false))
        assertEquals(OwnSeek.Blocked, PartySync.ownSeek(90_000, partyPositionMs = 60_000, behind = false))
        assertEquals(OwnSeek.CatchUp, PartySync.ownSeek(90_000, partyPositionMs = 60_000, behind = true))
        assertTrue(PartySync.shouldRejoin(1_500, playing = true))
        assertFalse(PartySync.shouldRejoin(1_500, playing = false))
        assertEquals(5_000L, PartySync.reportedGapMs(1_000))
        assertEquals(10_000L, PartySync.reportedGapMs(8_000))
    }

    @Test
    fun `the coordinator is the host when online, else the first member online`() {
        val party = ActiveParty(
            id = "p", code = "ABC234", memberId = "b", hostMemberId = "a",
            members = listOf(PartyMember("a", "A"), PartyMember("b", "B"), PartyMember("c", "C")),
            presence = mapOf("c" to PartyPresence("c")), media = PartyMedia("movie", "tt1", "movie"),
            maxMembers = 3, connected = true
        )
        assertEquals("b", PartySync.coordinatorId(party))
        assertEquals("a", PartySync.coordinatorId(party.copy(presence = mapOf("a" to PartyPresence("a")))))
        assertEquals("a", PartySync.coordinatorId(party.copy(hostOnly = true)))
        val waiting = party.copy(presence = mapOf("c" to PartyPresence("c", PartyMemberStatus.Loading, "movie|tt1||")))
        assertEquals(listOf("c"), PartySync.waitingMemberIds(waiting, emptySet()))
        assertEquals(emptyList<String>(), PartySync.waitingMemberIds(waiting, setOf("c")))
    }

    @Test
    fun `the clock keeps the sample with the shortest round trip`() {
        val time = TestTimeSource()
        val clock = PartyClock(time, wallClockMs = { 42L })
        assertEquals(42L, clock.nowMs())
        val slow = clock.mark()
        time += 400.milliseconds
        clock.addSample(1_000_000, slow)
        assertEquals(1_000_200L, clock.nowMs())
        val fast = clock.mark()
        time += 100.milliseconds
        clock.addSample(2_000_000, fast)
        assertEquals(2_000_050L, clock.nowMs())
        val slower = clock.mark()
        time += 300.milliseconds
        clock.addSample(9_000_000, slower) // ignored: a longer round trip
        assertEquals(2_000_350L, clock.nowMs())
    }

    @Test
    fun `codes, invite links and media keys`() {
        assertEquals("ABC234", normalizePartyCode("abc-234"))
        assertTrue(isValidPartyCode("abc 234"))
        assertFalse(isValidPartyCode("ABC10")) // 1 and 0 aren't in the alphabet
        assertEquals("ABC 234", formatPartyCode("abc234"))
        assertEquals("ABC234", partyCodeFrom("fetch://party/abc234"))
        assertEquals("series|tt1|1|2", partyMediaKey("Series", "tt1", 1, 2))
        assertEquals("movie|tt1||", PartyMedia("movie", "tt1", "movie").key)
        assertTrue(isShareableStreamUrl("https://debrid.example.com/file.mkv"))
        assertFalse(isShareableStreamUrl("http://127.0.0.1:8080/file.mkv"))
        assertFalse(isShareableStreamUrl("http://localhost/file"))
    }

    @Test
    fun `party source matches by torrent and file, else file name`() {
        fun stream(hash: String? = null, idx: Int? = null, filename: String? = null) = Stream(
            name = null, title = null, description = null, url = "https://x/y", ytId = null, infoHash = hash, fileIdx = idx, externalUrl = null,
            behaviorHints = StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, filename = filename),
            addonName = "A", addonLogo = null
        )
        assertTrue(PartySource(infoHash = "ABC", fileIdx = 1).matches(stream(hash = "abc", idx = 1)))
        assertFalse(PartySource(infoHash = "ABC", fileIdx = 1).matches(stream(hash = "abc", idx = 2)))
        assertTrue(PartySource(filename = "Movie.2160p.mkv").matches(stream(filename = "movie.2160p.MKV")))
        assertFalse(PartySource().matches(stream(filename = "x")))
    }
}
