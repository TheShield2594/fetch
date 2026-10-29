package com.fetch.tv.domain.model

/** What Ambient Mode fills the screen with. */
enum class AmbientFace {
    /** A slideshow of backdrops from the member's catalogs. */
    ARTWORK,

    /** A dot-matrix clock with the date and what to resume. */
    CLOCK
}

data class AmbientModeSettings(
    val enabled: Boolean = true,
    val timeoutMinutes: Int = DEFAULT_TIMEOUT_MINUTES,
    val catalogKeys: List<String> = emptyList(),
    /** Null until the member picks one: the theme decides (Signal shows the clock). */
    val face: AmbientFace? = null
) {
    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
        const val MAX_CATALOGS = 3
        val TIMEOUT_OPTIONS_MINUTES = listOf(1, 2, 5, 10, 15, 30)
    }
}
