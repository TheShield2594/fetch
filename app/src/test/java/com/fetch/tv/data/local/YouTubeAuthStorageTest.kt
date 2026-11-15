package com.fetch.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.youtube.YouTubeToken
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The shared YouTube client's view of the per-profile sign-in: what a request learns about the
 * sign-in goes back to the profile it was read from, and only while that profile still holds it.
 */
class YouTubeAuthStorageTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = MutableStateFlow(1)
    private val tokens = mutableMapOf<Int, YouTubeTokens?>()
    private val stores = mutableMapOf<Int, DataStore<Preferences>>()

    private val authStore: YouTubeAuthDataStore by lazy {
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), any()) } answers {
                val id = firstArg<Int>()
                stores.getOrPut(id) {
                    PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("youtube_$id.preferences_pb") }
                }
            }
        }
        val profiles = mockk<ProfileManager> {
            every { activeProfileId } returns active
            every { currentProfileId() } answers { active.value }
        }
        val credentials = mockk<YouTubeCredentialStore> {
            every { read(any()) } answers { tokens[firstArg()] ?: YouTubeTokens() }
            every { write(any(), any()) } answers { tokens[firstArg()] = secondArg() }
        }
        YouTubeAuthDataStore(factory, profiles, credentials)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a refresh that answers after a profile switch lands in the profile that asked`() = runBlocking {
        tokens[1] = YouTubeTokens("A1", "R1", 0L)
        tokens[2] = YouTubeTokens("A2", "R2", 5L)
        val read = authStore.storage.read()
        assertEquals(1, read.location)
        active.value = 2
        authStore.storage.saveRefreshedToken(read, YouTubeToken("A1-new", null, 3600), nowMs = 1_000)
        assertEquals(YouTubeTokens("A1-new", "R1", 1_000 + 3_600_000L), tokens[1])
        assertEquals("profile 2 is untouched", YouTubeTokens("A2", "R2", 5L), tokens[2])
    }

    @Test
    fun `a refresh for a sign-in that's since been replaced is dropped`() = runBlocking {
        tokens[1] = YouTubeTokens("A1", "R1", 0L)
        val read = authStore.storage.read()
        // Signed out and in again (another account) while the refresh was on its way.
        tokens[1] = YouTubeTokens("B1", "S1", 9L)
        authStore.storage.saveRefreshedToken(read, YouTubeToken("A1-new", null, 3600), nowMs = 1_000)
        authStore.storage.expireAccessToken(read)
        authStore.storage.clearRevokedSignIn(read)
        assertEquals(YouTubeTokens("B1", "S1", 9L), tokens[1])
    }

    @Test
    fun `Google dropping a sign-in signs out only the profile it belonged to`() = runBlocking {
        tokens[1] = YouTubeTokens("A1", "R1", 0L)
        tokens[2] = YouTubeTokens("A2", "R2", 5L)
        val read = authStore.storage.read()
        active.value = 2
        authStore.storage.clearRevokedSignIn(read)
        assertNull(tokens[1])
        assertEquals(YouTubeTokens("A2", "R2", 5L), tokens[2])
    }
}
