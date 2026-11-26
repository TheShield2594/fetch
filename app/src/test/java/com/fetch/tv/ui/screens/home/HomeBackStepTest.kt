package com.fetch.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBackStepTest {

    @Test
    fun `a card past the first goes to the row start`() {
        assertEquals(HomeBackStep.ROW_START, homeBackStep("catalog_b", 3, firstRowKey = "continue_watching"))
        assertEquals(HomeBackStep.ROW_START, homeBackStep("continue_watching", 3, firstRowKey = "continue_watching"))
    }

    @Test
    fun `the first card of a lower row goes to the top row`() {
        assertEquals(HomeBackStep.TOP_ROW, homeBackStep("catalog_t", 0, firstRowKey = "continue_watching"))
    }

    @Test
    fun `the first card of the first row opens the sidebar`() {
        assertEquals(HomeBackStep.OPEN_SIDEBAR, homeBackStep("continue_watching", 0, firstRowKey = "continue_watching"))
    }

    @Test
    fun `nothing focused opens the sidebar`() {
        assertEquals(HomeBackStep.OPEN_SIDEBAR, homeBackStep(null, 0, firstRowKey = "continue_watching"))
    }
}
