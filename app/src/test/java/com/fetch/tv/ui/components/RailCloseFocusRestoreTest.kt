package com.fetch.tv.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Closing the rail with Right requests focus on the page area, as FetchSidebarScaffold does:
 * the page's last focused item gets it back, and a page that never had focus gets its first
 * item. Either way exactly one element ends up focused.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class RailCloseFocusRestoreTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val pageItems = listOf("first", "second", "third")
    private val focused = mutableSetOf<String>()
    private val rail = FocusRequester()
    private val page = FocusRequester()
    private val itemRequesters = pageItems.associateWith { FocusRequester() }

    private fun setUp() {
        compose.setContent {
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(rail)
                        .onFocusChanged { if (it.isFocused) focused += "rail" else focused -= "rail" }
                        .focusable()
                )
                Column(
                    Modifier
                        .focusRequester(page)
                        .focusRestorer()
                        .focusGroup()
                ) {
                    pageItems.forEach { item ->
                        Box(
                            Modifier
                                .size(40.dp)
                                .focusRequester(itemRequesters.getValue(item))
                                .onFocusChanged { if (it.isFocused) focused += item else focused -= item }
                                .focusable()
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `closing the rail returns focus to the page's last focused item`() {
        setUp()
        compose.runOnUiThread { itemRequesters.getValue("third").requestFocus() }
        compose.waitForIdle()
        compose.runOnUiThread { rail.requestFocus() }
        compose.waitForIdle()
        assertEquals(setOf("rail"), focused)

        compose.runOnUiThread { page.requestFocus() }
        compose.waitForIdle()

        assertEquals(setOf("third"), focused)
    }

    @Test
    fun `a page that never had focus gets its first item`() {
        setUp()
        compose.runOnUiThread { rail.requestFocus() }
        compose.waitForIdle()

        compose.runOnUiThread { page.requestFocus() }
        compose.waitForIdle()

        assertEquals(setOf("first"), focused)
    }
}
