package com.fetch.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.fetch.desktop.AppState
import com.fetch.desktop.PlayerSession
import com.fetch.desktop.player.SubtitleOrganisation
import com.fetch.desktop.player.autosync.AutoSyncAligner
import com.fetch.desktop.player.autosync.AutoSyncRetimer
import com.fetch.desktop.player.autosync.AutoSyncSources
import com.fetch.desktop.player.autosync.Cue
import com.fetch.desktop.player.autosync.CueParser
import com.fetch.desktop.player.autosync.FfprobeReference
import com.fetch.desktop.player.autosync.FileRangeSource
import com.fetch.desktop.player.autosync.FileReference
import com.fetch.desktop.player.autosync.HttpRangeSource
import com.fetch.desktop.player.autosync.LiveReference
import com.fetch.desktop.player.autosync.MatroskaCueIndex
import com.fetch.desktop.player.autosync.ReferenceTrack
import com.fetch.desktop.player.autosync.RetimeResult
import com.fetch.desktop.player.autosync.Span
import com.fetch.desktop.player.autosync.SubtitleRetimeWriter
import com.fetch.desktop.player.autosync.TimeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

// --- Automatic subtitle sync (the TV's player/autosync), for the built-in player ---

/**
 * Auto Sync for the addon subtitle playing: compared with the video's own subtitles, then
 * shifted (mpv's sub-delay), or re-timed into a copy mpv loads when the timing drifts or the
 * subtitle was made for another cut. When it won't match, the other addon subtitles in its
 * language are tried, as on TV. A re-timed copy stands for its addon subtitle, so selecting it
 * doesn't start over.
 */
@Composable
internal fun AutoSyncEffect(state: AppState, session: PlayerSession) {
    val mpv = session.mpv
    val extras = session.extras
    val enabled = state.settings.player.extras.autoSync
    val selected = mpv.tracks.firstOrNull { it.type == "sub" && it.selected }
    val file = selected?.takeIf { it.external }?.externalFilename
    val url = file?.let { extras.syncedFiles[it] ?: it }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    LaunchedEffect(url, enabled) {
        if (!enabled || url == null || extras.syncedSubtitleUrl == url) return@LaunchedEffect
        AutoSyncRun(state, session, url).run()
    }
}

/** One subtitle Auto Sync can apply: its text, its lines and where it came from. */
private class SyncCandidate(val url: String, val raw: String, val cues: List<Cue>, val language: String?, val addon: String?)

private class AutoSyncRun(private val state: AppState, private val session: PlayerSession, private val url: String) {
    private val mpv = session.mpv
    private val extras = session.extras
    private val settings = state.settings.player.extras
    private var alternativesTried = false
    /** The copy mpv shows now, so a better re-timing replaces rather than piles up. */
    private var appliedTrackFile: String? = null

    suspend fun run() {
        // A delay Auto Sync set for another subtitle doesn't belong to this one.
        extras.syncedDelaySec?.let { if (abs(mpv.subtitleDelay - it) < 0.0005) mpv.changeSubtitleDelay(0.0) }
        extras.syncedDelaySec = null
        extras.syncedSubtitleUrl = null
        val addon = session.addonSubtitles.orEmpty().firstOrNull { it.url == url }
        val language = addon?.language ?: mpv.tracks.firstOrNull { it.type == "sub" && it.selected }?.lang

        val reference = AutoSyncSources.reference(mpv.tracks, language)
        if (reference == null) {
            mpv.showText("Sync failed: this video has no embedded subtitles to compare with.")
            return
        }
        val raw = withContext(Dispatchers.IO) { downloadSubtitle(url) }
        val cues = raw?.let(CueParser::parse).orEmpty()
        if (raw == null || cues.isEmpty()) {
            mpv.showText("Sync failed: Auto Sync doesn't support this subtitle format.")
            return
        }
        val candidate = SyncCandidate(url, raw, cues, language, addon?.addon)
        // Chosen again after a sync: the same timing, without comparing again.
        extras.syncResults[url]?.let { map ->
            apply(candidate, map, switched = false, announce = false)
            return
        }
        try {
            if (reference.image) syncWithFile(candidate, reference) else syncLive(candidate, reference)
        } finally {
            extras.autoSyncStatus = null
        }
    }

