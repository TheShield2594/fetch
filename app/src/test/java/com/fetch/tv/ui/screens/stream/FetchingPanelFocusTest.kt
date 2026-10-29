package com.fetch.tv.ui.screens.stream

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.fetch.tv.R
import com.fetch.tv.core.streams.BestMatchPreview
import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus
import com.fetch.tv.ui.theme.FetchTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The fetching panel moves focus to Play now when the first result lands, unless the member has
 * already pressed a key in the panel: an OK meant for See all sources must not start playback.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class FetchingPanelFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val best = BestMatchPreview(
        videoId = "tt1:1:1",
        labels = listOf("4K", "DV P8", "TrueHD Atmos", "18.2 GB"),
        specLabels = listOf("4K", "DV P8", "TrueHD Atmos"),
        buttonLabels = listOf("4K", "Dolby Vision", "Atmos"),
        isCached = true,
        addonName = "Torrentio",
        sourceCount = 3,
        isFinal = false
    )

    private val bestSoFar = mutableStateOf<BestMatchPreview?>(null)

    private fun showPanel() {
        compose.setContent {
            FetchTheme {
                FetchingPanel(
                    sourceChips = listOf(
                        SourceChipItem("Torrentio", SourceChipStatus.LOADING),
                        SourceChipItem("Comet", SourceChipStatus.LOADING)
                    ),
                    bestSoFar = bestSoFar.value,
                    deadlineElapsedMs = null,
                    onPlayNow = {},
                    onShowAllSources = {}
                )
            }
        }
        compose.waitForIdle()
    }

    private val seeAll get() = compose.onNodeWithText(compose.activity.getString(R.string.fetching_see_all_sources))
    private val playNow get() = compose.onNodeWithText(compose.activity.getString(R.string.fetching_play_now))

    @Test
    fun `a key pressed before the first result keeps focus on See all sources`() {
        showPanel()
        seeAll.assertIsFocused()

        // A key that leaves focus where it is: the guard is about the press, not a move.
        seeAll.performKeyInput { pressKey(Key.MediaPlayPause) }
        compose.waitForIdle()
        seeAll.assertIsFocused()

        compose.runOnUiThread { bestSoFar.value = best }
        compose.waitForIdle()

        seeAll.assertIsFocused()
        playNow.assertIsNotFocused()
    }

    @Test
    fun `with no key pressed the first result takes focus to Play now`() {
        showPanel()
        seeAll.assertIsFocused()

        compose.runOnUiThread { bestSoFar.value = best }
        compose.waitForIdle()

        playNow.assertIsFocused()
    }
}
