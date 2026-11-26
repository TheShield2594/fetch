package com.fetch.tv.ui.screens.player

import java.util.Locale

/**
 * Ranks same-language tracks when a remembered audio or subtitle track has no exact match in a new
 * release (#722). The stored name ("English (AC-3 5.1)") is all that is kept of the old track, so
 * its channel layout, codec and commentary or description wording are read back out of it.
 *
 * Commentary and audio description lose to a main track unless the remembered one was such a
 * track, then the same regional variant, channel count and codec win, in that order.
 */
internal object RememberedTrackFallback {

    private val CHANNEL_LAYOUT = Regex("""(?<![\d.])([1-9])\.([0-2])(?![\d.])""")
    private val COMMENTARY = Regex("""\bcommentar(y|ies)\b""", RegexOption.IGNORE_CASE)
    private val DESCRIPTION = Regex(
        """\b(audio[ -]?description|described video|descriptive( audio| video)?|visually impaired)\b""",
        RegexOption.IGNORE_CASE
    )

    // Longer tokens first, so "e-ac-3" is not read as "ac-3".
    private val CODEC_TOKENS = listOf(
        "e-ac-3" to "eac3", "eac3" to "eac3", "ddp" to "eac3", "dd+" to "eac3",
        "truehd" to "truehd",
        "dts-hd" to "dts", "dts" to "dts",
        "ac-3" to "ac3", "ac3" to "ac3", "dolby digital" to "ac3",
        "aac" to "aac", "opus" to "opus", "flac" to "flac", "mp3" to "mp3", "pcm" to "pcm", "vorbis" to "vorbis"
    )

    fun score(candidate: TrackInfo, rememberedName: String?, sameVariant: Boolean): Int {
        var score = 0
        val wantCommentary = isCommentary(rememberedName)
        if (isCommentary(candidate.name) != wantCommentary) score -= 100
        val wantDescription = isDescription(rememberedName)
        if ((candidate.describesVideo || isDescription(candidate.name)) != wantDescription) score -= 100
        if (sameVariant) score += 8
        val wantChannels = channelCount(rememberedName)
        val channels = candidate.channelCount?.takeIf { it > 0 } ?: channelCount(candidate.name)
        if (wantChannels != null && channels == wantChannels) score += 4
        val wantCodec = codecFamily(rememberedName)
        val codec = codecFamily(candidate.codec) ?: codecFamily(candidate.name)
        if (wantCodec != null && codec == wantCodec) score += 2
        return score
    }

    fun isCommentary(name: String?): Boolean = name != null && COMMENTARY.containsMatchIn(name)

    fun isDescription(name: String?): Boolean = name != null && DESCRIPTION.containsMatchIn(name)

    /** "5.1" is 6 channels, "stereo" 2, "mono" 1; null when the name says nothing. */
    fun channelCount(name: String?): Int? {
        if (name.isNullOrBlank()) return null
        CHANNEL_LAYOUT.find(name)?.let { match ->
            return match.groupValues[1].toInt() + match.groupValues[2].toInt()
        }
        val lower = name.lowercase(Locale.ROOT)
        return when {
            "stereo" in lower -> 2
            "mono" in lower -> 1
            else -> null
        }
    }

    fun codecFamily(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase(Locale.ROOT)
        return CODEC_TOKENS.firstOrNull { (token, _) -> token in lower }?.second
    }
}