    // --- Against a text track, as mpv shows it ---

    private suspend fun syncLive(candidate: SyncCandidate, reference: ReferenceTrack) {
        val previousSecondary = mpv.property("secondary-sid")
        val previousVisibility = mpv.property("secondary-sub-visibility")
        // Only keep watching after a match while the second subtitle slot is free.
        val canMonitor = previousSecondary == null || previousSecondary == "no"
        extras.autoSyncStatus = "Auto Sync: comparing with the video's subtitles…"
        mpv.setProperty("secondary-sub-visibility", "no")
        mpv.setProperty("secondary-sid", reference.track.id)
        val live = LiveReference()
        var checkpoint = AutoSyncAligner.MIN_REFERENCE_CUES
        var previous: RetimeResult? = null
        var applied: TimeMap? = null
        var reapplied = 0
        try {
            while (true) {
                delay(250)
                live.tick((mpv.position * 1000).toLong())
                live.observe(
                    mpv.property("secondary-sub-start")?.toDoubleOrNull(),
                    mpv.property("secondary-sub-end")?.toDoubleOrNull(),
                    mpv.property("secondary-sub-text")
                )
                if (live.size < checkpoint) continue
                val cues = live.cues()
                if (!AutoSyncAligner.enoughReference(cues)) {
                    checkpoint = live.size + 1
                    continue
                }
                val spans = live.spans()
                val result = withContext(Dispatchers.Default) { AutoSyncRetimer.retime(cues, spans, candidate.cues) }

                if (applied != null) {
                    // Watching on: a clearly better timing for what's still to come replaces the first.
                    val from = (spans.lastOrNull()?.startMs ?: cues.first().startMs) - 60_000
                    val to = candidate.cues.last().endMs
                    if (result != null && result.confident && result.map.maxDifference(applied, from.coerceAtLeast(0), to) > 250) {
                        applied = result.map
                        apply(candidate, result.map, switched = false, announce = true)
                        reapplied++
                    }
                    checkpoint = live.size + 40
                    if (live.size >= 600 || reapplied >= 3) break
                    continue
                }

                // Thorough: two estimates in a row must agree; otherwise the first confident one is used.
                val accepted = if (settings.autoSyncThorough) {
                    result?.takeIf { r -> r.confident && previous?.confident == true && agree(previous!!, r, spans, cues) }
                } else result?.takeIf { it.confident }
                previous = result
                if (accepted != null) {
                    applied = accepted.map
                    apply(candidate, accepted.map, switched = false, announce = true)
                    extras.autoSyncStatus = null
                    if (!canMonitor) break
                    checkpoint = live.size + 40
                    continue
                }
                checkpoint = live.size + 15
                // Half way to giving up, and at the end: maybe another subtitle in the language fits.
                if (live.size >= 60 && !alternativesTried && (result == null || !result.confident)) {
                    if (tryAlternatives(candidate, cues, spans, result)) break
                }
                if (live.size >= 150) {
                    mpv.showText("Sync failed: Auto Sync couldn't match this subtitle to the video.")
                    break
                }
            }
        } finally {
            mpv.setProperty("secondary-sid", previousSecondary ?: "no")
            mpv.setProperty("secondary-sub-visibility", previousVisibility ?: "yes")
        }
    }

    private fun agree(a: RetimeResult, b: RetimeResult, spans: List<Span>, cues: List<Cue>): Boolean {
        val from = spans.firstOrNull()?.startMs ?: cues.first().startMs
        val to = spans.lastOrNull()?.endMs ?: cues.last().endMs
        return a.map.maxDifference(b.map, from, to) <= 150
    }

    // --- Against an image track, by its timings in the file ---

