package com.fetch.tv.ui.screens.home

/** What Back does on Home while a row card has focus (#817). */
internal enum class HomeBackStep {
    /** Scroll the focused row back to its first card. */
    ROW_START,

    /** Scroll Home to the top and focus the first row's remembered card. */
    TOP_ROW,

    /** Leave it to the root policy, which opens the sidebar. */
    OPEN_SIDEBAR
}

/**
 * Back on Home steps up one level at a time, so it never leaves Home (or opens the sidebar) while
 * the page is still scrolled down: a card past the first goes to the row's start, the first card
 * of a lower row goes to the top row, and only the top row opens the sidebar.
 */
internal fun homeBackStep(
    focusedRowKey: String?,
    focusedItemIndex: Int,
    firstRowKey: String?
): HomeBackStep = when {
    focusedRowKey == null -> HomeBackStep.OPEN_SIDEBAR
    focusedItemIndex > 0 -> HomeBackStep.ROW_START
    firstRowKey != null && focusedRowKey != firstRowKey -> HomeBackStep.TOP_ROW
    else -> HomeBackStep.OPEN_SIDEBAR
}
