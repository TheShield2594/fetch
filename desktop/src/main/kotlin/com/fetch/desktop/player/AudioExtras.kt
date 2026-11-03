package com.fetch.desktop.player

import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.PlayerExtras
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * The TV's audio extras for mpv: dialogue (centre channel) level in a downmix, skip silence, and
 * the audio delay kept per output device. Pure where it can be, so the mapping is testable.
 */
object AudioExtras {

    /**
     * The swresample centre mix level for [db] relative to the usual −3 dB, as a linear gain
     * (swresample's `clev`, default 0.707). Null at 0 dB: mpv's default.
     */
    fun centerMixLevel(db: Int): Double? {
        val clamped = db.coerceIn(PlayerExtras.CENTER_MIX_MIN_DB, PlayerExtras.CENTER_MIX_MAX_DB)
        if (clamped == 0) return null
        return 10.0.pow((-3.0 + clamped) / 20.0)
    }

    /** mpv's `audio-swresample-o` for [db]: what downmixing surround to fewer channels uses. */
    fun swresampleOptions(db: Int): String = centerMixLevel(db)?.let { "clev=" + "%.4f".format(Locale.ROOT, it) } ?: ""

    /** The mpv options for the window and the built-in player alike. */
    fun mpvArgs(extras: PlayerExtras): List<String> =
        swresampleOptions(extras.centerMixLevelDb).takeIf { it.isNotEmpty() }?.let { listOf("--audio-swresample-o=$it") }.orEmpty()

    /** Whether dialogue boost does anything for a track: only surround is mixed down. */
    fun centerMixApplies(channels: String?): Boolean {
        val c = channels?.lowercase() ?: return false
        if (c.contains("mono") || c.contains("stereo") || c == "1ch" || c == "2ch" || c == "2.0" || c == "1.0") return false
        return c.contains('.') || c.removeSuffix("ch").toIntOrNull()?.let { it > 2 } == true
    }

    // --- Skip silence ---

    /** The label of the level meter the built-in player adds for skip silence. */
    const val SILENCE_FILTER_LABEL = "fetch-silence"

    /** A level meter that tags every audio frame with its loudness, read back through `af-metadata`. */
    const val SILENCE_FILTER = "@$SILENCE_FILTER_LABEL:lavfi=[astats=metadata=1:reset=1:measure_overall=RMS_level:measure_perchannel=none]"

    /** The loudness key `astats` writes. */
    const val RMS_KEY = "lavfi.astats.Overall.RMS_level"

    /** The RMS level in dBFS from mpv's `af-metadata/<label>` (a JSON object, or key=value pairs). */
    fun rmsLevel(metadata: String?): Double? {
        if (metadata.isNullOrBlank()) return null
        val match = Regex("\"?" + Regex.escape(RMS_KEY) + "\"?\\s*[:=]\\s*\"?(-?[0-9.]+|-inf)").find(metadata) ?: return null
        val raw = match.groupValues[1]
        return if (raw == "-inf") Double.NEGATIVE_INFINITY else raw.toDoubleOrNull()
    }

    // --- Audio delay per output ---

    /**
     * Which output the sound goes to, as a key for the remembered delay: mpv's `audio-device`
     * when one is picked, else the audio system and its default sink (PulseAudio or PipeWire).
     */
    fun routeKey(audioDevice: String?, currentAo: String?, defaultSink: String?): String? {
        val device = audioDevice?.trim().orEmpty()
        if (device.isNotEmpty() && device != "auto") return device
        val ao = currentAo?.trim()?.takeIf { it.isNotEmpty() } ?: return defaultSink?.trim()?.takeIf { it.isNotEmpty() }?.let { "auto/$it" }
        return "$ao/" + (defaultSink?.trim()?.takeIf { it.isNotEmpty() } ?: "default")
    }

    /** The default sink's name from `pactl get-default-sink`; null without PulseAudio or PipeWire. */
    fun defaultSink(): String? = runCatching {
        val process = ProcessBuilder("pactl", "get-default-sink").redirectErrorStream(true).start()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching null
        }
        process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 && it.isNotEmpty() && !it.contains(' ') }
    }.getOrNull()
}

/**
 * Speeds through silence, as the TV's skip silence shortens it: once the sound has stayed below
 * [thresholdDb] for [minSilenceSec], playback runs at [fastSpeed] times the chosen speed until
 * there's sound again. [sample] returns the speed factor to switch to, or null to stay.
 */
class SilenceSkipper(
    private val thresholdDb: Double = -50.0,
    private val minSilenceSec: Double = 0.6,
    val fastSpeed: Double = 3.0
) {
    private var quietSince: Double? = null
    var hurrying = false
        private set

    /** [levelDb] at wall time [nowSec]; null level (no reading) changes nothing. */
    fun sample(levelDb: Double?, nowSec: Double): Double? {
        if (levelDb == null) return null
        val quiet = levelDb <= thresholdDb
        if (!quiet) {
            quietSince = null
            if (hurrying) {
                hurrying = false
                return 1.0
            }
            return null
        }
        val since = quietSince ?: nowSec.also { quietSince = it }
        if (!hurrying && nowSec - since >= minSilenceSec) {
            hurrying = true
            return fastSpeed
        }
        return null
    }

    /** Back to normal speed (a seek, a pause, the file changing). */
    fun reset(): Double? {
        quietSince = null
        return if (hurrying) 1.0.also { hurrying = false } else null
    }
}

/** The audio delay remembered for each output, in ms (the TV's AudioDelayRouteDataStore), in `audio-delays.json`. */
class AudioDelayStore(file: File = File(AppDirs.config, "audio-delays.json")) {
    private val store = JsonFile(file, MapSerializer(String.serializer(), Int.serializer())) { emptyMap() }
    private var delays: Map<String, Int> = store.load()

    fun delayMs(route: String): Int? = delays[route]

    fun save(route: String, delayMs: Int) {
        val clamped = delayMs.coerceIn(com.fetch.tv.domain.model.AUDIO_DELAY_MIN_MS, com.fetch.tv.domain.model.AUDIO_DELAY_MAX_MS)
        if (delays[route] == clamped) return
        delays = delays + (route to clamped)
        runCatching { store.save(delays) }
    }
}
