package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Test

class NeighbourHeroBackdropsTest {

    private fun card(id: String, backdrop: String? = "https://art.test/$id-backdrop.jpg", poster: String? = null) =
        ModernCarouselItem(
            key = id,
            title = id,
            subtitle = null,
            imageUrl = null,
            heroPreview = HeroPreview(
                title = id,
                logo = null,
                description = null,
                contentTypeText = null,
                yearText = null,
                imdbText = null,
                genres = StableList(emptyList()),
                poster = poster,
                backdrop = backdrop,
                imageUrl = null
            ),
            payload = ModernPayload.Catalog(
                focusKey = id,
                itemId = id,
                itemType = "movie",
                addonBaseUrl = "https://example.test/manifest.json",
                trailerTitle = id,
                trailerReleaseInfo = null,
                trailerApiType = "movie"
            ),
            metaPreview = MetaPreview(
                id = id,
                type = ContentType.MOVIE,
                name = id,
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            )
        )

    @Test
    fun `the cards either side are loaded, not the focused one`() {
        val items = listOf(card("a"), card("b"), card("c"))

        assertEquals(
            listOf("https://art.test/a-backdrop.jpg", "https://art.test/c-backdrop.jpg"),
            neighbourHeroBackdrops(items, index = 1, enrichedPreviews = emptyMap())
        )
    }

    @Test
    fun `the row's ends have one neighbour`() {
        val items = listOf(card("a"), card("b"))

        assertEquals(listOf("https://art.test/b-backdrop.jpg"), neighbourHeroBackdrops(items, 0, emptyMap()))
        assertEquals(listOf("https://art.test/a-backdrop.jpg"), neighbourHeroBackdrops(items, 1, emptyMap()))
    }

    @Test
    fun `enriched art wins, as it does on the hero`() {
        val items = listOf(card("a"), card("b"))
        val enriched = items[1].metaPreview!!.copy(background = "https://tmdb.test/b.jpg")

        assertEquals(
            listOf("https://tmdb.test/b.jpg"),
            neighbourHeroBackdrops(items, 0, mapOf("b" to enriched))
        )
    }

    @Test
    fun `a card without a backdrop falls back to its poster`() {
        val items = listOf(card("a"), card("b", backdrop = null, poster = "https://art.test/b-poster.jpg"))

        assertEquals(listOf("https://art.test/b-poster.jpg"), neighbourHeroBackdrops(items, 0, emptyMap()))
    }

    @Test
    fun `the low-RAM tier preloads only the next card`() {
        val items = listOf(card("a"), card("b"), card("c"))

        assertEquals(
            listOf("https://art.test/c-backdrop.jpg"),
            neighbourHeroBackdrops(items, 1, emptyMap(), singleNeighbour = true)
        )
    }

    @Test
    fun `the low-RAM tier falls back to the previous card at the row's end`() {
        val items = listOf(card("a"), card("b"))

        assertEquals(
            listOf("https://art.test/a-backdrop.jpg"),
            neighbourHeroBackdrops(items, 1, emptyMap(), singleNeighbour = true)
        )
    }

    @Test
    fun `the low-RAM tier falls back to the previous card when the next has no art`() {
        val items = listOf(card("a"), card("b"), card("c", backdrop = null))

        assertEquals(
            listOf("https://art.test/a-backdrop.jpg"),
            neighbourHeroBackdrops(items, 1, emptyMap(), singleNeighbour = true)
        )
    }
}
