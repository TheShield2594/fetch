package com.fetch.desktop.party

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.PlayerControls
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The built-in player as the party's sync loop sees it. Positions are milliseconds. */
interface PartyPlayer {
    /** [partyMediaKey] of what's playing; null when nothing a party can follow is loaded. */
    val mediaKey: String?
    val positionMs: Long?
    val durationMs: Long
    /** Playing, or about to (not paused). */
    val playing: Boolean
    /** Loading or buffering. */
    val loading: Boolean
    /** The current file has shown its first frame. */
    val rendered: Boolean
    val ended: Boolean
    val failed: Boolean
    /** mpv's speed right now (including a sync nudge). */
    val rate: Float
    fun setPaused(paused: Boolean)
    fun seekTo(positionMs: Long)
    fun setRate(rate: Float)
    fun currentMedia(): PartyMedia?
    /** What's playing as a link friends can open, or null when it can't be shared. */
    fun currentStream(): PartyStream?
}

/**
 * Keeps the built-in player with the party, as the TV's `PlayerRuntimeControllerParty` does:
 * every [TICK] it reports this member's status, follows the party's play/pause, rate and
 * position (nudging the speed for small drift, seeking for large), publishes the host's media
 * changes and shared stream, and handles being behind the party.
 *
 * It is also the player's [PlayerControls] while a party is on: pause, seek and speed go to the
 * party, and when only the host may control playback a member's pause or rewind is their own
 * ("behind", with Catch up).
 */
