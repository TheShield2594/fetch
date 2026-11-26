package com.fetch.tv.ui.screens.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fetch.tv.ui.theme.FetchTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * TheShield2594/fetch#816: when the held card was Home's last row, Home falls back to its
 * skeleton. The standalone banner is drawn after it, as HomeScreen does, and Undo must stay
 * visible, focused and usable.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class StandaloneUndoBannerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val pending = PendingContinueWatchingRemoval(
        contentId = "tt1",
        season = 1,
        episode = 2,
        isNextUp = false,
        title = "Show",
        token = 1L,
        profileId = 1,
        shownItems = emptyList()
    )

    @Test
    fun `Undo stays visible and usable over the Home skeleton`() {
        // The same layer HomeScreen draws: the skeleton is up because the held card was Home's
        // last row, and the hold is cleared by Undo as the view model's undo does.
        var held by mutableStateOf<PendingContinueWatchingRemoval?>(pending)
        compose.setContent {
            FetchTheme {
                Box(Modifier.fillMaxSize()) {
                    HomeLoaderAndUndoLayer(
                        uiState = HomeUiState(),
                        showLoader = true,
                        showStandaloneUndo = showsStandaloneUndoBanner(presentationReady = false, pending = held),
                        pending = held,
                        onUndo = { held = null }
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Removed Show").assertIsDisplayed()
        val undo = compose.onNodeWithText("Undo")
        undo.assertIsDisplayed()
        undo.assertIsFocused()
        undo.performClick()
        compose.waitForIdle()

        assertNull(held)
        compose.onNodeWithText("Removed Show").assertDoesNotExist()
    }
}
