package com.fetch.desktop.collections

import com.fetch.tv.domain.model.AddonCatalogCollectionSource
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import com.fetch.tv.domain.model.FetchListCollectionSource
import com.fetch.tv.domain.model.FolderViewMode
import com.fetch.tv.domain.model.LocalListCollectionSource
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.TmdbCollectionFilters
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import com.fetch.tv.domain.model.TmdbCollectionSort
import com.fetch.tv.domain.model.TmdbCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionSourceType
import com.fetch.tv.domain.model.TraktCollectionSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CollectionsCodecTest {

    private val sample = listOf(
        Collection(
            id = "c1",
            title = "Studios",
            backdropImageUrl = "https://img/back.jpg",
            pinToTop = true,
            viewMode = FolderViewMode.ROWS,
            showAllTab = false,
            folders = listOf(
                CollectionFolder(
                    id = "f1",
                    title = "Marvel",
                    coverEmoji = "🦸",
                    tileShape = PosterShape.LANDSCAPE,
                    hideTitle = true,
                    sources = listOf(
                        AddonCatalogCollectionSource("com.linvo.cinemeta", "movie", "top", "Action", "https://v3-cinemeta.strem.io"),
                        TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Marvel Studios", 420, TmdbCollectionMediaType.MOVIE),
                        TmdbCollectionSource(
                            TmdbCollectionSourceType.DISCOVER, "Korean thrillers", null, TmdbCollectionMediaType.TV,
                            TmdbCollectionSort.VOTE_AVERAGE_DESC.value,
                            TmdbCollectionFilters(withGenres = "53", withOriginalLanguage = "ko", voteCountGte = 100, year = 2020)
                        ),
                        TraktCollectionSource("Best of", 12345L, TmdbCollectionMediaType.TV, "added", "desc"),
                        LocalListCollectionSource("l1", "Weekend", TmdbCollectionMediaType.MOVIE),
                        FetchListCollectionSource("w1", "Website", null)
                    )
                ),
                CollectionFolder(id = "f2", title = "Pixar", coverImageUrl = "https://img/pixar.png")
            )
        )
    )

    @Test
    fun `collections survive a round trip`() {
        assertEquals(sample, CollectionsCodec.parse(CollectionsCodec.encode(sample)))
        assertEquals(sample, CollectionsCodec.parse(CollectionsCodec.encodeElement(sample)))
    }

    @Test
    fun `the json has the TV's field names, provider tags and legacy catalogSources`() {
        val folder = Json.parseToJsonElement(CollectionsCodec.encode(sample)).jsonArray[0].jsonObject["folders"]!!.jsonArray[0].jsonObject
        assertEquals("LANDSCAPE", folder["tileShape"]!!.jsonPrimitive.content)
        val sources = folder["sources"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("addon", "tmdb", "tmdb", "trakt", "local", "fetch"), sources.map { it["provider"]!!.jsonPrimitive.content })
        assertEquals("COMPANY", sources[1]["tmdbSourceType"]!!.jsonPrimitive.content)
        assertEquals("MOVIE", sources[1]["mediaType"]!!.jsonPrimitive.content)
        assertEquals("ko", sources[2]["filters"]!!.jsonObject["withOriginalLanguage"]!!.jsonPrimitive.content)
        assertEquals("12345", sources[3]["traktListId"]!!.jsonPrimitive.content)
        // Gson leaves nulls out.
        assertFalse("coverImageUrl" in folder)
        assertFalse("genre" in sources[1])
        val legacy = folder["catalogSources"]!!.jsonArray.single().jsonObject
        assertEquals("top", legacy["catalogId"]!!.jsonPrimitive.content)
        assertEquals("Action", legacy["genre"]!!.jsonPrimitive.content)
    }

    @Test
    fun `json from the TV reads with its rules`() {
        val tv = """
            [{"id":"a","title":"Old","folders":[
               {"id":"f","title":"Legacy","tileShape":"wide",
                "catalogSources":[{"addonId":"x","type":"movie","catalogId":"pop"}]},
               {"id":"g","title":"New","tileShape":"poster","focusGifEnabled":false,"sources":[
                 {"provider":"tmdb","tmdbSourceType":"list","tmdbId":7,"sortBy":"popularity.desc"},
                 {"provider":"tmdb","tmdbSourceType":"bogus"},
                 {"provider":"trakt","traktListId":0},
                 {"provider":"trakt","traktListId":9,"sortBy":"WEIRD","sortHow":"DESC"},
                 {"provider":"local","listId":"l"},
                 {"addonId":"y","type":"series","catalogId":"c","addonUrl":"javascript:alert(1)"},
                 {"provider":"addon","addonId":"z","type":"movie","catalogId":"d","addonUrl":" https://addon.example/ "},
                 {"provider":"addon","addonId":"","type":"movie","catalogId":"e"},
                 {"provider":"plugin","pluginId":"p"}
               ]}]}]
        """
        val folders = CollectionsCodec.parse(tv).single().folders
        val legacy = folders[0]
        assertEquals(PosterShape.LANDSCAPE, legacy.tileShape)
        assertEquals(listOf(AddonCatalogCollectionSource("x", "movie", "pop")), legacy.sources)
        val modern = folders[1]
        assertEquals(PosterShape.POSTER, modern.tileShape)
        assertFalse(modern.focusGifEnabled)
        val sources = modern.sources
        assertEquals(5, sources.size)
        val list = sources[0] as TmdbCollectionSource
        // A list keeps its own order unless another sort was picked.
        assertEquals(TmdbCollectionSort.ORIGINAL.value, list.sortBy)
        assertEquals("List", list.title)
        val trakt = sources[1] as TraktCollectionSource
        assertEquals("Trakt List 9", trakt.title)
        assertEquals("rank", trakt.sortBy)
        assertEquals("desc", trakt.sortHow)
        assertEquals(LocalListCollectionSource("l", "TV list", null), sources[2])
        // Only a web address is kept as where to read an uninstalled addon from.
        assertNull((sources[3] as AddonCatalogCollectionSource).addonUrl)
        assertEquals("https://addon.example", (sources[4] as AddonCatalogCollectionSource).addonUrl)
    }

    @Test
    fun `duplicate ids keep one and a collection without an id is dropped`() {
        val parsed = CollectionsCodec.parse(
            """[{"id":"a","title":"First","folders":[]},{"id":"a","title":"Second","folders":[]},{"id":"","title":"No id"}]"""
        )
        assertEquals(listOf("Second"), parsed.map { it.title })
    }

    @Test
    fun `broken json reads as nothing, and validation says why`() {
        assertTrue(CollectionsCodec.parse("not json").isEmpty())
        assertTrue(CollectionsCodec.parse("""{"id":"x"}""").isEmpty())
        assertNotNull(CollectionsCodec.validate(""))
        assertNotNull(CollectionsCodec.validate("{}"))
        assertNotNull(CollectionsCodec.validate("[]"))
        assertNull(CollectionsCodec.validate(CollectionsCodec.encode(sample)))
    }

    @Test
    fun `the store keeps a profile's collections in its folder`() {
        val dir = Files.createTempDirectory("collections").toFile()
        try {
            val store = CollectionsStore.inProfile(dir)
            assertTrue(store.load().isEmpty())
            store.save(sample)
            assertTrue(File(dir, "collections.json").exists())
            assertEquals(sample, CollectionsStore.inProfile(dir).load())
            File(dir, "collections.json").writeText("garbage")
            assertTrue(store.load().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