class PartyPlayerSync(
    private val host: PartySyncHost,
    private val player: PartyPlayer,
    /** Controls for when no party is following this player. */
    private val direct: PlayerControls,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : PlayerControls {

    /** Paused or rewound alone while the host controls playback. */
    var behind by mutableStateOf(false)
        private set
    var behindMs: Long? by mutableStateOf(null)
        private set

    /** A short line for the player to show for a moment ("Only the host can control playback"). */
    var hint: String? by mutableStateOf(null)
        private set
    /** Bumped with every [hint], so the same text shown twice still restarts its timer. */
    var hintSerial by mutableStateOf(0)
        private set

    fun showMessage(text: String) {
        hint = text
        hintSerial++
    }

    fun clearHint(serial: Int) {
        if (serial == hintSerial) hint = null
    }

    /** The speed the user picked; the party's when synced. */
    private var userRate: Float? by mutableStateOf(null)
    private var rateNudge: Float? = null
    private var previousKey: String? = null

    // The TV loop's locals, kept between ticks.
    private var partyId: String? = null
    private var bufferingSince: TimeMark? = null
    private var mismatchSince: TimeMark? = null
    private var lastSeek: TimeMark? = null
    private var lastCorrection: TimeMark? = null
    private var lastSeq: Pair<Long, String?>? = null
    private var lastStatus: Triple<PartyMemberStatus, String, Long?>? = null
    private var lastShared: PartyStream? = null
    private var sentSource = false
    private var wasRendered = false
    private var publishedInitial = false
    private var seekLeadMs = 0L
    private var measureSeekLead = false
    private var wasBehind = false

    private val party: ActiveParty? get() = host.state.value.party

    /** The party is watching what this player plays. */
    val isSynced: Boolean
        get() = party?.media?.key?.let { it == player.mediaKey } == true

    /** Only the host controls playback, and this player plays the party's media. */
    val isControlLocked: Boolean
        get() = party?.let { current -> !current.canControl && current.media.key == player.mediaKey } == true

    /** Where to start [key] to join the party where it is; null when the party isn't on it. */
    fun resumePositionMs(key: String): Long? {
        val current = party ?: return null
        if (current.media.key != key) return null
        val playback = host.playback.value ?: return null
        if (playback.mediaKey != null && playback.mediaKey != key) return null
        return PartySync.expectedPositionMs(playback, host.nowMs())
    }

    // --- Controls ---

    override val speed: Double
        get() = if (party != null && isSynced) (userRate ?: player.rate).toDouble() else direct.speed

    override fun togglePause() {
        if (party == null || !isSynced) return direct.togglePause()
        if (isControlLocked) {
            toggleOwnPlayback()
            return
        }
        val playing = player.playing
        player.setPaused(playing)
        publish(paused = playing)
    }

    override fun seekTo(seconds: Double) {
        if (party == null || !isSynced) return direct.seekTo(seconds)
        val requested = (seconds * 1000).toLong().coerceAtLeast(0L).let { ms ->
            if (player.durationMs > 0) ms.coerceAtMost(player.durationMs) else ms
        }
        val target = if (isControlLocked) ownSeekTarget(requested) ?: return else requested
        player.seekTo(target)
        publish(positionMs = target)
    }

    override fun seekBy(seconds: Double) {
        if (party == null || !isSynced) return direct.seekBy(seconds)
        val current = player.positionMs ?: 0L
        seekTo((current + (seconds * 1000).toLong()).coerceAtLeast(0L) / 1000.0)
    }

    override fun changeSpeed(value: Double) {
        if (party == null || !isSynced) return direct.changeSpeed(value)
        if (blocksControl()) return
        rateNudge = null
        player.setRate(value.toFloat())
        userRate = value.toFloat()
        publish(rate = value.toFloat())
    }

    override fun mayChangeMedia(userInitiated: Boolean): Boolean {
        if (!isControlLocked) return true
        if (userInitiated) blocksControl()
        return false
    }

    /** Catch up: back with the party after being behind. */
    fun catchUp() = markBehind(false)

    /**
     * The player is closing: pause the party when nobody else is watching, and report
     * "choosing" (the TV's `leavePartyPlayback`).
     */
    fun leave() {
        val current = party ?: return
        val playback = host.playback.value
        val othersWatching = current.presence.values.any {
            it.memberId != current.memberId && it.mediaKey == current.media.key && it.status != PartyMemberStatus.Browsing
        }
        if (current.media.key == player.mediaKey && playback != null && !playback.paused && !othersWatching) {
            host.publishPlayback(paused = true, positionMs = player.positionMs ?: playback.positionMs, rate = playback.rate)
        }
        host.setLocalStatus(PartyMemberStatus.Browsing, null)
    }

    private fun toggleOwnPlayback() {
        when {
            player.playing -> {
                markBehind(true)
                applyPaused(paused = true, force = true)
            }
            behind -> applyPaused(paused = false, force = true)
            else -> blocksControl()
        }
    }

    private fun ownSeekTarget(targetMs: Long): Long? {
        val playback = host.playback.value ?: return targetMs
        val partyPositionMs = PartySync.expectedPositionMs(playback, host.nowMs(), player.durationMs.coerceAtLeast(0L))
        return when (val seek = PartySync.ownSeek(targetMs, partyPositionMs, behind)) {
            is OwnSeek.Behind -> {
                markBehind(true)
                seek.positionMs
            }
            OwnSeek.CatchUp -> {
                markBehind(false)
                null
            }
            OwnSeek.Blocked -> {
                blocksControl()
                null
            }
        }
    }

    private fun blocksControl(): Boolean {
        if (!isControlLocked) return false
        showMessage(HOST_ONLY)
        return true
    }

    private fun publish(
        positionMs: Long = player.positionMs ?: 0L,
        rate: Float = host.playback.value?.rate ?: userRate ?: player.rate,
        paused: Boolean = !player.playing,
    ) {
        if (!isSynced) return
        host.publishPlayback(paused = paused, positionMs = positionMs, rate = rate)
    }

    private fun markBehind(value: Boolean) {
        if (behind != value) {
            behind = value
            behindMs = behindMs.takeIf { value }
        }
    }

    private fun applyPaused(paused: Boolean, force: Boolean) {
        if (!force && player.playing == !paused) return
        player.setPaused(paused)
    }

    private fun applyRate(rate: Float) {
        player.setRate(rate)
        userRate = rate
    }

    private fun clearNudge(playback: PartyPlayback? = host.playback.value) {
        if (rateNudge == null) return
        rateNudge = null
        player.setRate(playback?.rate ?: userRate ?: 1f)
    }

    // --- The loop ---

    /** Runs until cancelled; call from the UI's scope. */
    suspend fun run() {
        try {
            while (currentCoroutineContext().isActive) {
                delay(TICK)
                tick()
            }
        } finally {
            clearNudge()
        }
    }

    /** One step of the TV's `runPartySync` loop. */
    fun tick() {
        val current = party
        if (current?.id != partyId) {
            // A new party (or none): start over, as the TV restarts its loop per party.
            partyId = current?.id
            resetLoop()
            if (current == null) {
                clearNudge()
                markBehind(false)
                return
            }
        }
        current ?: return
        val key = player.mediaKey ?: return

        val previous = previousKey
        if (previous != key) {
            previousKey = key
            markBehind(false)
            sentSource = false
            if (previous != null && current.media.key == previous) player.currentMedia()?.let { host.publishMedia(it, positionMs = 0L) }
        }
        // A party started from a title page has no source yet: the host's goes in once known.
        if (current.isHost && !sentSource && current.media.key == key && current.media.source == null && player.rendered) {
            sentSource = true
            player.currentMedia()?.takeIf { it.source != null }?.let { host.publishMedia(it, positionMs = player.positionMs ?: 0L) }
        }

        val shared = if (current.isHost && current.sharing) player.currentStream() else null
        if (shared != lastShared) {
            lastShared = shared
            if (current.isHost && current.sharing) host.shareStream(shared)
        }

        val rendered = player.rendered
        if (rendered && !wasRendered) lastSeq = null
        wasRendered = rendered
        val loading = player.loading
        val behindGap = host.playback.value
            ?.takeIf { behind && current.media.key == key }
            ?.let { playbackNow ->
                player.positionMs?.let { local ->
                    PartySync.expectedPositionMs(playbackNow, host.nowMs(), player.durationMs.coerceAtLeast(0L)) - local
                }
            }
        val status = when {
            player.failed -> PartyMemberStatus.Browsing
            behindGap != null -> PartyMemberStatus.Behind
            !rendered -> PartyMemberStatus.Loading
            loading && !player.ended -> {
                val since = bufferingSince ?: timeSource.markNow().also { bufferingSince = it }
                if (since.elapsedNow() > BUFFERING_REPORT_DELAY) PartyMemberStatus.Buffering else PartyMemberStatus.Ready
            }
            else -> {
                bufferingSince = null
                PartyMemberStatus.Ready
            }
        }
        val reported = Triple(
            status,
            key,
            behindGap?.takeIf { status == PartyMemberStatus.Behind }?.let(PartySync::reportedGapMs),
        )
        if (reported != lastStatus) {
            lastStatus = reported
            host.setLocalStatus(status, key, reported.third)
        }

        val positionMs = player.positionMs
        if (current.media.key != key || positionMs == null || player.failed) {
            clearNudge()
            return
        }
        val durationMs = player.durationMs.coerceAtLeast(0L)
        val playback = host.playback.value
        if (playback == null) {
            if (rendered && !publishedInitial && current.isHost) {
                publishedInitial = true
                host.publishPlayback(paused = !player.playing, positionMs = positionMs, rate = userRate ?: player.rate)
            }
            return
        }
        publishedInitial = true
        if (!rendered) return
        if (behind && current.canControl) markBehind(false)
        if (behind) {
            clearNudge(playback)
            mismatchSince = null
            val gap = PartySync.expectedPositionMs(playback, host.nowMs(), durationMs) - positionMs
            if (PartySync.shouldRejoin(gap, playing = player.playing && !loading)) {
                markBehind(false)
            } else {
                val shown = behindMs
                if (shown == null || abs(gap - shown) >= BEHIND_DISPLAY_STEP_MS) behindMs = gap.coerceAtLeast(0L)
                wasBehind = true
                return
            }
        }
        if (wasBehind) {
            wasBehind = false
            lastSeq = null
            if (!current.canControl) showMessage(CAUGHT_UP)
        }

        val expected = PartySync.expectedPositionMs(playback, host.nowMs(), durationMs)
        val identity = playback.seq to playback.memberId
        if (identity != lastSeq) {
            val forced = lastSeq == null
            lastSeq = identity
            applyPaused(playback.paused, force = forced)
            val own = playback.memberId == current.memberId && !forced
            if ((!own || playback.paused) && abs(positionMs - expected) > PartySync.PAUSED_TOLERANCE_MS) {
                player.seekTo(if (playback.paused) expected else expected + seekLeadMs)
                measureSeekLead = !playback.paused
            }
            lastSeek = timeSource.markNow()
            if (rateNudge == null && abs((userRate ?: player.rate) - playback.rate) > 0.01f) applyRate(playback.rate)
            mismatchSince = null
            return
        }

        if (loading || player.ended) {
            mismatchSince = null
            return
        }

        val playing = player.playing
        if (playing == playback.paused) {
            val since = mismatchSince ?: timeSource.markNow().also { mismatchSince = it }
            if (since.elapsedNow() > OUTSIDE_TOGGLE_DELAY) {
                mismatchSince = null
                when {
                    current.canControl -> host.publishPlayback(paused = !playing, positionMs = positionMs, rate = playback.rate)
                    playing -> applyPaused(playback.paused, force = true)
                    else -> markBehind(true)
                }
            }
            return
        }
        mismatchSince = null

        val settled = lastSeek?.let { it.elapsedNow() > SEEK_SETTLE } ?: true
        val due = lastCorrection?.let { it.elapsedNow() >= CORRECTION_INTERVAL } ?: true
        if (!settled || !due) return
        lastCorrection = timeSource.markNow()
        val drift = positionMs - expected
        if (measureSeekLead && !playback.paused) {
            measureSeekLead = false
            if (abs(drift) < MAX_SEEK_LEAD_MS) seekLeadMs = (seekLeadMs - drift).coerceIn(0L, MAX_SEEK_LEAD_MS)
        }
        when (val correction = PartySync.correction(positionMs, expected, playback, rateNudge != null)) {
            is PartyCorrection.Seek -> {
                clearNudge(playback)
                val target = if (playback.paused) correction.positionMs else correction.positionMs + seekLeadMs
                player.seekTo(if (durationMs > 0L) target.coerceAtMost(durationMs) else target)
                lastSeek = timeSource.markNow()
                measureSeekLead = !playback.paused
            }
            is PartyCorrection.Nudge -> {
                if (rateNudge != correction.rate) {
                    rateNudge = correction.rate
                    player.setRate(correction.rate)
                }
            }
            PartyCorrection.InSync -> clearNudge(playback)
        }
    }

    private fun resetLoop() {
        bufferingSince = null
        mismatchSince = null
        lastSeek = null
        lastCorrection = null
        lastSeq = null
        lastStatus = null
        lastShared = null
        wasRendered = false
        publishedInitial = false
        seekLeadMs = 0L
        measureSeekLead = false
        wasBehind = false
        sentSource = false
    }

    companion object {
        val TICK = 250.milliseconds
        private val BUFFERING_REPORT_DELAY = 1_000.milliseconds
        private val OUTSIDE_TOGGLE_DELAY = 1_200.milliseconds
        private val SEEK_SETTLE = 2_500.milliseconds
        private val CORRECTION_INTERVAL = 500.milliseconds
        private const val MAX_SEEK_LEAD_MS = 3_000L
        private const val BEHIND_DISPLAY_STEP_MS = 1_000L
        const val HOST_ONLY = "Only the host can control playback"
        const val CAUGHT_UP = "Back in sync with the party"
    }
}
