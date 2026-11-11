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

/** A run of 24+ characters with no space: a release file name such as `Movie.2024.2160p.WEB-DL.mkv`. */
private val LONG_TOKEN = Regex("\\S{24,}")

/** Characters release names separate their parts with; a line may break after any of them. */
private val TOKEN_SEPARATOR = Regex("([._\\-+\\]\\)])(?=\\S)")

/**
 * Lets a release file name wrap between its parts ("Movie.2024." / "2160p.WEB-DL") instead of
 * mid-token ("Movie.2024.21" / "60p"), by putting a zero-width space after each separator inside
 * long unbroken runs. Ordinary words are left alone.
 */
internal fun wrappableReleaseName(text: String): String =
    LONG_TOKEN.replace(text) { match -> TOKEN_SEPARATOR.replace(match.value, "$1​") }
