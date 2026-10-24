package com.fetch.tv.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerState
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ModalNavigationDrawer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Opening the sidebar on a page other than Home must not pass focus through Home first. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class SidebarFocusEntryTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val items = listOf("Home", "Search", "Library", "Settings")

    private fun focusedWhenOpened(currentPage: String?): List<String> {
        val focused = mutableListOf<String>()
        val drawerState = DrawerState(DrawerValue.Closed)
        val requesters = items.associateWith { FocusRequester() }
        compose.setContent {
            val content = remember { FocusRequester() }
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { value ->
                    Column(Modifier.sidebarFocusEntry(currentPage?.let(requesters::getValue))) {
                        items.forEach { item ->
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .focusRequester(requesters.getValue(item))
                                    // As in FloatingSidebarButton: only focusable while open.
                                    .focusProperties { canFocus = value == DrawerValue.Open }
                                    .onFocusChanged { if (it.isFocused) focused += item }
                                    .focusable()
                            )
                        }
                    }
                }
            ) {
                Box(Modifier.size(40.dp).focusRequester(content).focusable())
                LaunchedEffect(Unit) { content.requestFocus() }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { drawerState.setValue(DrawerValue.Open) }
        compose.waitForIdle()
        return focused
    }

    @Test
    fun `opening the sidebar focuses the current page's item directly`() {
        assertEquals(listOf("Settings"), focusedWhenOpened("Settings"))
    }

    @Test
    fun `with no current page the first item takes focus as before`() {
        assertEquals(listOf("Home"), focusedWhenOpened(null))
    }
}