    private suspend fun syncWithFile(candidate: SyncCandidate, reference: ReferenceTrack) {
        extras.autoSyncStatus = "Auto Sync: reading the video's subtitle timings…"
        val file = withContext(Dispatchers.IO) { readFileReference(reference) }
        if (file == null) {
            val hint = if (withContext(Dispatchers.IO) { FfprobeReference.available() }) "" else " Installing ffmpeg lets Fetch read more videos."
            mpv.showText("Sync failed: couldn't read the timings of this video's image subtitles.$hint")
            return
        }
        extras.autoSyncStatus = "Auto Sync: comparing with the video's subtitles…"
        val result = withContext(Dispatchers.Default) { AutoSyncRetimer.retime(file.cues, file.spans, candidate.cues) }
        if (result != null && result.confident) {
            apply(candidate, result.map, switched = false, announce = true)
            return
        }
        if (tryAlternatives(candidate, file.cues, file.spans, result)) return
        mpv.showText("Sync failed: Auto Sync couldn't match this subtitle to the video.")
    }

    /** The image track's timings: the Matroska index first (a few small reads), else ffprobe's sampled packets. */
    private fun readFileReference(reference: ReferenceTrack): FileReference? {
        val path = mpv.property("path") ?: return null
        val headers = AutoSyncSources.headers(mpv.property("http-header-fields"), mpv.property("user-agent"))
        val durationMs = (mpv.duration * 1000).toLong().takeIf { it > 0 }
        val source = when {
            path.startsWith("http://") || path.startsWith("https://") -> HttpRangeSource(path, headers)
            File(path).isFile -> FileRangeSource(File(path))
            else -> null
        }
        source?.let { MatroskaCueIndex.read(it, reference.track.srcId?.toLong(), reference.ordinal, durationMs) }?.let { return it }
        if (!FfprobeReference.available()) return null
        return FfprobeReference.read(path, reference.ordinal, mpv.duration.takeIf { it > 0 }, headers)
    }

    // --- Other subtitles in the language ---

    /**
     * Tries the other addon subtitles in the language against [reference]; the best confident
     * one (clearly better than [current]) is loaded, synced. True when one was.
     */
    private suspend fun tryAlternatives(candidate: SyncCandidate, reference: List<Cue>, spans: List<Span>, current: RetimeResult?): Boolean {
        alternativesTried = true
        val others = AutoSyncSources.alternatives(candidate.url, candidate.language, session.addonSubtitles.orEmpty())
        if (others.isEmpty()) return false
        val status = extras.autoSyncStatus
        extras.autoSyncStatus = "Auto Sync: trying other ${candidate.language?.let { SubtitleOrganisation.languageLabel(SubtitleOrganisation.languageKey(it)) } ?: ""} subtitles…".replace("  ", " ")
        val tried = coroutineScope {
            others.map { other ->
                async(Dispatchers.IO) {
                    val raw = downloadSubtitle(other.url) ?: return@async null
                    val cues = CueParser.parse(raw).takeIf { it.isNotEmpty() } ?: return@async null
                    val result = AutoSyncRetimer.retime(reference, spans, cues) ?: return@async null
                    SyncCandidate(other.url, raw, cues, other.language, other.addon) to result
                }
            }.awaitAll().filterNotNull()
        }
        extras.autoSyncStatus = status
        val best = tried.filter { it.second.confident }.maxByOrNull { it.second.quality } ?: return false
        if (current != null && current.confident && current.quality >= best.second.quality) return false
        apply(best.first, best.second.map, switched = true, announce = true)
        return true
    }

    // --- Applying ---

