package com.fetch.tv.domain.model

private val supporterThemes = linkedMapOf(
    AppTheme.GOLD to CosmeticEntitlement.GOLD_THEME,
    AppTheme.JADE to CosmeticEntitlement.JADE_THEME,
    AppTheme.ROSE_GOLD to CosmeticEntitlement.ROSE_GOLD_THEME,
    AppTheme.ARCTIC_BLUE to CosmeticEntitlement.ARCTIC_BLUE_THEME,
    AppTheme.GRAPHITE to CosmeticEntitlement.GRAPHITE_THEME
)

/** The theme a profile gets until it picks one. */
val DEFAULT_APP_THEME = AppTheme.FETCH

private val standardThemes = listOf(DEFAULT_APP_THEME, AppTheme.WHITE) + AppTheme.entries.filterNot {
    it == DEFAULT_APP_THEME || it == AppTheme.WHITE || it == AppTheme.CUSTOM || it in supporterThemes
}

fun availableAppThemes(entitlements: CosmeticEntitlements): List<AppTheme> {
    val unlockedSupporterThemes = supporterThemes
        .filterValues(entitlements::includes)
        .keys
        .toList()
    return unlockedSupporterThemes + AppTheme.CUSTOM + standardThemes
}

fun resolveAppTheme(
    selectedTheme: AppTheme?,
    entitlements: CosmeticEntitlements
): AppTheme {
    if (selectedTheme == null) {
        return supporterThemes
            .filterValues(entitlements::includes)
            .keys
            .firstOrNull()
            ?: DEFAULT_APP_THEME
    }
    return if (selectedTheme in availableAppThemes(entitlements)) {
        selectedTheme
    } else {
        DEFAULT_APP_THEME
    }
}

@Suppress("UNUSED_PARAMETER")
fun resolveCustomThemeColors(colors: CustomThemeColors, memberTier: MemberTier?): CustomThemeColors = colors
