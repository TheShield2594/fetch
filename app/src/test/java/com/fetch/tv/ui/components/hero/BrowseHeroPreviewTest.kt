package com.fetch.tv.ui.components.hero

import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The browse hero band's preview, from a card and the cached meta its focus prefetch brings (#852). */
class BrowseHeroPreviewTest {

    private fun card(background: String? = null, logo: String? = null, releaseInfo: String? = "2019–2023", runtime: String? = null) =
        MetaPreview(
            id = "tt1", type = ContentType.SERIES, rawType = "series", name = "Card title", poster = "poster",
            posterShape = PosterShape.POSTER, background = background, logo = logo, description = "Card synopsis",
            releaseInfo = releaseInfo, imdbRating = 7.9f, genres = listOf("Drama", "Crime", "Mystery"), runtime = runtime
        )

    private fun cached(background: String? = "cached-bg", logo: String? = "cached-logo", runtime: String? = "58 min") =
        mockk<Meta>(relaxed = true) {
            every { this@mockk.background } returns background
            every { this@mockk.logo } returns logo
            every { this@mockk.runtime } returns runtime
            every { releaseInfo } returns ""
            every { imdbRating } returns null
            every { ageRating } returns ""
            every { description } returns "Cached synopsis"
            every { genres } returns emptyList()
        }

    @Test
    fun `the card shows at once and the cache fills in its art and logo`() {
        val before = browseHeroPreview(card(), cached = null, typeLabel = "Series")
        assertNull(before.backdrop)
        assertEquals("poster", before.imageUrl)
        // Runtime formatting needs Android's ICU, so these JVM tests leave the runtime out.
        val after = browseHeroPreview(card(), cached(runtime = null), typeLabel = "Series")
        assertEquals("cached-bg", after.backdrop)
        assertEquals("cached-logo", after.logo)
    }

    @Test
    fun `blank cached values never hide the card's own`() {
        val preview = browseHeroPreview(card(background = "card-bg"), cached(background = "", logo = "", runtime = null), "Series")
        assertEquals("card-bg", preview.backdrop)
        assertNull(preview.logo)
        assertEquals("2019", preview.yearText)
        assertEquals("7.9", preview.imdbText?.replace(',', '.'))
        assertNull(preview.ageRatingText)
        assertEquals("Card synopsis", preview.description)
        assertEquals(listOf("Drama", "Crime"), preview.genres.toList())
        assertEquals(true, preview.isSeries)
    }

    @Test
    fun `the year comes from a release line, and a detail line without one gives none`() {
        assertEquals("2021", browseHeroYear("2021"))
        assertEquals("2019", browseHeroYear("2019–2023"))
        assertNull(browseHeroYear("S1 E2 · 40%"))
        assertNull(browseHeroYear(null))
    }
}
