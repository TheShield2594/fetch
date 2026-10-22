package com.fetch.tv.ui.screens.stream

/**
 * Spec words addons put in a stream name ("4k DV | HDR"). A line made only of these says nothing
 * the parsed chips don't already show.
 */
private val SPEC_WORD = Regex(
    "(?i)\\b(" +
        "\\d{3,4}p|[48]k|uhd|fhd|qhd|hd|sd|" +
        "dv|dovi|dolby|vision|hdr10\\+|hdr10|hdr|hlg|sdr|imax|" +
        "hevc|avc|av1|vp9|[xh]\\.?26[45]|10[ -]?bits?|8[ -]?bits?|" +
        "atmos|truehd|dts(?:-?(?:hd|x|ma))?|ddp?(?:\\+|\\b)|e?-?ac-?3|aac|flac|opus|" +
        "remux|web-?dl|web-?rip|web|blu-?ray|bdrip|brrip|hdtv|" +
        "\\d(?:\\.\\d)?ch|[257]\\.[01]" +
        ")(?=\\W|$)"
)

/**
 * Drops lines of an addon's stream name that only repeat its parsed specs, keeping the lines
 * that name the addon or the release. Returns the original text if every line would go.
 */
internal fun withoutSpecOnlyLines(text: String): String {
    val lines = text.lines()
    if (lines.size < 2) return text
    val kept = lines.filter { line ->
        line.isNotBlank() && SPEC_WORD.replace(line, "").any { it.isLetterOrDigit() }
    }
    return if (kept.isEmpty()) text else kept.joinToString("\n")
}
