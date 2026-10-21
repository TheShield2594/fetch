package com.fetch.tv.data.repository

import com.fetch.tv.data.local.normalizeIntroDbUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipMarkerSourcesTest {

    @Test
    fun `introdb url gets a scheme and one trailing slash`() {
        assertEquals("https://api.introdb.app/", normalizeIntroDbUrl("api.introdb.app"))
        assertEquals("http://10.0.0.5:8080/v1/", normalizeIntroDbUrl(" http://10.0.0.5:8080/v1// "))
    }

    @Test
    fun `introdb url rejects blank and non-http values`() {
        assertNull(normalizeIntroDbUrl(""))
        assertNull(normalizeIntroDbUrl("ftp://example.com"))
        assertNull(normalizeIntroDbUrl("https://"))
    }

    @Test
    fun `only aniskip markers with an id can be reported`() {
        val base = SkipInterval(1.0, 60.0, "op", "aniskip", sourceId = "abc")
        assertTrue(canReportSkipInterval(base))
        assertFalse(canReportSkipInterval(base.copy(sourceId = null)))
        assertFalse(canReportSkipInterval(base.copy(provider = "introdb")))
    }

    @Test
    fun `submission types follow introdb's episode and movie segments`() {
        assertEquals("intro", introDbSubmissionType("intro", isMovie = false))
        assertEquals("outro", introDbSubmissionType("ed", isMovie = false))
        assertEquals("outro", introDbSubmissionType("outro", isMovie = true))
        assertNull(introDbSubmissionType("intro", isMovie = true))
        assertNull(introDbSubmissionType("post-credits", isMovie = false))
    }
}
