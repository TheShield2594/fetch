package com.fetch.tv.data.local

import com.fetch.tv.core.tracking.LocalList
import com.fetch.tv.core.tracking.LocalListKind
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.SavedLibraryItem
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalListsJsonTest {
    private val gson = Gson()

    @Test
    fun `lists are stored under their field names and round trip`() {
        val list = list(description = "Weekend picks")
        val json = gson.toJson(listOf(list))

        val keys = JsonParser.parseString(json).asJsonArray[0].asJsonObject.keySet()
        assertEquals(setOf("id", "name", "kind", "description", "createdAt", "updatedAt", "items"), keys)
        assertEquals(listOf(list), LocalListsJson.decode(gson, json))
    }

    @Test
    fun `lists written by a minified build are still read`() {
        val itemJson = gson.toJson(item())
        val legacy = """[{"a":"id-1","b":"Movies","c":"custom","d":"Weekend picks","e":10,"f":20,"g":[$itemJson]}]"""

        assertEquals(listOf(list(description = "Weekend picks")), LocalListsJson.decode(gson, legacy))
    }

    @Test
    fun `minified list without a description is still read`() {
        val legacy = """[{"a":"id-1","b":"Movies","c":"custom","e":10,"f":20,"g":[]}]"""

        assertEquals(listOf(list(description = null, items = emptyList())), LocalListsJson.decode(gson, legacy))
    }

    @Test
    fun `damaged input reads as no lists`() {
        assertTrue(LocalListsJson.decode(gson, "{").isEmpty())
        assertEquals(listOf(null), LocalListsJson.decode(gson, "[1]"))
    }

    private fun list(description: String?, items: List<SavedLibraryItem> = listOf(item())) = LocalList(
        id = "id-1",
        name = "Movies",
        kind = LocalListKind.CUSTOM,
        description = description,
        createdAt = 10L,
        updatedAt = 20L,
        items = items
    )

    private fun item() = SavedLibraryItem(
        id = "tt1",
        type = "movie",
        name = "Film",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = listOf("Drama"),
        addonBaseUrl = null,
        addedAt = 3L
    )
}
