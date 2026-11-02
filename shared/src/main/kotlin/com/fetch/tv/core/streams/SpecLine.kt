package com.fetch.tv.core.streams

/**
 * The spec line Fetch shows wherever it names a stream: "38m left · 4K · DV P8 · TrueHD Atmos ·
 * 18.2 GB · Cached". The order is always the same: optional [leading] text (such as the time
 * left), the stream's labels most important first, then the cached state last.
 */
data class SpecLine(
    val leading: String?,
    val labels: List<String>,
    /** The cached label, or null when the stream isn't known to be cached. */
    val cached: String?
) {
    val parts: List<String> get() = listOfNotNull(leading) + labels + listOfNotNull(cached)

    val text: String get() = parts.joinToString(SEPARATOR)

    companion object {
        const val SEPARATOR = " · "

        /**
         * Builds a spec line from what is known about a stream, or returns null when nothing is.
         * Blank parts are dropped, and the cached label is used only when [isCached] is true, so
         * the line never claims more than the addon said.
         */
        fun of(
            labels: List<String>,
            isCached: Boolean,
            cachedLabel: String,
            leading: String? = null
        ): SpecLine? {
            val line = SpecLine(
                leading = leading?.takeIf { it.isNotBlank() },
                labels = labels.filter { it.isNotBlank() },
                cached = cachedLabel.takeIf { isCached && it.isNotBlank() }
            )
            return line.takeIf { it.parts.isNotEmpty() }
        }
    }
}
