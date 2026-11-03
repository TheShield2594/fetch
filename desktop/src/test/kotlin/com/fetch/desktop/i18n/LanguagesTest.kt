package com.fetch.desktop.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {

    @Test
    fun `android qualifiers map to BCP-47 tags`() {
        assertEquals("en", Languages.qualifierToTag("values"))
        assertEquals("de", Languages.qualifierToTag("values-de"))
        assertEquals("pt-BR", Languages.qualifierToTag("values-pt-rBR"))
        assertEquals("zh-TW", Languages.qualifierToTag("values-zh-rTW"))
        assertEquals("es-419", Languages.qualifierToTag("values-b+es+419"))
        assertEquals("sr-Latn", Languages.qualifierToTag("values-b+sr+Latn"))
        assertEquals("he", Languages.qualifierToTag("values-iw"))
        assertEquals("id", Languages.qualifierToTag("values-in"))
        assertNull(Languages.qualifierToTag("values-v31"))
        assertNull(Languages.qualifierToTag("values-night"))
        assertNull(Languages.qualifierToTag("drawable"))
    }

    @Test
    fun `tags find their folder`() {
        assertEquals("values-pt-rBR", Languages.tagToQualifier("pt-BR"))
        assertEquals("values-pt-rBR", Languages.tagToQualifier("pt_BR"))
        assertEquals("values-in", Languages.tagToQualifier("in"))
        assertEquals("values-in", Languages.tagToQualifier("id"))
        assertEquals("values-iw", Languages.tagToQualifier("he"))
        assertEquals("values-b+sr+Latn", Languages.tagToQualifier("sr-Latn"))
        assertEquals("values-b+es+419", Languages.tagToQualifier("es-419"))
        assertNull(Languages.tagToQualifier("en"))
    }

    @Test
    fun `a locale resolves to the closest language there is`() {
        assertEquals("en", Languages.resolve(null))
        assertEquals("en", Languages.resolve(""))
        assertEquals("en", Languages.resolve("en-GB"))
        assertEquals("de", Languages.resolve("de-AT"))
        assertEquals("de", Languages.resolve("de"))
        assertEquals("fr", Languages.resolve("fr-CA"))
        assertEquals("es", Languages.resolve("es-ES"))
        assertEquals("es", Languages.resolve("es"))
        assertEquals("es-419", Languages.resolve("es-MX"))
        assertEquals("es-419", Languages.resolve("es-419"))
        assertEquals("pt-BR", Languages.resolve("pt"))
        assertEquals("pt-PT", Languages.resolve("pt-PT"))
        assertEquals("pt-BR", Languages.resolve("pt-BR"))
        assertEquals("zh-TW", Languages.resolve("zh-Hant-HK"))
        assertEquals("zh-TW", Languages.resolve("zh-HK"))
        assertEquals("zh-CN", Languages.resolve("zh"))
        assertEquals("zh-CN", Languages.resolve("zh-Hans-SG"))
        assertEquals("sr-Latn", Languages.resolve("sr-RS"))
        assertEquals("he", Languages.resolve("iw-IL"))
        assertEquals("id", Languages.resolve("in"))
        assertEquals("no", Languages.resolve("nb-NO"))
        assertEquals("en", Languages.resolve("ko-KR"))
    }

    @Test
    fun `the picker names each language in its own words`() {
        val options = Languages.pickerOptions().toMap()
        assertEquals(Languages.pickerTags.size, options.size)
        assertEquals("Deutsch", options["de"])
        assertEquals("English", options["en"])
        assertTrue(options.values.all { it.isNotBlank() && it.first().let { c -> !c.isLowerCase() } })
    }

    @Test
    fun `right to left languages`() {
        assertTrue(Languages.isRtl("ar"))
        assertTrue(Languages.isRtl("he"))
        assertTrue(Languages.isRtl("iw"))
        assertFalse(Languages.isRtl("de"))
    }
}
