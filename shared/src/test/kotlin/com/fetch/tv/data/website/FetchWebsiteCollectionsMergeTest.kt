package com.fetch.tv.data.website

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class FetchWebsiteCollectionsMergeTest {

    private fun list(vararg collections: Pair<String, String>): JsonArray =
        Json.parseToJsonElement(
            collections.joinToString(",", "[", "]") { (id, title) -> """{"id":"$id","title":"$title"}""" }
        ) as JsonArray

    private fun merge(base: JsonArray?, local: JsonArray, remote: JsonArray) =
        FetchWebsiteCollectionsMerge.merge(base, local, remote)

    @Test
    fun `a collection another TV added survives this TV renaming a different one`() {
        // The scenario of #669: TV A adds X; TV B, which hasn't pulled, renames Y and pushes.
        val base = list("y" to "Y")
        val local = list("y" to "Y renamed")
        val remote = list("y" to "Y", "x" to "X")

        assertEquals(list("y" to "Y renamed", "x" to "X"), merge(base, local, remote))
    }

    @Test
    fun `an untouched collection takes the website's edit`() {
        val base = list("a" to "A", "b" to "B")
        val local = list("a" to "A", "b" to "B", "c" to "C")
        val remote = list("a" to "A elsewhere", "b" to "B")

        assertEquals(list("a" to "A elsewhere", "b" to "B", "c" to "C"), merge(base, local, remote))
    }

    @Test
    fun `a collection deleted elsewhere goes, unless it was changed here`() {
        val base = list("a" to "A", "b" to "B")
        val remote = list()

        assertEquals(list("b" to "B edited"), merge(base, list("a" to "A", "b" to "B edited"), remote))
    }

    @Test
    fun `a collection deleted here stays deleted`() {
        val base = list("a" to "A", "b" to "B")
        val local = list("a" to "A")
        val remote = list("a" to "A", "b" to "B", "x" to "X")

        assertEquals(list("a" to "A", "x" to "X"), merge(base, local, remote))
    }

    @Test
    fun `the local edit wins when both sides changed one collection`() {
        val base = list("a" to "A")

        assertEquals(list("a" to "A here"), merge(base, list("a" to "A here"), list("a" to "A there")))
    }

    @Test
    fun `without a base nothing is treated as deleted`() {
        val local = list("a" to "A here")
        val remote = list("a" to "A there", "x" to "X")

        assertEquals(list("a" to "A here", "x" to "X"), merge(null, local, remote))
    }

    @Test
    fun `key order inside a collection is not a change`() {
        val base = Json.parseToJsonElement("""[{"id":"a","title":"A"}]""") as JsonArray
        val local = Json.parseToJsonElement("""[{"title":"A","id":"a"}]""") as JsonArray

        assertEquals(list("a" to "A elsewhere"), merge(base, local, list("a" to "A elsewhere")))
    }
}
