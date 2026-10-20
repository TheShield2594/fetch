package com.fetch.tv.ui.screens.home

object HeroBackdropState {
    @Volatile
    var currentHeroBackdropUrl: String? = null
        private set

    /** Last backdrop URL that was actually displayed — survives navigation for seamless back transitions. */
    @Volatile
    var lastDisplayedUrl: String? = null

    fun update(url: String?) {
        currentHeroBackdropUrl = url
        if (!url.isNullOrBlank()) {
            lastDisplayedUrl = url
        }
    }

    fun consumeAndClear(): String? {
        val url = currentHeroBackdropUrl
        currentHeroBackdropUrl = null
        return url
    }
}

/** One-shot handoff: season the details screen should open on (set by CW "Go to details"). */
object DetailInitialSeasonState {
    @Volatile
    private var season: Int? = null

    fun set(value: Int?) {
        season = value
    }

    fun consumeAndClear(): Int? {
        val value = season
        season = null
        return value
    }
}
