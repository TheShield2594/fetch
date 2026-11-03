package com.fetch.desktop.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StringCatalogTest {

    private fun xml(vararg strings: Pair<String, String>) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" +
            strings.joinToString("\n") { (k, v) -> "    <string name=\"$k\">$v</string>" } + "\n</resources>"

    private fun catalog(folders: Map<String, String>) =
        StringCatalog { folder -> folders[folder]?.byteInputStream() }

    private val sample = catalog(
        mapOf(
            "values" to xml(
                "settings" to "Settings",
                "nav_settings" to "Settings",
                "cancel" to "Cancel",
                "increase_contrast" to "Increase Contrast",
                "remove" to "Remove %1\$s?",
                "episodes" to "%d episodes",
                "loading" to "Loading..."
            ),
            "values-de" to xml(
                "nav_settings" to "Einstellungen",
                "cancel" to "Abbrechen",
                "increase_contrast" to "Kontrast erhöhen",
                "remove" to "%1\$s entfernen?",
                "episodes" to "%d Folgen",
                "loading" to "Wird geladen …"
            ),
            "values-pt-rBR" to xml("cancel" to "Cancelar"),
            "values-b+es+419" to xml("cancel" to "Cancelar (419)")
        )
    )

    @Test
    fun `translates by English text, using the first key the language has`() {
        assertEquals("Einstellungen", sample.translate("Settings", "de"))
        assertEquals("Abbrechen", sample.translate("Cancel", "de"))
        assertEquals("Cancelar", sample.translate("Cancel", "pt-BR"))
        assertEquals("Cancelar (419)", sample.translate("Cancel", "es-419"))
    }

    @Test
    fun `a key left in English gives way to one that is translated`() {
        val c = catalog(
            mapOf(
                "values" to xml("collections_cancel" to "Cancel", "action_cancel" to "Cancel"),
                "values-de" to xml("collections_cancel" to "Cancel", "action_cancel" to "Abbrechen")
            )
        )
        assertEquals("Abbrechen", c.translate("Cancel", "de"))
    }

    @Test
    fun `unknown or untranslated text stays English`() {
        assertEquals("Something desktop only", sample.translate("Something desktop only", "de"))
        assertEquals("Settings", sample.translate("Settings", "pt-BR"))
        assertEquals("Cancel", sample.translate("Cancel", "fr"))
        assertEquals("Cancel", sample.translate("Cancel", "en"))
        assertEquals("", sample.translate("", "de"))
    }

    @Test
    fun `loose matching ignores case, ellipsis style and a closing full stop`() {
        assertEquals("Kontrast erhöhen", sample.translate("Increase contrast", "de"))
        assertEquals("Wird geladen …", sample.translate("Loading…", "de"))
        assertEquals("Abbrechen", sample.translate("Cancel.", "de"))
    }

    @Test
    fun `capitals stay capitals`() {
        assertEquals("ABBRECHEN", sample.translate("CANCEL", "de"))
        assertEquals("CANCEL", sample.translate("CANCEL", "fr"))
    }

    @Test
    fun `formats arguments into the translation`() {
        assertEquals("Dune entfernen?", sample.format("Remove %1\$s?", "de", "Dune"))
        assertEquals("3 Folgen", sample.format("%d episodes", "de", 3))
        assertEquals("Remove Dune?", sample.format("Remove %1\$s?", "en", "Dune"))
        // Not in the catalog: the English template is formatted.
        assertEquals("Season 2", sample.format("Season %1\$d", "de", 2))
    }

    @Test
    fun `placeholders match whatever their type`() {
        val c = catalog(
            mapOf(
                "values" to xml("season" to "Season %1\$d", "by" to "By %s"),
                "values-de" to xml("season" to "Staffel %1\$d", "by" to "Von %s")
            )
        )
        assertEquals("Staffel 2", c.format("Season %1\$s", "de", 2))
        assertEquals("Staffel 2", c.format("Season %1\$s", "de", "2"))
        assertEquals("Von Fetch", c.format("By %1\$s", "de", "Fetch"))
        assertEquals("Season 2", c.format("Season %1\$s", "en", 2))
        assertEquals("100%", c.format("%1\$s%%", "de", 100))
    }

    @Test
    fun `a translation the arguments don't fit falls back to English`() {
        val broken = catalog(
            mapOf(
                "values" to xml("n" to "%d items"),
                "values-de" to xml("n" to "%1\$s Elemente %2\$d")
            )
        )
        assertEquals("4 items", broken.format("%d items", "de", 4))
    }

    @Test
    fun `later sources add to and override earlier ones`() {
        val tv = mapOf("values" to xml("a" to "Play"), "values-de" to xml("a" to "Wiedergabe"))
        val desktop = mapOf("values" to xml("d" to "Built-in player"), "values-de" to xml("d" to "Integrierter Player"))
        val merged = StringCatalog(listOf({ f: String -> tv[f]?.byteInputStream() }, { f: String -> desktop[f]?.byteInputStream() }))
        assertEquals("Wiedergabe", merged.translate("Play", "de"))
        assertEquals("Integrierter Player", merged.translate("Built-in player", "de"))
    }

    @Test
    fun `android escapes are undone`() {
        val c = catalog(
            mapOf(
                "values" to xml(
                    "a" to "Don\\'t \\\"quote\\\" me",
                    "b" to "Line one\\nLine two",
                    "c" to "\\@handle \\? &amp; more",
                    "d" to "\"  keep   spaces  \"",
                    "e" to "  collapse \n   these  ",
                    "f" to "\\u00e9t\\u00e9"
                )
            )
        )
        assertEquals("a", c.keysFor("Don't \"quote\" me").single())
        assertEquals("b", c.keysFor("Line one\nLine two").single())
        assertEquals("c", c.keysFor("@handle ? & more").single())
        assertEquals("  keep   spaces  ", c.english("d"))
        assertEquals("collapse these", c.english("e"))
        assertEquals("été", c.english("f"))
    }

    @Test
    fun `plurals and arrays are skipped`() {
        val c = catalog(
            mapOf(
                "values" to "<resources><string name=\"s\">One</string><plurals name=\"p\"><item quantity=\"one\">One</item></plurals>" +
                    "<string-array name=\"arr\"><item>Two</item></string-array></resources>"
            )
        )
        assertEquals(listOf("s"), c.keysFor("One"))
        assertTrue(c.keysFor("Two").isEmpty())
    }

    @Test
    fun `a broken or missing file leaves text English`() {
        val c = catalog(mapOf("values" to xml("a" to "Play"), "values-de" to "<resources><string"))
        assertEquals("Play", c.translate("Play", "de"))
        assertEquals("Play", catalog(emptyMap()).translate("Play", "de"))
    }

    @Test
    fun `the TV's own strings load from the build's resources`() {
        val c = StringCatalog.fromClasspath()
        val key = c.keysFor("Cancel").firstOrNull()
        // The build copies app/src/main/res into i18n/tv; without it there is nothing to check.
        if (key == null) return
        assertNotEquals("Cancel", c.translate("Cancel", "de"))
        assertNotEquals("Cancel", c.translate("Cancel", "ja"))
        assertNull(c.english("no_such_key_for_sure"))
    }

    @Test
    fun `every TV language folder is listed`() {
        val res = listOf(File("../app/src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory } ?: return
        val onDisk = res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").exists() }
            .map { it.name }.sorted()
        assertEquals(onDisk, Languages.qualifiers.sorted())
        // And the picker offers each of them.
        val picker = Languages.pickerTags.map { Languages.normalize(it) }.toSet()
        Languages.qualifiers.forEach { q -> assertTrue("$q isn't in the picker", Languages.qualifierToTag(q) in picker) }
    }
}
