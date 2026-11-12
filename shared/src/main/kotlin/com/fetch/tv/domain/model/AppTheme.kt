package com.fetch.tv.domain.model

/**
 * The built-in colour themes, plus Custom. Kept small on purpose (#455): Fetch, one neutral
 * monochrome theme (White) and three accents that sit far apart on the colour wheel, each held to
 * WCAG AA by ThemeContrastTest. Themes that used to exist are mapped onto these by [fromStoredName].
 */
enum class AppTheme(val displayName: String) {
    FETCH("Fetch"),
    CUSTOM("Custom"),
    OCEAN("Ocean"),
    EMERALD("Emerald"),
    AMBER("Amber"),
    WHITE("White");

    companion object {
        /**
         * Themes removed in #455, each mapped to the kept theme nearest in colour, so a profile
         * (or a synced settings blob from an older device) that saved one keeps a similar look
         * instead of dropping to the default.
         */
        val retiredThemes: Map<String, AppTheme> = mapOf(
            "GOLD" to AMBER,
            "JADE" to EMERALD,
            "ROSE_GOLD" to FETCH,
            "ARCTIC_BLUE" to OCEAN,
            "GRAPHITE" to WHITE,
            "CRIMSON" to FETCH,
            "VIOLET" to OCEAN,
            "ROSE" to FETCH
        )

        /**
         * The theme a stored or synced name stands for: a current theme by its name, a retired one
         * by [retiredThemes], or null for anything else (the caller's default then applies).
         */
        fun fromStoredName(name: String?): AppTheme? {
            if (name == null) return null
            return entries.firstOrNull { it.name == name } ?: retiredThemes[name]
        }
    }
}
