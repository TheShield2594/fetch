package com.fetch.desktop.data

import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.CustomThemeColors
import kotlinx.serialization.Serializable

/**
 * The TV app's `theme_settings` beyond the theme itself (Settings › Appearance there): Increase
 * contrast, AMOLED Mode and Pure Black Surfaces, the Custom theme's colours and the app font.
 * Synced with the TV under its keys.
 */
@Serializable
data class AppearanceSettings(
    /** `high_contrast`: brighter secondary text and borders. */
    val highContrast: Boolean = false,
    /** `amoled_mode`: pure black backgrounds. */
    val amoledMode: Boolean = false,
    /** `amoled_surfaces_mode`: cards and panels pure black too; only with [amoledMode]. */
    val amoledSurfaces: Boolean = false,
    /** `custom_theme_colors`: three `#RRGGBB` colours, comma-separated, as the TV stores them. */
    val customThemeColors: String = CustomThemeColors.solid(CustomThemeColors.Default.second).encode(),
    /** `selected_font`: an [AppFont] name. */
    val font: String = AppFont.SCHIBSTED_GROTESK.name
) {
    val customColors: CustomThemeColors get() = CustomThemeColors.decode(customThemeColors)

    val appFont: AppFont get() = AppFont.entries.firstOrNull { it.name == font } ?: AppFont.SCHIBSTED_GROTESK

    /** Pure black surfaces only count while AMOLED Mode is on, as on TV. */
    val pureBlackSurfaces: Boolean get() = amoledMode && amoledSurfaces
}
