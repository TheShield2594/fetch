package com.fetch.desktop

import com.fetch.desktop.mdblist.FakeReply
import com.fetch.desktop.mdblist.MdbListFakeHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private var tags = listOf("v1.3.0")
    private val http = MdbListFakeHttp { _, _ ->
        FakeReply(200, tags.joinToString(",", "[", "]") { """{"tag_name":"$it","html_url":"https://gh/$it"}""" })
    }
    private val notified = mutableListOf<String>()

    private fun checker(scope: kotlinx.coroutines.CoroutineScope) = UpdateChecker(
        scope,
        http = http.client,
        current = { "1.3.0" },
        releasesUrl = "https://api.github.test/releases",
        notifier = { notified += it.version }
    )

    @Test
    fun `the background check notifies once per version, and the start-up check doesn't notify`() = runBlocking {
        val updates = checker(this)
        tags = listOf("v1.3.0", "v1.4.0")
        updates.check(beta = false)
        updates.checkAndWait()
        assertEquals("1.4.0", updates.available?.version)
        assertEquals(emptyList<String>(), notified)

        updates.check(beta = false, notify = true)
        updates.checkAndWait()
        updates.check(beta = false, notify = true)
        updates.checkAndWait()
        assertEquals(listOf("1.4.0"), notified)

        tags = listOf("v1.3.0", "v1.4.0", "v1.5.0")
        updates.check(beta = false, notify = true)
        updates.checkAndWait()
        assertEquals(listOf("1.4.0", "1.5.0"), notified)
    }

    @Test
    fun `Later hides the banner until a newer version turns up`() = runBlocking {
        val updates = checker(this)
        tags = listOf("v1.4.0")
        updates.check(beta = false)
        updates.checkAndWait()
        updates.dismissed = true

        updates.check(beta = false)
        updates.checkAndWait()
        assertTrue("same version stays hidden", updates.dismissed)

        tags = listOf("v1.4.0", "v1.5.0")
        updates.check(beta = false)
        updates.checkAndWait()
        assertFalse("a newer version shows again", updates.dismissed)
    }

    /** check() launches into the scope; let it finish before looking. */
    private suspend fun UpdateChecker.checkAndWait() {
        while (checking) kotlinx.coroutines.yield()
    }
}
