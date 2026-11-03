package com.fetch.desktop.playback

/**
 * The TV app's `FilenameEpisodeMatcher` (it lives in `:app`, so it's copied here): turns the
 * file name of the episode playing into the next episode's ("Show.S01E03.1080p" →
 * "Show.S01E04.1080p"), so the next episode can play the same release.
 *
 * Patterns, case-insensitive: S01E03, S1E3, "S 01 E 03"; 1x03; E03, EP03, "Ep 03", EP.03.
 */
object FilenameEpisodeMatcher {

    fun buildNextFilename(filename: String, currentSeason: Int?, currentEpisode: Int, nextSeason: Int?, nextEpisode: Int): String? {
        if (filename.isBlank()) return null
        // Most specific first; the first match wins so unrelated numbers stay.
        return sXeY(filename, currentSeason, currentEpisode, nextSeason, nextEpisode)
            ?: seasonXepisode(filename, currentSeason, currentEpisode, nextSeason, nextEpisode)
            ?: episodeOnly(filename, currentEpisode, nextEpisode)
    }

    fun matches(candidate: String?, expected: String?): Boolean =
        !candidate.isNullOrBlank() && !expected.isNullOrBlank() && candidate.equals(expected, ignoreCase = true)

    fun extractSeasonEpisode(filename: String): Pair<Int?, Int>? {
        if (filename.isBlank()) return null
        SXEY.find(filename)?.let { m -> return m.groupValues[2].toIntOrNull() to (m.groupValues[4].toIntOrNull() ?: return null) }
        SEASON_X_EPISODE.find(filename)?.let { m -> return m.groupValues[1].toIntOrNull() to (m.groupValues[2].toIntOrNull() ?: return null) }
        EPISODE_ONLY.find(filename)?.let { m -> return null to (m.groupValues[2].toIntOrNull() ?: return null) }
        return null
    }

    private val SXEY = Regex("""(?i)(S)\s*(\d{1,3})\s*(E)\s*(\d{1,4})""")
    private val SEASON_X_EPISODE = Regex("""(?<!\d)(\d{1,2})[xX](\d{1,4})""")
    private val EPISODE_ONLY = Regex("""(?i)\b(E[Pp]?)[.\s]?(\d{1,4})\b""")

    private fun sXeY(filename: String, currentSeason: Int?, currentEpisode: Int, nextSeason: Int?, nextEpisode: Int): String? {
        if (currentSeason == null) return null
        val m = SXEY.find(filename) ?: return null
        if (m.groupValues[2].toIntOrNull() != currentSeason || m.groupValues[4].toIntOrNull() != currentEpisode) return null
        // Keep the zero padding and the S/E casing.
        val season = (nextSeason ?: currentSeason).toString().padStart(m.groupValues[2].length, '0')
        val episode = nextEpisode.toString().padStart(m.groupValues[4].length, '0')
        return filename.replaceRange(m.range, "${m.groupValues[1]}$season${m.groupValues[3]}$episode")
    }

    private fun seasonXepisode(filename: String, currentSeason: Int?, currentEpisode: Int, nextSeason: Int?, nextEpisode: Int): String? {
        if (currentSeason == null) return null
        val m = SEASON_X_EPISODE.find(filename) ?: return null
        if (m.groupValues[1].toIntOrNull() != currentSeason || m.groupValues[2].toIntOrNull() != currentEpisode) return null
        val separator = filename[m.range.first + m.groupValues[1].length]
        val replacement = (nextSeason ?: currentSeason).toString().padStart(m.groupValues[1].length, '0') +
            separator + nextEpisode.toString().padStart(m.groupValues[2].length, '0')
        return filename.replaceRange(m.range, replacement)
    }

    private fun episodeOnly(filename: String, currentEpisode: Int, nextEpisode: Int): String? {
        val m = EPISODE_ONLY.find(filename) ?: return null
        if (m.groupValues[2].toIntOrNull() != currentEpisode) return null
        val prefix = m.groupValues[1]
        val separator = m.value.substring(prefix.length, m.value.length - m.groupValues[2].length)
        return filename.replaceRange(m.range, prefix + separator + nextEpisode.toString().padStart(m.groupValues[2].length, '0'))
    }
}
