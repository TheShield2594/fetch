package com.fetch.tv.ui.screens.home

import com.fetch.tv.data.youtube.YouTubeVideo
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModernHomeContentTypesTest {

    @Test
    fun `movie and series catalog cards share one content type`() {
        val movie = modernCarouselItemContentType(catalogItem("m1", itemType = "movie"))
        val series = modernCarouselItemContentType(catalogItem("s1", itemType = "series"))

        assertEquals(MODERN_CARD_CONTENT_TYPE_CATALOG, movie)
        assertEquals(movie, series)
    }

    @Test
    fun `placeholders, Continue Watching, folders and videos keep their own card types`() {
        assertEquals(
            MODERN_CARD_CONTENT_TYPE_PLACEHOLDER,
            modernCarouselItemContentType(catalogItem("__placeholder_0", itemType = "movie"))
        )
        assertEquals(
            MODERN_CARD_CONTENT_TYPE_CONTINUE_WATCHING,
            modernCarouselItemContentType(item("cw", ModernPayload.ContinueWatching(continueWatchingItem())))
        )
        assertEquals(
            MODERN_CARD_CONTENT_TYPE_COLLECTION_FOLDER,
            modernCarouselItemContentType(
                item(
                    "folder",
                    ModernPayload.CollectionFolder(
                        focusKey = "folder",
                        collectionId = "c",
                        folderId = "f",
                        posterShape = PosterShape.POSTER,
                        focusGifEnabled = false,
                        focusGifUrl = null,
                        heroBackdropUrl = null,
                        heroVideoUrl = null,
                        titleLogoUrl = null
                    )
                )
            )
        )
        val video = YouTubeVideo(videoId = "v", title = "V", thumbnailUrl = "https://example.test/v.jpg")
        assertEquals(
            MODERN_CARD_CONTENT_TYPE_YOUTUBE,
            modernCarouselItemContentType(item("yt", ModernPayload.YouTubeVideo(focusKey = "yt", video = video)))
        )
    }

    @Test
    fun `catalog rows of every media type share a row type`() {
        val movies = modernHomeRowContentType(row(key = "a_movie_top", apiType = "movie"))
        val series = modernHomeRowContentType(row(key = "a_series_top", apiType = "series"))

        assertEquals(MODERN_ROW_CONTENT_TYPE_CATALOG, movies)
        assertEquals(movies, series)
    }

    @Test
    fun `Continue Watching style rows no longer share a type with collection rows`() {
        val continueWatching = modernHomeRowContentType(row(key = MODERN_CONTINUE_WATCHING_ROW_KEY, apiType = null))
        val upcoming = modernHomeRowContentType(row(key = MODERN_UPCOMING_ROW_KEY, apiType = null))
        val readyTonight = modernHomeRowContentType(row(key = MODERN_READY_TONIGHT_ROW_KEY, apiType = null))
        val collection = modernHomeRowContentType(row(key = "collection_1", apiType = null))

        assertEquals(MODERN_ROW_CONTENT_TYPE_CONTINUE_WATCHING, continueWatching)
        assertEquals(continueWatching, upcoming)
        assertEquals(continueWatching, readyTonight)
        assertEquals(MODERN_ROW_CONTENT_TYPE_OTHER, collection)
        assertNotEquals(continueWatching, collection)
    }

    private fun continueWatchingItem() = ContinueWatchingItem.NextUp(NextUpInfo(
        "show", "series", "Show", null, null, null, "show:1:2", 1, 2, null,
        thumbnail = null, lastWatched = 1, sortTimestamp = 1
    ))

    private fun row(key: String, apiType: String?) = HomeRowModel(
        key = key,
        title = key,
        globalRowIndex = 0,
        items = StableList(emptyList()),
        apiType = apiType
    )

    private fun catalogItem(id: String, itemType: String) = item(
        id,
        ModernPayload.Catalog(
            focusKey = id,
            itemId = id,
            itemType = itemType,
            addonBaseUrl = "https://example.test/manifest.json",
            trailerTitle = id,
            trailerReleaseInfo = null,
            trailerApiType = itemType
        )
    )

    private fun item(key: String, payload: ModernPayload) = ModernCarouselItem(
        key = key,
        title = key,
        subtitle = null,
        imageUrl = null,
        heroPreview = HeroPreview(
            title = key,
            logo = null,
            description = null,
            contentTypeText = null,
            yearText = null,
            imdbText = null,
            genres = StableList(emptyList()),
            poster = null,
            backdrop = null,
            imageUrl = null
        ),
        payload = payload
    )
}
