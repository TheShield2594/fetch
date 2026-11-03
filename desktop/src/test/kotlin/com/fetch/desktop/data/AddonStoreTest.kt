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

    @Test
    fun `switched-off addons are saved with the list and dropped with their addon`() {
        val file = File(temp.root, "addons.json")
        AddonStore(file).save(listOf("https://a.example", "https://b.example"), setOf("https://b.example", "https://gone.example"))
        assertEquals(setOf("https://b.example"), AddonStore(file).loadDisabled())
        // Saving without the switches keeps them; removing the addon drops its switch.
        AddonStore(file).save(listOf("https://b.example", "https://a.example"))
        assertEquals(setOf("https://b.example"), AddonStore(file).loadDisabled())
        AddonStore(file).save(listOf("https://a.example"))
        assertEquals(emptySet<String>(), AddonStore(file).loadDisabled())
    }

    @Test
    fun `a list saved before the switches existed loads with every addon on`() {
        val file = File(temp.root, "addons.json").apply { writeText("""{"addons":["https://a.example"]}""") }
        assertEquals(listOf("https://a.example"), AddonStore(file).load())
        assertEquals(emptySet<String>(), AddonStore(file).loadDisabled())
    }
}
