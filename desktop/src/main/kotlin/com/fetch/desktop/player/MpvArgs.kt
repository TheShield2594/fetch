package com.fetch.desktop.player

import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.SubtitleMode
import com.fetch.tv.core.streams.ranking.StreamLanguage

/** Turns [PlayerSettings] into mpv options. Kept pure so the mapping is testable. */
object MpvArgs {

    /** The languages offered for audio and subtitles: the ranker's, minus the pseudo ones. */
    val languages: List<StreamLanguage> =
        StreamLanguage.entries.filter { it != StreamLanguage.MULTI && it != StreamLanguage.LATINO }

    /** mpv matches track tags literally, and files use both "en" and "eng", so pass both. */
    fun languageCodes(code: String): String {
        val language = StreamLanguage.fromCode(code) ?: return code
        val three = language.names.firstOrNull { it.length == 3 }
        return listOfNotNull(language.code, three).distinct().joinToString(",")
    }

    fun build(settings: PlayerSettings, subtitleFiles: List<String> = emptyList()): List<String> = buildList {
        add("--hwdec=${settings.hwdec.mpvValue}")
        if (settings.fullscreen) add("--fs")
        settings.audioLanguage?.let { add("--alang=${languageCodes(it)}") }
        settings.subtitleLanguage?.let { add("--slang=${languageCodes(it)}") }
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
        if (settings.audioPassthrough) add("--audio-spdif=ac3,eac3,dts,dts-hd,truehd")
        if (settings.subtitleMode != SubtitleMode.OFF) subtitleFiles.forEach { add("--sub-file=$it") }
        addAll(splitArgs(settings.extraMpvArgs))
    }

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
