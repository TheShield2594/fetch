package com.fetch.tv.domain.model

/** The theme a profile gets until it picks one. */
val DEFAULT_APP_THEME = AppTheme.FETCH

/** Every theme, in the order the picker lists them: Custom, Fetch, White, then the accents. */
fun availableAppThemes(): List<AppTheme> =
    listOf(AppTheme.CUSTOM, DEFAULT_APP_THEME, AppTheme.WHITE) +
        AppTheme.entries.filterNot { it == AppTheme.CUSTOM || it == DEFAULT_APP_THEME || it == AppTheme.WHITE }

/** The theme to draw: the profile's pick, or [DEFAULT_APP_THEME] when it hasn't picked one. */
fun resolveAppTheme(selectedTheme: AppTheme?): AppTheme = selectedTheme ?: DEFAULT_APP_THEME