    /**
     * Shows [candidate] with [map]: a constant shift as mpv's sub-delay (nothing within the
     * tolerance), anything else as a re-timed copy mpv loads and selects.
     */
    private fun apply(candidate: SyncCandidate, map: TimeMap, switched: Boolean, announce: Boolean) {
        val first = candidate.cues.first().startMs
        val last = candidate.cues.last().endMs
        val tolerance = settings.autoSyncToleranceMs
        val small = tolerance > 0 && map.maxShift(first, last) <= tolerance
        extras.syncResults[candidate.url] = map
        // Set first: selecting the subtitle restarts the effect, which then sees it's done.
        extras.syncedSubtitleUrl = candidate.url
        val label = SubtitleOrganisation.languageLabel(SubtitleOrganisation.languageKey(candidate.language))
        val prefix = if (switched) "Sync applied with another subtitle${candidate.addon?.let { " ($it)" } ?: ""}" else "Sync applied"
        when {
            small || (map.isConstantShift && map.offsetMs.toLong() == 0L) -> {
                showOriginal(candidate, label, switched)
                extras.syncedDelaySec = 0.0
                if (announce) mpv.showText(if (switched) "$prefix: timing already matches" else "Subtitle timing already matches")
            }
            map.isConstantShift -> {
                val offset = map.offsetMs.toLong()
                showOriginal(candidate, label, switched)
                mpv.changeSubtitleDelay(offset / 1000.0)
                extras.syncedDelaySec = offset / 1000.0
                if (announce) mpv.showText("$prefix: ${AutoSyncAligner.label(offset)}")
            }
            else -> {
                val text = SubtitleRetimeWriter.retime(candidate.raw, map)
                val file = text?.let { writeCopy(candidate.url, it) }
                if (file == null) {
                    mpv.showText("Sync failed: couldn't write the re-timed subtitle.")
                    return
                }
                if (abs(mpv.subtitleDelay - (extras.syncedDelaySec ?: 0.0)) < 0.0005) mpv.changeSubtitleDelay(0.0)
                extras.syncedDelaySec = null
                extras.syncedFiles[file.path] = candidate.url
                // The copy it replaces goes, so the list holds one synced copy.
                appliedTrackFile?.let { old -> mpv.tracks.firstOrNull { it.externalFilename == old }?.let { mpv.runCommand("sub-remove", it.id) } }
                appliedTrackFile = file.path
                mpv.addSubtitle(file.path, "$label (synced)", candidate.language)
                if (announce) mpv.showText("$prefix: ${AutoSyncSources.describe(map, candidate.cues)}")
            }
        }
    }

    /** [candidate] itself on screen (not a re-timed copy): added when it's another subtitle, else selected again. */
    private fun showOriginal(candidate: SyncCandidate, label: String, switched: Boolean) {
        val copy = appliedTrackFile
        if (!switched && copy == null) return
        val original = mpv.tracks.firstOrNull { it.type == "sub" && it.externalFilename == candidate.url }
        if (original != null) mpv.selectSubtitle(original.id) else mpv.addSubtitle(candidate.url, label, candidate.language)
        copy?.let { old -> mpv.tracks.firstOrNull { it.externalFilename == old }?.let { mpv.runCommand("sub-remove", it.id) } }
        appliedTrackFile = null
    }

    private fun writeCopy(url: String, text: String): File? = runCatching {
        val dir = File(System.getProperty("java.io.tmpdir"), "fetch-autosync").apply { mkdirs() }
        val ext = when {
            text.trimStart().startsWith("WEBVTT") -> "vtt"
            text.contains("[Events]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
        File(dir, "sync-${Integer.toHexString(url.hashCode())}-${System.nanoTime()}.$ext").apply {
            writeText(text)
            deleteOnExit()
        }
    }.getOrNull()
}

internal fun downloadSubtitle(url: String): String? = runCatching {
    val client = okhttp3.OkHttpClient.Builder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
    client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return@use null
        val bytes = response.body?.byteStream()?.use { it.readNBytes(4 * 1024 * 1024) } ?: return@use null
        val gz = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val raw = if (gz) java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readNBytes(8 * 1024 * 1024) } else bytes
        String(raw, Charsets.UTF_8).takeUnless { it.contains('�') } ?: String(raw, Charsets.ISO_8859_1)
    }
}.getOrNull()
