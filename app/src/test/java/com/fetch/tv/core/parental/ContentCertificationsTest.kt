package com.fetch.tv.core.parental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCertificationsTest {
    @Test
    fun `maps US movie and TV ratings onto one ladder`() {
        assertEquals(CertificationLevel.ALL_AGES, ContentCertifications.levelOf("G"))
        assertEquals(CertificationLevel.ALL_AGES, ContentCertifications.levelOf("TV-Y"))
        assertEquals(CertificationLevel.OLDER_KIDS, ContentCertifications.levelOf("TV-Y7-FV"))
        assertEquals(CertificationLevel.PARENTAL_GUIDANCE, ContentCertifications.levelOf("tv-pg"))
        assertEquals(CertificationLevel.TEENS, ContentCertifications.levelOf("PG-13"))
        assertEquals(CertificationLevel.TEENS, ContentCertifications.levelOf("TV-14"))
        assertEquals(CertificationLevel.MATURE, ContentCertifications.levelOf("Rated R"))
        assertEquals(CertificationLevel.MATURE, ContentCertifications.levelOf("TV-MA"))
        assertEquals(CertificationLevel.ADULTS_ONLY, ContentCertifications.levelOf("NC-17"))
    }

    @Test
    fun `unrated labels have no level`() {
        assertNull(ContentCertifications.levelOf(null))
        assertNull(ContentCertifications.levelOf(""))
        assertNull(ContentCertifications.levelOf("NR"))
        assertNull(ContentCertifications.levelOf("Not Rated"))
        assertNull(ContentCertifications.levelOf("banana"))
    }

    @Test
    fun `uses the country's own system when given`() {
        assertEquals(CertificationLevel.TEENS, ContentCertifications.levelOf("12A", "GB"))
        assertEquals(CertificationLevel.MATURE, ContentCertifications.levelOf("15", "GB"))
        assertEquals(CertificationLevel.MATURE, ContentCertifications.levelOf("16", "DE"))
        assertEquals(CertificationLevel.ALL_AGES, ContentCertifications.levelOf("FSK 0", "DE"))
        assertEquals(CertificationLevel.MATURE, ContentCertifications.levelOf("MA15+", "AU"))
    }

    @Test
    fun `picks preferred country and the strictest rating within it`() {
        val level = ContentCertifications.pickLevel(
            mapOf(
                "FR" to listOf("U"),
                "US" to listOf("", "PG-13", "R"),
                "GB" to listOf("15")
            )
        )
        assertEquals(CertificationLevel.MATURE, level)
    }

    @Test
    fun `falls back to other countries when the preferred ones are blank`() {
        val level = ContentCertifications.pickLevel(mapOf("US" to listOf(""), "SE" to listOf("11")))
        assertEquals(CertificationLevel.TEENS, level)
    }

    @Test
    fun `restriction allows titles up to the limit and handles unrated`() {
        val pg = ContentRestriction(maxLevel = CertificationLevel.PARENTAL_GUIDANCE, allowUnrated = false)
        assertTrue(pg.allowsCertification("G"))
        assertTrue(pg.allowsCertification("TV-PG"))
        assertFalse(pg.allowsCertification("PG-13"))
        assertFalse(pg.allowsCertification(null))
        assertTrue(pg.copy(allowUnrated = true).allowsCertification("NR"))
        assertTrue(ContentRestriction.Unrestricted.allowsCertification("NC-17"))
    }
}
