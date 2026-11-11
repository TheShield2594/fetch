package com.fetch.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.CustomThemeColors
import com.fetch.tv.domain.model.ThemeSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "theme_settings"
    }

    private fun store(profileId: Int) =
        factory.get(profileId, FEATURE)

    private val themeKey = stringPreferencesKey("selected_theme")
    private val customThemeColorsKey = stringPreferencesKey("custom_theme_colors")
    private val fontKey = stringPreferencesKey("selected_font")
    private val amoledModeKey = booleanPreferencesKey("amoled_mode")
    private val amoledSurfacesModeKey = booleanPreferencesKey("amoled_surfaces_mode")
    private val highContrastKey = booleanPreferencesKey("high_contrast")
    private val reduceMotionKey = booleanPreferencesKey("reduce_motion")

    val themeSelection: Flow<ThemeSelection> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            ThemeSelection(
                theme = prefs[themeKey]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } },
                customColors = CustomThemeColors.decode(prefs[customThemeColorsKey])
            )
        }
    }

    val selectedFont: Flow<AppFont> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val fontName = prefs[fontKey] ?: AppFont.SCHIBSTED_GROTESK.name
            try {
                AppFont.valueOf(fontName)
            } catch (e: IllegalArgumentException) {
                AppFont.SCHIBSTED_GROTESK
            }
        }
    }

    val amoledMode: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[amoledModeKey] ?: false
        }
    }

    val amoledSurfacesMode: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[amoledSurfacesModeKey] ?: false
        }
    }

    /** Brighter secondary text, borders and a heavier focus ring, for anyone who finds the default too faint. */
    val highContrast: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[highContrastKey] ?: false
        }
    }

    /**
     * Holds decorative motion still (shimmers, slides, the hero carousel's auto-advance) for anyone
     * who finds it distracting. Turning the system's animations off does the same.
     */
    val reduceMotion: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[reduceMotionKey] ?: false
        }
    }

    suspend fun setTheme(theme: AppTheme) {
        store(profileManager.currentProfileId()).edit { prefs ->
            prefs[themeKey] = theme.name
        }
    }

    suspend fun setCustomTheme(colors: CustomThemeColors) {
        store(profileManager.currentProfileId()).edit { prefs ->
            prefs[customThemeColorsKey] = colors.encode()
            prefs[themeKey] = AppTheme.CUSTOM.name
        }
    }

    suspend fun setFont(font: AppFont) {
        store(profileManager.currentProfileId()).edit { prefs ->
            prefs[fontKey] = font.name
        }
    }

    suspend fun setAmoledMode(enabled: Boolean) {
        store(profileManager.currentProfileId()).edit { prefs ->
            prefs[amoledModeKey] = enabled
            if (!enabled) {
                prefs[amoledSurfacesModeKey] = false
            }
        }
    }

    suspend fun setAmoledSurfacesMode(enabled: Boolean) {
        store(profileManager.currentProfileId()).edit { prefs ->
            prefs[amoledSurfacesModeKey] = enabled
        }
    }

    suspend fun setHighContrast(enabled: Boolean) {
        store(profileManager.currentProfileId()).edit { prefs ->
            prefs[highContrastKey] = enabled
        }
    }

    suspend fun setReduceMotion(enabled: Boolean) {
        store(profileManager.currentProfileId()).edit { prefs ->
            prefs[reduceMotionKey] = enabled
        }
    }


    suspend fun getThemeForProfile(profileId: Int): AppTheme? {
        val prefs = factory.get(profileId, FEATURE).data.first()
        return prefs[themeKey]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
    }

    fun observeThemeForProfile(profileId: Int): Flow<AppTheme?> =
        factory.get(profileId, FEATURE).data.map { prefs ->
            prefs[themeKey]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
        }
}
