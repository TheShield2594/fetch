package com.fetch.tv.ui.screens.settings

import com.fetch.tv.MainDispatcherRule
import com.fetch.tv.data.local.ThemeDataStore
import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.CustomThemeColors
import com.fetch.tv.domain.model.ThemeSelection
import com.fetch.tv.launcher.AppIconManager
import com.fetch.tv.launcher.AppIconSettingsState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThemeSettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val gradient = CustomThemeColors(0x112233, 0x445566, 0x778899)
    private val solid = CustomThemeColors.solid(0x445566)
    private val selection = MutableStateFlow(ThemeSelection(AppTheme.CUSTOM, gradient))
    private val store = mockk<ThemeDataStore> {
        every { themeSelection } returns selection
        every { selectedFont } returns flowOf(AppFont.INTER)
        every { amoledMode } returns flowOf(false)
        every { amoledSurfacesMode } returns flowOf(false)
        every { highContrast } returns flowOf(false)
        every { reduceMotion } returns flowOf(false)
        coEvery { setCustomTheme(any()) } coAnswers {
            selection.value = ThemeSelection(AppTheme.CUSTOM, firstArg())
        }
    }

    @Test
    fun savingACustomThemePersistsTheColorsUnchanged() = runTest {
        val viewModel = createViewModel()
        runCurrent()

        assertEquals(gradient, viewModel.uiState.value.customThemeColors)
        viewModel.onEvent(ThemeSettingsEvent.SaveCustomTheme(gradient))
        runCurrent()

        coVerify(exactly = 1) { store.setCustomTheme(gradient) }
        assertEquals(gradient, viewModel.uiState.value.customThemeColors)
    }

    @Test
    fun everyThemeIsAvailableAndTheEditorStaysSolid() = runTest {
        selection.value = ThemeSelection(AppTheme.CUSTOM, solid)
        val viewModel = createViewModel()
        runCurrent()

        assertTrue(viewModel.uiState.value.themesLoaded)
        assertEquals(AppTheme.CUSTOM, viewModel.uiState.value.selectedTheme)
        assertEquals(AppTheme.entries.toSet(), viewModel.uiState.value.availableThemes.toSet())
        assertFalse(viewModel.uiState.value.customThemeGradientEnabled)
        assertEquals(solid, viewModel.uiState.value.customThemeColors)
    }

    @Test
    fun unsetThemeResolvesToGold() = runTest {
        selection.value = ThemeSelection(null, solid)
        val viewModel = createViewModel()
        runCurrent()

        assertEquals(AppTheme.GOLD, viewModel.uiState.value.selectedTheme)
    }

    private fun createViewModel(): ThemeSettingsViewModel {
        val icons = mockk<AppIconManager> { every { state } returns MutableStateFlow(AppIconSettingsState()) }
        return ThemeSettingsViewModel(store, icons)
    }
}
