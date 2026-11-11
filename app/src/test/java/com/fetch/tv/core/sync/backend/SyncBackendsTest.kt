package com.fetch.tv.core.sync.backend

import android.content.Context
import android.content.SharedPreferences
import com.fetch.tv.data.website.FetchWebsiteStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SyncBackendsTest {
    private val stored = mutableMapOf<String, String?>()
    private var paired = false

    private val website = mockk<FetchWebsiteSyncBackend> {
        every { kind } returns SyncBackendKind.FETCH_WEBSITE
        every { isConnected } answers { paired }
    }

    private fun backends(): SyncBackends {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }
        val preferences = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers { stored[firstArg()] ?: secondArg() }
            every { edit() } returns editor
        }
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns preferences
        }
        val store = mockk<FetchWebsiteStore> { every { isPaired } answers { paired } }
        return SyncBackends(context, store) { website }
    }

    @Test
    fun `nothing chosen and nothing connected is local only`() {
        val backends = backends()
        assertEquals(SyncBackendKind.LOCAL_ONLY, backends.chosen)
        assertNull(backends.active)
        assertEquals(false, backends.canSync)
    }

    @Test
    fun `nothing chosen follows the website pairing`() {
        val backends = backends()
        paired = true
        assertSame(website, backends.active)
        assertEquals(true, backends.isWebsiteActive)
    }

    @Test
    fun `a stored choice that isn't set up yet syncs nothing until it is`() {
        val backends = backends()
        backends.choose(SyncBackendKind.FETCH_WEBSITE)
        assertEquals(SyncBackendKind.FETCH_WEBSITE, backends.chosen)
        assertNull(backends.active)

        paired = true
        assertSame(website, backends.active)
    }

    @Test
    fun `a pairing made before there was a choice wins over a stored local only`() {
        val backends = backends()
        backends.choose(SyncBackendKind.LOCAL_ONLY)
        paired = true
        assertEquals(SyncBackendKind.FETCH_WEBSITE, backends.chosen)
    }

    @Test
    fun `a TV that chose the removed Supabase server reads as local only`() {
        stored["choice"] = "SUPABASE"
        assertEquals(SyncBackendKind.LOCAL_ONLY, backends().storedChoice.value)
    }

    @Test
    fun `the choice is kept across restarts`() {
        backends().choose(SyncBackendKind.FETCH_WEBSITE)
        assertEquals(SyncBackendKind.FETCH_WEBSITE, backends().storedChoice.value)
    }
}
