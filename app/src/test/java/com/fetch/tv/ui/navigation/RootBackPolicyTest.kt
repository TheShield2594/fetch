package com.fetch.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RootBackPolicyTest {

    private val home = Screen.Home.route
    private val search = Screen.Search.route
    private val settings = Screen.Settings.route
    private val calendar = Screen.Calendar.route

    @Test
    fun `Back on Home opens the sidebar`() {
        assertEquals(RootBackAction.OPEN_SIDEBAR, rootBackAction(home, previousRoute = null, sidebarOpen = false))
    }

    @Test
    fun `Back on Home with the sidebar open leaves the app`() {
        assertEquals(RootBackAction.EXIT, rootBackAction(home, previousRoute = null, sidebarOpen = true))
    }

    @Test
    fun `Back on another sidebar page goes to Home`() {
        assertEquals(RootBackAction.GO_HOME, rootBackAction(settings, previousRoute = home, sidebarOpen = false))
        assertEquals(RootBackAction.GO_HOME, rootBackAction(search, previousRoute = null, sidebarOpen = false))
    }

    @Test
    fun `Back on another sidebar page never leaves the app`() {
        assertEquals(RootBackAction.CLOSE_SIDEBAR, rootBackAction(settings, previousRoute = home, sidebarOpen = true))
        assertEquals(RootBackAction.CLOSE_SIDEBAR, rootBackAction(search, previousRoute = calendar, sidebarOpen = true))
    }

    @Test
    fun `Back on a sidebar page opened from another screen returns to that screen`() {
        assertEquals(RootBackAction.POP, rootBackAction(search, previousRoute = calendar, sidebarOpen = false))
    }

    @Test
    fun `Back on a sidebar page opened from another sidebar page returns to that page`() {
        // Library's "Search titles" and "Open settings" buttons push a sidebar page on top of Library.
        val library = Screen.Library.route
        assertEquals(RootBackAction.POP, rootBackAction(search, previousRoute = library, sidebarOpen = false))
        assertEquals(RootBackAction.POP, rootBackAction(settings, previousRoute = library, sidebarOpen = false))
    }
}
