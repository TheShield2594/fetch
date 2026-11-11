package com.fetch.tv.release

import com.fetch.tv.domain.model.WatchedItem
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What an older build wrote must still read after an upgrade. The watched items are stored as
 * Gson JSON by field name, so these pin that format: renaming a field, or losing the R8 rule that
 * keeps the names, would drop watched state on the next update without any error.
 */
class PersistedSyncFormatTest {

    private val gson = Gson()

    @Test
    fun `a watched episode written by an older build still reads`() {
        val stored = """{"contentId":"tt0903747","contentType":"series","title":"Breaking Bad",""" +
            """"season":2,"episode":3,"watchedAt":1731283200000}"""
        val item = gson.fromJson(stored, WatchedItem::class.java)
        assertEquals(
            WatchedItem(
                contentId = "tt0903747",
                contentType = "series",
                title = "Breaking Bad",
                season = 2,
                episode = 3,
                watchedAt = 1_731_283_200_000L
            ),
            item
        )
        assertEquals(item, gson.fromJson(gson.toJson(item), WatchedItem::class.java))
    }

    @Test
    fun `release builds keep the field names of the stored models`() {
        val rules = sequenceOf("proguard-rules.pro", "app/proguard-rules.pro")
            .map(::File)
            .first { it.isFile }
            .readLines()
            .map { it.trim() }
        assertTrue(
            "SavedLibraryItem and WatchedItem live in domain.model; its keep rule is missing",
            "-keep class com.fetch.tv.domain.model.** { *; }" in rules
        )
    }
}
