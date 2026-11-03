package com.fetch.desktop.playback

import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.desktop.player.MpvProgress
import com.fetch.desktop.skip.Segment

/**
 * Up Next, preloading and the queue for playback in mpv's own window, which Fetch follows over
 * IPC: the built-in player's `PositionWatch`, driven by mpv's `time-pos` and `duration`.
 *
 * - At the next-episode threshold mpv's OSD counts down [COUNTDOWN_SEC] seconds of playback
 *   ("Up Next: S1 E4 · starting in 5 s"), then the window closes and [movedOn] is set, for the
 *   app to start the next episode. Alt+N keeps watching instead ([onCancelled]).
 * - "Preload next episode sources" asks the addons ahead, as in the built-in player ([onPreload]).
 * - Alt+Q shows what's queued on the OSD.
 *
 * The countdown follows the playback position, so it waits while paused, and a seek back before
 * the threshold calls it off until the threshold comes again. Every method returns the IPC
 * commands to send to mpv.
 */
class MpvWindowUpNext(
    private val settings: () -> AutoPlaySettings,
    private val segments: List<Segment>,
    /** "S1 E4 · Title" when an episode comes next and autoplay is on; null otherwise (no Up Next). */
    private val nextLabel: String?,
    private val timeoutSeconds: () -> Int,
    /** The queue's upcoming items' labels, for Alt+Q. */
    private val upcoming: () -> List<String>,
    private val onPreload: () -> Unit,
    private val onCancelled: () -> Unit
) {
    @Volatile var movedOn = false
        private set
    @Volatile private var armed = false
    @Volatile private var preloaded = false
    @Volatile private var cancelled = false
    @Volatile private var countdownFrom: Double? = null
    @Volatile private var lastShown: Int? = null

    /** Key bindings for the window, sent once connected. */
    fun startup(): List<String> = buildList {
        if (nextLabel != null) add(MpvProgress.keybindCommand(CANCEL_KEY, CANCEL_MESSAGE))
        add(MpvProgress.keybindCommand(QUEUE_KEY, QUEUE_MESSAGE))
    }

    fun onTimes(positionSec: Double, durationSec: Double): List<String> {
        if (movedOn || durationSec <= 0) return emptyList()
        val ap = settings()
        if (!armed) {
            if (!NextEpisodeRules.isAwayFromEnd(positionSec, durationSec, segments, ap)) return emptyList()
            armed = true
        }
        nextLabel ?: return emptyList()
        if (ap.preloadNextEpisode && !preloaded && NextEpisodeRules.shouldPreload(positionSec, durationSec, segments, ap, timeoutSeconds())) {
            preloaded = true
            onPreload()
        }
        if (cancelled) return emptyList()
        val reached = NextEpisodeRules.thresholdReached(positionSec, durationSec, segments, ap)
        val from = countdownFrom
        if (from == null) {
            if (!reached) return emptyList()
            countdownFrom = positionSec
            return show(COUNTDOWN_SEC)
        }
        if (positionSec < from - 1.0 && !reached) {
            // Seeked back before the threshold: wait for it again.
            countdownFrom = null
            lastShown = null
            return listOf(MpvProgress.showTextCommand("", 1))
        }
        val left = COUNTDOWN_SEC - (positionSec - from).toInt()
        if (left <= 0 || durationSec - positionSec <= 0.5) {
            movedOn = true
            return listOf(MpvProgress.showTextCommand("Up Next: $nextLabel", 2_000), MpvProgress.QUIT_COMMAND)
        }
        return if (left != lastShown) show(left) else emptyList()
    }

    fun onClientMessage(args: List<String>): List<String> = when (args.firstOrNull()) {
        CANCEL_MESSAGE -> {
            if (nextLabel == null || movedOn) emptyList()
            else {
                cancelled = true
                countdownFrom = null
                onCancelled()
                listOf(MpvProgress.showTextCommand("Up Next cancelled", 2_000))
            }
        }
        QUEUE_MESSAGE -> {
            val items = upcoming()
            val text = if (items.isEmpty()) "The queue is empty"
            else "Queue\n" + items.take(QUEUE_LINES).mapIndexed { i, label -> "${i + 1}. $label" }.joinToString("\n") +
                if (items.size > QUEUE_LINES) "\n+${items.size - QUEUE_LINES} more" else ""
            listOf(MpvProgress.showTextCommand(text, MpvProgress.OSD_NOTE_MS))
        }
        else -> emptyList()
    }

    private fun show(secondsLeft: Int): List<String> {
        lastShown = secondsLeft
        return listOf(MpvProgress.showTextCommand("Up Next: $nextLabel\nStarting in $secondsLeft s · Alt+N to keep watching", 1_500))
    }

    companion object {
        const val COUNTDOWN_SEC = 5
        const val CANCEL_KEY = "Alt+n"
        const val QUEUE_KEY = "Alt+q"
        const val CANCEL_MESSAGE = "fetch-upnext-cancel"
        const val QUEUE_MESSAGE = "fetch-queue"
        private const val QUEUE_LINES = 8
    }
}
