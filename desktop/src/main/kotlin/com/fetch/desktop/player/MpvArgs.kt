package com.fetch.desktop.player

import com.fetch.desktop.data.AssOverride
import com.fetch.desktop.data.AudioChannels
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.SubtitleMode
import com.fetch.desktop.data.VideoFit
import com.fetch.tv.core.streams.ranking.StreamLanguage

/** Turns [PlayerSettings] into mpv options. Kept pure so the mapping is testable. */
object MpvArgs {

    /** The languages offered for audio and subtitles: the ranker's, minus the pseudo ones. */
    val languages: List<StreamLanguage> =
        StreamLanguage.entries.filter { it != StreamLanguage.MULTI && it != StreamLanguage.LATINO }

    /** mpv matches track tags literally, and files use both "en" and "eng", so pass both. */
    fun languageCodes(code: String): String {
        val two = StreamLanguage.fromCode(code)?.code ?: code
        // ISO 639-2 has a terminology code ("fra", from Java) and for some languages a
        // bibliographic one ("fre") that files also use.
        val terminology = runCatching { java.util.Locale.forLanguageTag(two).isO3Language }.getOrNull()?.takeIf { it.isNotBlank() }
        return listOfNotNull(two, terminology, BIBLIOGRAPHIC[terminology]).distinct().joinToString(",")
    }

    private val BIBLIOGRAPHIC = mapOf(
        "fra" to "fre", "deu" to "ger", "nld" to "dut", "zho" to "chi", "ces" to "cze", "ell" to "gre", "fas" to "per",
        "ron" to "rum", "slk" to "slo", "sqi" to "alb", "hye" to "arm", "eus" to "baq", "mya" to "bur", "kat" to "geo",
        "isl" to "ice", "mkd" to "mac", "mri" to "mao", "msa" to "may", "bod" to "tib", "cym" to "wel"
    )

    fun build(settings: PlayerSettings, subtitleFiles: List<String> = emptyList()): List<String> = buildList {
        add("--hwdec=${settings.hwdec.mpvValue}")
        if (settings.fullscreen) add("--fs")
        languageList(settings.audioLanguage, settings.secondaryAudioLanguage)?.let { add("--alang=$it") }
        languageList(settings.subtitleLanguage, settings.secondarySubtitleLanguage)?.let { add("--slang=$it") }
        when (settings.subtitleMode) {
            SubtitleMode.AUTO -> Unit
            // Don't pick a subtitle that matches the audio language.
            SubtitleMode.FOREIGN_AUDIO -> add("--subs-with-matching-audio=no")
            SubtitleMode.OFF -> add("--sid=no")
        }
        if (settings.subtitleScale != 1.0) add("--sub-scale=${settings.subtitleScale}")
        if (settings.subtitleBackground) {
            add("--sub-border-style=opaque-box")
            add("--sub-back-color=0.0/0.0/0.0/0.6")
        }
        if (!settings.useForcedSubtitles) add("--subs-fallback-forced=no")
        if (settings.stripSdh) add("--sub-filter-sdh=yes")
        if (settings.subtitleBold) add("--sub-bold=yes")
        if (!settings.subtitleColor.equals("#FFFFFF", ignoreCase = true)) add("--sub-color=${settings.subtitleColor}")
        if (!settings.subtitleBackground) {
            if (!settings.subtitleOutline) {
                add("--sub-border-size=0")
            } else {
                if (settings.subtitleOutlineSize != 3.0) add("--sub-border-size=${settings.subtitleOutlineSize}")
                if (!settings.subtitleOutlineColor.equals("#000000", ignoreCase = true)) add("--sub-border-color=${settings.subtitleOutlineColor}")
            }
        }
        if (settings.subtitlePosition != 100) add("--sub-pos=${settings.subtitlePosition.coerceIn(0, 150)}")
        if (settings.assOverride != AssOverride.SCALE) add("--sub-ass-override=${settings.assOverride.mpvValue}")
        if (settings.audioPassthrough) add("--audio-spdif=ac3,eac3,dts,dts-hd,truehd")
        if (settings.audioChannels != AudioChannels.AUTO) add("--audio-channels=${settings.audioChannels.mpvValue}")
        if (settings.normalizeDownmix) add("--audio-normalize-downmix=yes")
        if (settings.volumeBoostDb > 0) add("--af=lavfi=[volume=${settings.volumeBoostDb.coerceAtMost(20)}dB]")
        // Dialogue level when surround is mixed down.
        addAll(AudioExtras.mpvArgs(settings.extras))
        addAll(fitArgs(settings.videoFit))
        settings.cacheMb?.let {
            add("--cache=yes")
            add("--demuxer-max-bytes=${it}MiB")
        }
        if (settings.subtitleMode != SubtitleMode.OFF) subtitleFiles.forEach { add("--sub-file=$it") }
        addAll(splitArgs(settings.extraMpvArgs))
    }

    /** "en,eng,fr,fre": the first language's codes, then the second's; null when neither is set. */
    fun languageList(first: String?, second: String?): String? =
        listOfNotNull(first, second).distinct().joinToString(",") { languageCodes(it) }.ifEmpty { null }

    /** The mpv properties for [fit]; the built-in player also sets them live. */
    fun fitProperties(fit: VideoFit): List<Pair<String, String>> = when (fit) {
        VideoFit.FIT -> listOf("keepaspect" to "yes", "panscan" to "0.0", "video-zoom" to "0.0")
        VideoFit.CROP -> listOf("keepaspect" to "yes", "panscan" to "1.0", "video-zoom" to "0.0")
        VideoFit.STRETCH -> listOf("keepaspect" to "no", "panscan" to "0.0", "video-zoom" to "0.0")
        VideoFit.ZOOM -> listOf("keepaspect" to "yes", "panscan" to "0.0", "video-zoom" to "0.15")
    }

    private fun fitArgs(fit: VideoFit): List<String> =
        if (fit == VideoFit.FIT) emptyList() else fitProperties(fit).map { (k, v) -> "--$k=$v" }

    /** Splits on whitespace like a shell: quotes keep spaces inside one option and are dropped. */
    fun splitArgs(text: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var inToken = false
        for (c in text) {
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> { quote = c; inToken = true }
                quote == null && c.isWhitespace() -> {
                    if (inToken) args += current.toString()
                    current.clear()
                    inToken = false
                }
                else -> { current.append(c); inToken = true }
            }
        }
        if (inToken) args += current.toString()
        return args
    }
}
