package com.fetch.tv.ui.theme

import androidx.compose.ui.graphics.Color
import com.fetch.tv.domain.model.PROFILE_AVATAR_COLORS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The initial on every avatar colour stays readable, and white is kept wherever it already is. */
class ProfileAvatarContrastTest {

    // Plain JVM test: android.graphics.Color is stubbed out, so parse the hex here.
    private fun parse(hex: String) = Color(0xFF000000 or hex.removePrefix("#").toLong(16))

    @Test
    fun `initial meets the large-text minimum on every avatar colour`() {
        PROFILE_AVATAR_COLORS.forEach { hex ->
            val fill = parse(hex)
            val ratio = contrastRatio(readableContentOn(fill, MIN_LARGE_TEXT_CONTRAST), fill)
            assertTrue("$hex is ${ratio}:1", ratio >= MIN_LARGE_TEXT_CONTRAST)
        }
    }

    @Test
    fun `white stays on dark fills and flips on amber and cyan`() {
        assertEquals(Color.White, readableContentOn(parse("#6D4C41"), MIN_LARGE_TEXT_CONTRAST))
        assertEquals(FetchPrimitives.neutral925, readableContentOn(parse("#FFB300"), MIN_LARGE_TEXT_CONTRAST))
        assertEquals(FetchPrimitives.neutral925, readableContentOn(parse("#00ACC1"), MIN_LARGE_TEXT_CONTRAST))
    }
}
