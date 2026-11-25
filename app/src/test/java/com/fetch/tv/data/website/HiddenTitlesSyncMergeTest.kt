package com.fetch.tv.data.website

import com.fetch.tv.data.local.HiddenTitle
import com.fetch.tv.data.local.HiddenTitlesStore
import com.fetch.tv.data.local.encodeHiddenTitle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Not interested" titles (#650) sync as one settings key per title, so the website's key-by-key
 * three-way merge keeps a title hidden on one TV and another restored elsewhere.
 */
class HiddenTitlesSyncMergeTest {

    private fun entry(id: String) = HiddenTitle(ids = listOf(id), type = "movie", name = id).let {
        it.storageKey to buildJsonObject {
            put("type", "string")
            put("value", encodeHiddenTitle(it))
        }
    }

    private fun blob(vararg keys: Pair<String, JsonObject>, hideWatched: Boolean? = null) = buildJsonObject {
        put("version", 1)
        put("features", buildJsonObject {
            put(HiddenTitlesStore.FEATURE, buildJsonObject {
                keys.forEach { (key, value) -> put(key, value) }
                hideWatched?.let { put("hide_watched", buildJsonObject { put("type", "boolean"); put("value", it) }) }
            })
        })
    }

    private fun JsonObject.hiddenKeys(): Set<String> =
        this["features"]!!.jsonObject[HiddenTitlesStore.FEATURE]!!.jsonObject.keys

    @Test
    fun `a title hidden here and one restored on the website both stand`() {
        val base = blob(entry("tt1"), entry("tt2"))
        // This TV hid tt3; the website (another TV) restored tt1 and turned Hide watched on.
        val local = blob(entry("tt1"), entry("tt2"), entry("tt3"))
        val remote = blob(entry("tt2"), hideWatched = true)

        val merged = FetchWebsiteTvMappings.mergeSettings(base = base, local = local, remote = remote)

        assertEquals(
            setOf("title:movie:tt2", "title:movie:tt3", "hide_watched"),
            merged.hiddenKeys()
        )
    }

    @Test
    fun `a title restored on this TV stays restored`() {
        val base = blob(entry("tt1"), entry("tt2"))
        val local = blob(entry("tt2"))
        val remote = blob(entry("tt1"), entry("tt2"))

        val merged = FetchWebsiteTvMappings.mergeSettings(base = base, local = local, remote = remote)

        assertEquals(setOf("title:movie:tt2"), merged.hiddenKeys())
    }
}
