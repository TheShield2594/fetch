package com.fetch.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PosterTitleFontSizeTest {

    @Test
    fun `a 14sp title stops at the 12sp floor instead of scaling to 11_9sp`() {
        assertEquals(12f, posterTitleFontSizeSp(14f), 0.0001f)
    }

    @Test
    fun `a larger title keeps its scaled size`() {
        assertEquals(17f, posterTitleFontSizeSp(20f), 0.0001f)
    }
}
