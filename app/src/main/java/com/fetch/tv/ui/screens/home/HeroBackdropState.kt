package com.fetch.tv.ui.screens.home

/**
 * Two things kept apart (#847): the backdrop handed to the Detail page a screen is about to open
 * ([update], [consumeAndClear]), and the backdrop Home's hero last showed ([showOnHome],
 * [lastDisplayedUrl]), which Home starts from on its return. Other screens only hand off, so a
 * title opened from Discover or the Library never shows up first in Home's hero.
 */
object HeroBackdropState {
    @Volatile
    var currentHeroBackdropUrl: String? = null
        private set

    /** Last backdrop Home's hero displayed — survives navigation for a seamless return to Home. */
    @Volatile
    var lastDisplayedUrl: String? = null
        private set

    /** The backdrop for the Detail page about to open. */
    fun update(url: String?) {
        currentHeroBackdropUrl = url
    }

    /** Home's hero is showing [url]: hand it to Detail, and start from it when Home comes back. */
    fun showOnHome(url: String?) {
        update(url)
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
