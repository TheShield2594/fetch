package com.fetch.tv.domain.model

/** Featured themes, listed first in the picker. GOLD is the theme a profile gets until it picks one. */
private val featuredThemes = listOf(
    AppTheme.GOLD,
    AppTheme.JADE,
    AppTheme.ROSE_GOLD,
    AppTheme.ARCTIC_BLUE,
    AppTheme.GRAPHITE
)

/** The fallback theme for anything that cannot be resolved. */
val DEFAULT_APP_THEME = AppTheme.FETCH

private val standardThemes = listOf(DEFAULT_APP_THEME, AppTheme.WHITE) + AppTheme.entries.filterNot {
    it == DEFAULT_APP_THEME || it == AppTheme.WHITE || it == AppTheme.CUSTOM || it in featuredThemes
}

private val appThemes: List<AppTheme> = featuredThemes + AppTheme.CUSTOM + standardThemes

fun availableAppThemes(): List<AppTheme> = appThemes

fun resolveAppTheme(selectedTheme: AppTheme?): AppTheme {
    if (selectedTheme == null) return featuredThemes.first()
    return if (selectedTheme in appThemes) selectedTheme else DEFAULT_APP_THEME
}
