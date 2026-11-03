package com.fetch.desktop

import com.fetch.desktop.collections.renameLocalListSources
import com.fetch.desktop.data.LayoutPrefs
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.LibraryQuery
import com.fetch.desktop.data.LibrarySort
import com.fetch.desktop.data.LibraryView
import com.fetch.desktop.home.metaAddonOrder
import com.fetch.desktop.library.LibraryProviderOrder
import com.fetch.desktop.poster.CustomPosterScreen
import com.fetch.desktop.poster.customPosterFor
import com.fetch.desktop.poster.posterPatternFor
import com.fetch.desktop.poster.withCustomPosterUrl
import com.fetch.desktop.ui.CardStyle
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.LocalListCollectionSource
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.TraktCollectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutExtrasLogicTest {
    private fun preview(id: String, shape: PosterShape = PosterShape.POSTER, source: String? = null) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = id, poster = "https://addon/$id.jpg", posterShape = shape,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), sourceAddonBaseUrl = source
    )

    @Test
    fun `custom posters replace portrait art and keep the original`() {
        val p = preview("tt1").withCustomPosterUrl("https://posters.example/{imdb_id}/{type}.jpg")
        assertEquals("https://posters.example/tt1/movie.jpg", p.poster)
        assertEquals("https://addon/tt1.jpg", p.rawPosterUrl)
        // A required id the title lacks keeps its own poster.
        assertEquals("https://addon/kitsu:1.jpg", preview("kitsu:1").withCustomPosterUrl("https://p.example/{imdb_id}").poster)
        // Without {shape}, landscape cards keep theirs.
        assertEquals("https://addon/tt1.jpg", preview("tt1", PosterShape.LANDSCAPE).withCustomPosterUrl("https://p.example/{imdb_id}").poster)
        assertEquals("https://p.example/tt1", customPosterFor("https://p.example/{imdb_id}", "tt1", "series"))
    }

    @Test
    fun `custom posters apply only to the screens picked`() {
        val l = LayoutPrefs(customPosterUrlPattern = " https://p.example/{id} ", customPosterScreens = setOf("library"))
        assertEquals("", l.posterPatternFor(CustomPosterScreen.HOME))
        assertEquals("https://p.example/{id}", l.posterPatternFor(CustomPosterScreen.LIBRARY))
        assertEquals("https://p.example/{id}", l.copy(customPosterScreens = null).posterPatternFor(CustomPosterScreen.HOME))
    }

    @Test
    fun `card sizes scale with the TV's card settings`() {
        val style = CardStyle.from(LayoutPrefs(posterCardWidthDp = 252, posterCardHeightDp = 378, posterCardCornerRadiusDp = 8))
        assertEquals(320f, style.size(PosterShape.POSTER).width.value, 0.01f)
        assertEquals(480f, style.size(PosterShape.POSTER).height.value, 0.01f)
        assertEquals(10f, style.corner.value, 0.01f)
        assertEquals(10f, CardStyle.from(LayoutPrefs()).corner.value, 0.01f)
        assertEquals(PosterShape.LANDSCAPE, CardStyle.from(LayoutPrefs(landscapePosters = true)).shapeFor(PosterShape.POSTER))
        assertEquals(200f, CardStyle.from(LayoutPrefs(), kids = true).size(PosterShape.POSTER).width.value, 0.01f)
    }

    @Test
    fun `meta comes from the catalog's addon first when external meta isn't preferred`() {
        val a = Addon(id = "a", baseUrl = "https://a.example", name = "A", version = "1", description = null, logo = null, catalogs = emptyList(), types = emptyList(), resources = emptyList())
        val b = a.copy(id = "b", baseUrl = "https://b.example/")
        val p = preview("tt1", source = "https://b.example")
        assertEquals(listOf("a", "b"), metaAddonOrder(listOf(a, b), p, preferExternal = true).map { it.id })
        assertEquals(listOf("b", "a"), metaAddonOrder(listOf(a, b), p, preferExternal = false).map { it.id })
    }

    @Test
    fun `provider order keeps the list's own order and each provider's options`() {
        val items = listOf(LibraryEntry("c", "movie", "Cat", addedAt = 1), LibraryEntry("a", "movie", "Ant", addedAt = 3), LibraryEntry("b", "movie", "Bee", releaseInfo = "1999", addedAt = 2))
        assertEquals(listOf("c", "a", "b"), LibraryView(items, emptySet(), LibraryQuery(sort = LibrarySort.PROVIDER)).results.map { it.id })
        assertEquals(listOf("a", "b", "c"), LibraryView(items, emptySet(), LibraryQuery(sort = LibrarySort.RECENTLY_ADDED)).results.map { it.id })
        assertEquals(listOf("b", "a", "c"), LibraryView(items, emptySet(), LibraryQuery(sort = LibrarySort.RELEASED_ASC)).results.map { it.id })
        assertEquals(LibrarySort.PROVIDER, LibraryProviderOrder.sortOptions(ListProvider.TRAKT).first())
        assertEquals(LibrarySort.RECENTLY_ADDED, LibraryProviderOrder.sortFor(ListProvider.LOCAL, LibrarySort.PROVIDER))
        assertEquals(LibrarySort.RELEASED_DESC, LibraryProviderOrder.sortFor(ListProvider.MDBLIST, LibrarySort.RELEASED_DESC))
    }

    @Test
    fun `renaming a local list retitles the collection sources showing it`() {
        val folder = CollectionFolder(
            id = "f", title = "F",
            sources = listOf(LocalListCollectionSource("l1", "Old"), LocalListCollectionSource("l2", "Other"), TraktCollectionSource("Trakt", 5))
        )
        val collections = listOf(Collection(id = "c", title = "C", folders = listOf(folder)))
        val renamed = renameLocalListSources(collections, "l1", "New")!!
        assertEquals(listOf("New", "Other"), renamed.single().folders.single().sources.filterIsInstance<LocalListCollectionSource>().map { it.title })
        assertNull(renameLocalListSources(collections, "l9", "New"))
    }

    @Test
    fun `the Expanded card trailer location gives cards the trailer, the hero location doesn't`() {
        val card = LayoutPrefs(
            focusedPosterTrailer = true,
            focusedPosterTrailerTarget = com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD,
            trailerDelaySeconds = 7,
            expandFocusedPosterDelaySeconds = 3
        )
        val trailer = CardStyle.from(card).trailer!!
        // The trailer delay counts from the pointer landing; the expand delay already took 3 s of it.
        assertEquals(4_000L, trailer.delayAfterExpandMs)
        assertNull(CardStyle.from(card, playerOpen = true).trailer)
        assertNull(CardStyle.from(card.copy(focusedPosterTrailerTarget = com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)).trailer)
        assertNull(CardStyle.from(card.copy(focusedPosterTrailer = false)).trailer)
    }
}
