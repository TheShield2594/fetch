package com.fetch.desktop.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AddonStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a first run starts with the default addons`() {
        val store = AddonStore(File(temp.root, "fetch/addons.json"))
        assertEquals(AddonStore.DEFAULT_ADDONS, store.load())
    }

    @Test
    fun `saved addons load back in order without duplicates`() {
        val file = File(temp.root, "fetch/addons.json")
        AddonStore(file).save(listOf("https://b.example", "https://a.example", "https://b.example"))
        assertEquals(listOf("https://b.example", "https://a.example"), AddonStore(file).load())
    }

    @Test
    fun `an empty saved list stays empty`() {
        val file = File(temp.root, "addons.json")
        AddonStore(file).save(emptyList())
        assertEquals(emptyList<String>(), AddonStore(file).load())
    }

    @Test
    fun `an unreadable file falls back to the defaults`() {
        val file = File(temp.root, "addons.json").apply { writeText("not json") }
        assertEquals(AddonStore.DEFAULT_ADDONS, AddonStore(file).load())
    }
}
