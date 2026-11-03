package com.fetch.desktop.party

import com.fetch.desktop.PlayerControls
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class PartyPlayerSyncTest {
    private class FakeHost(party: ActiveParty?) : PartySyncHost {
        override val state = MutableStateFlow(PartyUiState(party))
        override val playback = MutableStateFlow<PartyPlayback?>(null)
        var now = 100_000L
        val published = mutableListOf<PartyPlayback>()
        val media = mutableListOf<PartyMedia>()
        val statuses = mutableListOf<Triple<PartyMemberStatus, String?, Long?>>()
        val shared = mutableListOf<PartyStream?>()

        override fun nowMs() = now
        override fun publishPlayback(paused: Boolean, positionMs: Long, rate: Float) {
            val p = PartyPlayback(seq = (playback.value?.seq ?: 0) + 1, memberId = state.value.party?.memberId, paused = paused, positionMs = positionMs, rate = rate, at = now, mediaKey = state.value.party?.media?.key)
            published += p
            playback.value = p
        }
        override fun publishMedia(media: PartyMedia, positionMs: Long) {
            this.media += media
        }
        override fun shareStream(stream: PartyStream?) {
            shared += stream
        }
        override fun setLocalStatus(status: PartyMemberStatus, mediaKey: String?, behindMs: Long?) {
            statuses += Triple(status, mediaKey, behindMs)
        }
    }

    private class FakePlayer : PartyPlayer {
        override var mediaKey: String? = MOVIE.key
        override var positionMs: Long? = 10_000
        override var durationMs: Long = 7_200_000
        override var playing = true
        override var loading = false
        override var rendered = true
        override var ended = false
        override var failed = false
        private var currentRate = 1f
        override val rate: Float get() = currentRate
        val seeks = mutableListOf<Long>()
        override fun setPaused(paused: Boolean) {
            playing = !paused
        }
        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            this.positionMs = positionMs
        }
        override fun setRate(rate: Float) {
            currentRate = rate
        }
        override fun currentMedia() = mediaKey?.let { key -> if (key == MOVIE.key) MOVIE else OTHER }
        override fun currentStream(): PartyStream? = null
    }

    private class Direct : PlayerControls {
        var toggles = 0
        override val speed = 1.0
        override fun togglePause() { toggles++ }
        override fun seekTo(seconds: Double) {}
        override fun seekBy(seconds: Double) {}
        override fun changeSpeed(value: Double) {}
    }

    private fun party(self: String = "a", host: String = "a", hostOnly: Boolean = false) = ActiveParty(
        id = "p1", code = "ABC234", memberId = self, hostMemberId = host,
        members = listOf(PartyMember("a", "A"), PartyMember("b", "B")),
        presence = emptyMap(), media = MOVIE, maxMembers = 3, connected = true, hostOnly = hostOnly
    )

    private val time = TestTimeSource()

    private fun setup(party: ActiveParty?): Triple<FakeHost, FakePlayer, PartyPlayerSync> {
        val host = FakeHost(party)
        val player = FakePlayer()
        return Triple(host, player, PartyPlayerSync(host, player, Direct(), time))
    }

    @Test
    fun `without a party the controls go straight to the player`() {
        val host = FakeHost(null)
        val direct = Direct()
        val sync = PartyPlayerSync(host, FakePlayer(), direct, time)
        sync.togglePause()
        sync.tick()
        assertEquals(1, direct.toggles)
        assertTrue(host.published.isEmpty())
    }

    @Test
    fun `follows the party's pause and position`() {
        val (host, player, sync) = setup(party(self = "b", host = "a"))
        host.playback.value = PartyPlayback(seq = 1, memberId = "a", paused = true, positionMs = 30_000, at = host.now, mediaKey = MOVIE.key)
        sync.tick()
        assertFalse(player.playing)
        assertEquals(listOf(30_000L), player.seeks)
        assertEquals(PartyMemberStatus.Ready, host.statuses.last().first)
    }

    @Test
    fun `small drift nudges the speed, large drift seeks`() {
        val (host, player, sync) = setup(party(self = "b", host = "a"))
        host.playback.value = PartyPlayback(seq = 1, memberId = "a", paused = false, positionMs = 10_000, at = host.now, mediaKey = MOVIE.key)
        player.positionMs = 10_400
        sync.tick() // takes the new state; 400 ms is within the seek tolerance
        assertTrue(player.seeks.isEmpty())
        time += 3.seconds
        sync.tick()
        assertEquals(0.95f, player.rate)
        // Back in step: the nudge stops.
        player.positionMs = 10_050
        time += 1.seconds
        sync.tick()
        assertEquals(1f, player.rate)
        // Far off: seek.
        player.positionMs = 20_000
        time += 1.seconds
        sync.tick()
        assertEquals(listOf(10_000L), player.seeks)
    }

    @Test
    fun `pausing publishes when everyone can control`() {
        val (host, player, sync) = setup(party(self = "b", host = "a"))
        host.playback.value = PartyPlayback(seq = 1, memberId = "a", paused = false, positionMs = 10_000, at = host.now, mediaKey = MOVIE.key)
        sync.tick()
        sync.togglePause()
        assertFalse(player.playing)
        assertTrue(host.published.last().paused)
        sync.changeSpeed(1.5)
        assertEquals(1.5f, host.published.last().rate)
        assertEquals(1.5, sync.speed, 0.001)
        sync.seekTo(60.0)
        assertEquals(60_000L, host.published.last().positionMs)
    }

    @Test
    fun `with host-only control a member pauses and rewinds alone, and can't skip ahead`() {
        val (host, player, sync) = setup(party(self = "b", host = "a", hostOnly = true))
        host.playback.value = PartyPlayback(seq = 1, memberId = "a", paused = false, positionMs = 60_000, at = host.now, mediaKey = MOVIE.key)
        player.positionMs = 60_000
        sync.tick()
        assertTrue(sync.isControlLocked)

        sync.seekTo(600.0) // ahead of the party
        assertEquals(PartyPlayerSync.HOST_ONLY, sync.hint)
        assertTrue(host.published.isEmpty())
        assertFalse(sync.mayChangeMedia())

        sync.togglePause()
        assertFalse(player.playing)
        assertTrue(sync.behind)
        assertTrue(host.published.isEmpty())

        // Behind: the loop reports it and doesn't pull this member back.
        host.now += 20_000
        sync.tick()
        assertEquals(PartyMemberStatus.Behind, host.statuses.last().first)
        assertEquals(20_000L, host.statuses.last().third)
        assertEquals(20_000L, sync.behindMs)
        assertTrue(player.seeks.isEmpty())

        sync.catchUp()
        sync.tick()
        assertFalse(sync.behind)
        assertTrue(player.playing)
        assertEquals(listOf(80_000L), player.seeks)
    }

    @Test
    fun `the host's next title goes to the party, and the first frame publishes the start`() {
        val (host, player, sync) = setup(party())
        player.rendered = false
        sync.tick()
        assertEquals(PartyMemberStatus.Loading, host.statuses.last().first)
        assertNull(host.playback.value)
        player.rendered = true
        sync.tick()
        assertEquals(1, host.published.size)
        assertFalse(host.published.single().paused)

        player.mediaKey = OTHER.key
        sync.tick()
        assertEquals(OTHER, host.media.last())
    }

    @Test
    fun `a party started from a title page gets the host's source once playing`() {
        val (host, player, sync) = setup(party())
        // FakePlayer's media has no source: nothing to send.
        sync.tick()
        assertTrue(host.media.isEmpty())
        assertTrue(player.rendered)
    }

    companion object {
        val MOVIE = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie", title = "Heat")
        val OTHER = PartyMedia(type = "movie", metaId = "tt2", metaType = "movie", title = "Ronin")
    }
}
