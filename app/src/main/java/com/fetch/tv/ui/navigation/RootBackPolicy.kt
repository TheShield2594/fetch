package com.fetch.tv.ui.navigation

/** What Back does on a page that shows the sidebar. */
internal enum class RootBackAction {
    /** Open the sidebar and focus the current page's item. */
    OPEN_SIDEBAR,

    /** Close the sidebar and hand focus back to the page. */
    CLOSE_SIDEBAR,

    /** Go back to the screen that opened this page. */
    POP,

    /** Go to Home. */
    GO_HOME,

    /** Leave the app. */
    EXIT
}

/**
 * Back on a sidebar page. The app is only ever left from Home with the sidebar open, so Back on
 * Search, Library or Settings can't close it by accident:
 *
 * - Home: Back opens the sidebar, and Back on the open sidebar leaves the app.
 * - Any other sidebar page: Back closes an open sidebar. With the sidebar closed it returns to
 *   the screen that opened the page when that wasn't Home (Calendar's "Search titles" button,
 *   say), and otherwise goes to Home.
 */
internal fun rootBackAction(
    currentRoute: String?,
    previousRoute: String?,
    sidebarOpen: Boolean,
    homeRoute: String = Screen.Home.route
): RootBackAction {
    val onHome = currentRoute == homeRoute
    return when {
        sidebarOpen && onHome -> RootBackAction.EXIT
        sidebarOpen -> RootBackAction.CLOSE_SIDEBAR
        onHome -> RootBackAction.OPEN_SIDEBAR
        previousRoute != null && previousRoute != homeRoute -> RootBackAction.POP
        else -> RootBackAction.GO_HOME
    }
}
