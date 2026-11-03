package com.fetch.desktop.poster

import com.fetch.desktop.data.LayoutPrefs
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape

/** Where Layout › Custom Poster Source applies, by the TV's stored keys. */
enum class CustomPosterScreen(val key: String, val label: String) {
    HOME("home", "Home"),
    CONTINUE_WATCHING("continue_watching", "Continue Watching"),
    COLLECTIONS("collections", "Collections"),
    LIBRARY("library", "Library"),
    SEARCH("search", "Search"),
    DETAILS("details", "Details screen"),
    /** The TV's launcher rows; kept so a synced choice survives, not shown here. */
    TV_RECOMMENDATIONS("tv_recommendations", "TV recommendations");

    companion object {
        /** The screens the desktop has. */
        val shown: List<CustomPosterScreen> = entries - TV_RECOMMENDATIONS
    }
}

/** The pattern to use on [screen], or "" when it's off there. */
fun LayoutPrefs.posterPatternFor(screen: CustomPosterScreen): String {
    val pattern = customPosterUrlPattern.trim()
    if (pattern.isEmpty()) return ""
    val screens = customPosterScreens ?: return pattern
    return if (screen.key in screens) pattern else ""
}

/**
 * [this] with its poster from [pattern], as the TV's `withCustomPosterUrl`: the original stays in
 * [MetaPreview.rawPosterUrl]. Without `{shape}` only portrait posters change.
 */
fun MetaPreview.withCustomPosterUrl(pattern: String): MetaPreview {
    if (pattern.isBlank()) return this
    val ids = CustomPosterUrlResolver.extractIds(id, explicitImdbId = imdbId)
    val supportsShape = "{shape}" in pattern
    if (!supportsShape && posterShape != PosterShape.POSTER) return this
    val shape = when (posterShape) {
        PosterShape.POSTER -> "poster"
        PosterShape.LANDSCAPE -> "landscape"
        PosterShape.SQUARE -> "square"
    }
    val poster = CustomPosterUrlResolver.resolve(pattern, ids, apiType, if (supportsShape) shape else "poster")
    val landscape = if (supportsShape) CustomPosterUrlResolver.resolve(pattern, ids, apiType, "landscape") else null
    if (poster == null && landscape == null) return this
    return copy(
        poster = poster ?: this.poster,
        rawPosterUrl = rawPosterUrl ?: this.poster,
        landscapePoster = landscape ?: landscapePoster
    )
}

fun List<MetaPreview>.withCustomPosterUrls(pattern: String): List<MetaPreview> =
    if (pattern.isBlank()) this else map { it.withCustomPosterUrl(pattern) }

/** A poster for a Continue Watching card from [pattern], or null to keep its own art. */
fun customPosterFor(pattern: String, contentId: String, type: String): String? {
    if (pattern.isBlank()) return null
    return CustomPosterUrlResolver.resolve(pattern, CustomPosterUrlResolver.extractIds(contentId), type, "poster")
}
