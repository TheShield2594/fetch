package com.fetch.tv.core.sync.backend

import android.content.Context
import android.content.SharedPreferences
import com.fetch.tv.core.auth.AuthManager
import com.fetch.tv.data.website.FetchWebsiteStore
import com.fetch.tv.domain.model.AuthState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SyncBackendsTest {
    private val stored = mutableMapOf<String, String?>()
    private var paired = false
    private val authState = MutableStateFlow<AuthState>(AuthState.SignedOut)

    private val website = mockk<FetchWebsiteSyncBackend> {
        every { kind } returns SyncBackendKind.FETCH_WEBSITE
        every { isConnected } answers { paired }
    }
    private val supabase = mockk<SupabaseSyncBackend> {
        every { kind } returns SyncBackendKind.SUPABASE
        every { isConnected } answers { authState.value is AuthState.FullAccount }
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
        val auth = mockk<AuthManager> { every { authState } returns this@SyncBackendsTest.authState }
        return SyncBackends(context, store, auth, { website }, { supabase })
    }

    private fun signInToSupabase() {
        authState.value = AuthState.FullAccount(userId = "user", email = "user@example.com")
    }

    @Test
    fun `nothing chosen and nothing connected is local only`() {
        val backends = backends()
        assertEquals(SyncBackendKind.LOCAL_ONLY, backends.chosen)
        assertNull(backends.active)
        assertEquals(false, backends.canSync)
    }

    @Test
    fun `nothing chosen follows the connection, the website ahead of Supabase`() {
        val backends = backends()
        signInToSupabase()
        assertSame(supabase, backends.active)

        paired = true
        assertSame(website, backends.active)
        assertEquals(true, backends.isWebsiteActive)
        assertEquals(false, backends.isSupabaseActive)
    }

    @Test
    fun `a stored choice wins while it is connected`() {
        val backends = backends()
        paired = true
        signInToSupabase()
        backends.choose(SyncBackendKind.SUPABASE)
        assertSame(supabase, backends.active)
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
    fun `a connection made some other way is used over a stored choice that isn't connected`() {
        val backends = backends()
        backends.choose(SyncBackendKind.LOCAL_ONLY)
        signInToSupabase()
        assertEquals(SyncBackendKind.SUPABASE, backends.chosen)
        assertSame(supabase, backends.active)
    }

    @Test
    fun `the choice is kept across restarts`() {
        backends().choose(SyncBackendKind.FETCH_WEBSITE)
        assertEquals(SyncBackendKind.FETCH_WEBSITE, backends().storedChoice.value)
    }
}
