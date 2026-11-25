package com.fetch.tv.data.website

import android.content.Context
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A profile deleted on the website is cleaned off this TV the way a delete made here is, so the
 * next profile given its slot doesn't inherit its history, settings and sign-ins (#670).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class ProfileDeletedOnWebsiteTest {

    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("deleted_on_website_test", Context.MODE_PRIVATE)
    private lateinit var store: FetchWebsiteStore
    private val client = mockk<FetchWebsiteClient>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)
    private val profileDataStore = mockk<ProfileDataStore>(relaxed = true)
    private val restrictions = mockk<ProfileRestrictionsDataStore>(relaxed = true)

    private fun profile(id: Int) = UserProfile(id = id, name = "Profile $id", avatarColorHex = "#1E88E5")
    private fun remote(slot: Int) = WebsiteSlottedProfile(slot = slot, id = "website-$slot", name = "Profile $slot")

    private fun service() = FetchWebsiteSyncService(
        store, client, profileManager, profileDataStore, restrictions, mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
    )

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        store = FetchWebsiteStore(prefs)
        store.save(FetchWebsiteSession(serverUrl = "https://fetch.example", deviceToken = "token", deviceName = "TV"))
        every { profileManager.profiles } returns MutableStateFlow(listOf(profile(1), profile(2), profile(3)))
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        every { restrictions.restrictions } returns flowOf(emptyMap())
        every { restrictions.titleLists } returns flowOf(emptyMap())
        every { restrictions.kidsRules } returns flowOf(emptyMap())
        every { restrictions.kidsRulesChangedAt } returns flowOf(emptyMap())
    }

    @Test
    fun `a slot the website no longer has is wiped before the list is replaced`() = runBlocking {
        coEvery { client.profiles() } returns listOf(remote(1), remote(2))

        service().pullProfiles()

        coVerifyOrder {
            profileManager.deleteProfile(3)
            profileDataStore.replaceAllProfiles(any())
        }
        coVerify(exactly = 0) { profileManager.deleteProfile(1) }
        coVerify(exactly = 0) { profileManager.deleteProfile(2) }
    }

    @Test
    fun `a cleanup that fails keeps the list, so the next pull tries it again`() = runBlocking {
        coEvery { client.profiles() } returns listOf(remote(1), remote(2))
        coEvery { profileManager.deleteProfile(3) } throws IOException("disk") andThen true
        val service = service()

        assertFalse(service.pullProfiles())
        coVerify(exactly = 0) { profileDataStore.replaceAllProfiles(any()) }

        assertTrue(service.pullProfiles())
        coVerify(exactly = 2) { profileManager.deleteProfile(3) }
        coVerify(exactly = 1) { profileDataStore.replaceAllProfiles(any()) }
    }

    @Test
    fun `the primary profile is never wiped`() = runBlocking {
        coEvery { client.profiles() } returns listOf(remote(2))

        service().pullProfiles()

        coVerify(exactly = 0) { profileManager.deleteProfile(1) }
        coVerify(exactly = 1) { profileManager.deleteProfile(3) }
    }

    @Test
    fun `a profile made here and not sent yet keeps its data`() = runBlocking {
        // Created on this TV since the last push: missing on the website, but not deleted there.
        store.markProfilesChanged()
        coEvery { client.profiles() } returns listOf(remote(1), remote(2))

        service().pullProfiles()

        coVerify(exactly = 0) { profileManager.deleteProfile(any()) }
    }

    @Test
    fun `an empty website list wipes nothing`() = runBlocking {
        coEvery { client.profiles() } returns emptyList()

        service().pullProfiles()

        coVerify(exactly = 0) { profileManager.deleteProfile(any()) }
    }
}
