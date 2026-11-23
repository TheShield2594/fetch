package com.fetch.tv.core.server

import com.fetch.tv.R
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.profile.TvBackup
import com.fetch.tv.core.profile.TvBackupRestoreResult
import com.fetch.tv.data.anilist.AniListApi
import com.fetch.tv.data.anilist.AniListApiException
import com.fetch.tv.data.anilist.AniListAuthStore
import com.fetch.tv.data.anilist.AniListViewer
import com.fetch.tv.data.local.MDBListSettingsDataStore
import com.fetch.tv.data.mediaserver.ServerConnection
import com.fetch.tv.data.mediaserver.ServerException
import com.fetch.tv.data.mediaserver.ServerFailure
import com.fetch.tv.data.mediaserver.ServerProvider
import com.fetch.tv.data.mediaserver.ServerRepository
import com.fetch.tv.data.mediaserver.ServerSignInMethod
import com.fetch.tv.data.remote.api.MDBListApi
import com.fetch.tv.data.website.FetchWebsitePairing
import com.fetch.tv.data.website.FetchWebsiteStore
import com.fetch.tv.data.website.WebsitePairingState
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.AddonBehaviorHints
import com.fetch.tv.domain.model.AddonResource
import com.fetch.tv.domain.model.UserProfile
import com.fetch.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * The phone hub's actions on the TV (#580): what a locked profile may not change, how account
 * keys and media servers are checked before they are saved, and the addon a phone sends, from
 * proposal to the TV's yes or no. Only [PhoneHubState.urlFor] was tested before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PhoneHubControllerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    private var gated = false
    private val parentalControls = mockk<ParentalControls> { every { needsGate() } answers { gated } }
    private val mdbListApi = mockk<MDBListApi>()
    private val mdbListSettings = mockk<MDBListSettingsDataStore>(relaxed = true)
    private val aniListApi = mockk<AniListApi>()
    private val aniListAuth = mockk<AniListAuthStore>(relaxed = true)
    private val serverRepository = mockk<ServerRepository>()
    private val tvBackup = mockk<TvBackup>()
    private val profileManager = mockk<ProfileManager>(relaxed = true) {
        every { activeProfile } returns UserProfile(1, "Main", "#fff")
    }
    private val websitePairing = mockk<FetchWebsitePairing>()
    private val websiteStore = mockk<FetchWebsiteStore>(relaxed = true)
    private val installed = MutableStateFlow<List<Addon>>(emptyList())
    private val addons = mockk<AddonRepository>(relaxed = true) {
        every { getInstalledAddons() } returns installed
        every { addonChangeProfileId() } returns 1
    }

    private val controller = PhoneHubController(
        context = app,
        remoteCommandBus = mockk(relaxed = true),
        mdbListSettingsDataStore = mdbListSettings,
        mdbListApi = mdbListApi,
        traktAuthService = mockk(relaxed = true),
        traktAuthDataStore = mockk(relaxed = true),
        traktProgressService = mockk(relaxed = true),
        simklAuthRepository = mockk(relaxed = true),
        serverRepository = serverRepository,
        playerSettingsDataStore = mockk(relaxed = true),
        streamBadgeSettingsDataStore = mockk(relaxed = true),
        layoutPreferenceDataStore = mockk(relaxed = true),
        parentalControls = parentalControls,
        profileTransfer = mockk(relaxed = true),
        tvBackup = tvBackup,
        addonRepository = addons,
        profileManager = profileManager,
        websiteStore = websiteStore,
        websitePairing = dagger.Lazy { websitePairing },
        aniListApi = aniListApi,
        aniListAuth = aniListAuth,
        malApi = mockk(relaxed = true),
        malAuth = mockk(relaxed = true)
    )

    @After
    fun tearDown() = controller.stop()

    // ─── Locked profiles ───

    @Test
    fun aLockedProfileCanChangeNoSettingOrAccount() = runBlocking {
        gated = true
        val blocked = text(R.string.phone_hub_parental_blocked)
        assertEquals(PhoneHubResult(false, blocked), controller.saveMdbListKey("key"))
        assertEquals(PhoneHubResult(false, blocked), controller.connectAniList("token"))
        assertEquals(PhoneHubResult(false, blocked), controller.connectMal("code"))
        assertEquals(PhoneHubResult(false, blocked), controller.connectMediaServer("jellyfin", "a", "u", "p"))
        assertEquals(PhoneHubResult(false, blocked), controller.restoreBackup(buildJsonObject {}))
        assertEquals(blocked, controller.exportBackup(includeAddons = true).exceptionOrNull()?.message)
        assertEquals(blocked, controller.startTrackerSignIn("trakt").exceptionOrNull()?.message)
        assertEquals(PhoneHubWebsiteStatus(state = "failed", message = blocked), controller.connectWebsite("example.com"))
        assertEquals(emptyList<Pair<Int, String>>(), controller.transferableProfiles())
        assertEquals(blocked, controller.settingsChangeBlocked())
        controller.onScreenOpened()
        assertEquals(PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, message = blocked), controller.proposeAddon(URL))
        // Nothing reached a service or a store.
        coVerify(exactly = 0) { mdbListApi.getUser(any()) }
        coVerify(exactly = 0) { mdbListSettings.setApiKey(any()) }
        coVerify(exactly = 0) { aniListApi.viewer(any()) }
        coVerify(exactly = 0) { addons.fetchAddon(any()) }
    }

    // ─── Account keys ───

    @Test
    fun anMdbListKeyIsSavedOnlyOnceTheServiceAcceptsIt() = runBlocking {
        coEvery { mdbListApi.getUser("good") } returns Response.success(Unit)
        coEvery { mdbListApi.getUser("bad") } returns Response.error(401, "".toResponseBody())
        coEvery { mdbListApi.getUser("offline") } throws java.io.IOException("no network")

        assertEquals(PhoneHubResult(true, text(R.string.phone_web_mdblist_saved)), controller.saveMdbListKey("good"))
        assertEquals(PhoneHubResult(false, text(R.string.phone_web_mdblist_rejected)), controller.saveMdbListKey("bad"))
        assertEquals(PhoneHubResult(false, text(R.string.phone_web_mdblist_rejected)), controller.saveMdbListKey("offline"))
        // A blank key removes the saved one without asking the service.
        assertEquals(PhoneHubResult(true, text(R.string.phone_web_mdblist_removed)), controller.saveMdbListKey(" "))

        coVerify(exactly = 1) { mdbListSettings.setApiKey("good") }
        coVerify(exactly = 1) { mdbListSettings.setApiKey(" ") }
        coVerify(exactly = 0) { mdbListSettings.setApiKey("bad") }
        coVerify(exactly = 0) { mdbListSettings.setApiKey("offline") }
        coVerify(exactly = 0) { mdbListApi.getUser(" ") }
    }

    @Test
    fun anAniListTokenIsSavedToTheProfileActiveWhenItArrived() = runBlocking {
        every { aniListApi.isConfigured } returns true
        every { aniListAuth.activeProfileId() } returnsMany listOf(2, 3)
        coEvery { aniListApi.viewer("good") } returns AniListViewer(7, "Frieren")
        coEvery { aniListApi.viewer("expired") } throws AniListApiException("no viewer", unauthorized = true)
        coEvery { aniListApi.viewer("down") } throws AniListApiException("503")

        assertEquals(PhoneHubResult(true, text(R.string.phone_web_anilist_connected, "Frieren")), controller.connectAniList("good"))
        coVerify { aniListAuth.save(2, "good", any(), 7, "Frieren") }
        assertEquals(PhoneHubResult(false, text(R.string.phone_web_anilist_rejected)), controller.connectAniList("expired"))
        assertEquals(PhoneHubResult(false, text(R.string.phone_web_anilist_unavailable)), controller.connectAniList("down"))
        assertEquals(PhoneHubResult(false, text(R.string.phone_web_anilist_rejected)), controller.connectAniList(""))
    }

    @Test
    fun aMalCodeWithoutASignInStartedOnTheTvIsRejected() = runBlocking {
        assertEquals(PhoneHubResult(false, text(R.string.phone_web_mal_rejected)), controller.connectMal("def502abc"))
    }

    // ─── Media servers ───

    @Test
    fun aMediaServerSignInExplainsEachFailureInTheTvsWords() = runBlocking {
        val jellyfin = mockk<ServerProvider> {
            every { id } returns "jellyfin"
            every { displayName } returns "Jellyfin"
            every { minimumVersion } returns "10.8"
            every { signInMethod } returns ServerSignInMethod.PASSWORD
        }
        val plex = mockk<ServerProvider> {
            every { id } returns "plex"
            every { signInMethod } returns ServerSignInMethod.LINK_CODE
        }
        every { serverRepository.providers } returns listOf(jellyfin, plex)
        val expected = mapOf(
            ServerFailure.AUTH_REQUIRED to text(R.string.servers_error_auth),
            ServerFailure.NOT_FOUND to text(R.string.servers_error_address),
            ServerFailure.UNREACHABLE to text(R.string.servers_error_unreachable),
            ServerFailure.UNSUPPORTED to text(R.string.servers_error_unsupported, "Jellyfin", "10.8"),
            ServerFailure.FORBIDDEN to text(R.string.servers_error_forbidden),
            ServerFailure.INCOMPLETE to text(R.string.servers_error_failed),
            ServerFailure.FAILED to text(R.string.servers_error_failed)
        )
        assertEquals(ServerFailure.entries.toSet(), expected.keys)
        expected.forEach { (failure, message) ->
            coEvery { serverRepository.connect(jellyfin, any(), any(), any()) } throws ServerException(failure)
            assertEquals(failure.name, PhoneHubResult(false, message), controller.connectMediaServer("jellyfin", "h", "u", "p"))
        }

        val connection = mockk<ServerConnection> { every { name } returns "Den" }
        coEvery { serverRepository.connect(jellyfin, "https://den.example", "viewer", " pw ") } returns connection
        assertEquals(
            PhoneHubResult(true, text(R.string.phone_web_server_connected, "Den")),
            controller.connectMediaServer("jellyfin", "  https://den.example ", " viewer ", " pw ")
        )
        // A provider that signs in with a link code, or none, can't be signed in to from the phone.
        assertEquals(PhoneHubResult(false, text(R.string.phone_web_server_unknown)), controller.connectMediaServer("plex", "h", "u", "p"))
        assertEquals(PhoneHubResult(false, text(R.string.phone_web_server_unknown)), controller.connectMediaServer("emby", "h", "u", "p"))
    }

    // ─── Website pairing ───

    @Test
    fun websitePairingStatesReachThePhoneByName() = runBlocking {
        val pairingState = MutableStateFlow<WebsitePairingState>(WebsitePairingState.EnterAddress)
        every { websitePairing.state } returns pairingState
        every { websitePairing.lastAddress } returns "fetch.example"
        every { websiteStore.session } returns MutableStateFlow<com.fetch.tv.data.website.FetchWebsiteSession?>(null)
        val cases = listOf(
            WebsitePairingState.EnterAddress to PhoneHubWebsiteStatus(state = "idle"),
            WebsitePairingState.Starting to PhoneHubWebsiteStatus(state = "starting"),
            WebsitePairingState.Expired to PhoneHubWebsiteStatus(state = "expired")
        )
        cases.forEach { (state, expected) ->
            pairingState.value = state
            assertEquals(expected.copy(lastAddress = "fetch.example"), controller.websiteStatus())
        }
    }

    // ─── Backups ───

    @Test
    fun aRestoreSaysWhatItMergedAddedAndSkipped() = runBlocking {
        val backup = buildJsonObject { put("format", TvBackup.FORMAT) }
        assertEquals(
            PhoneHubResult(false, text(R.string.phone_web_backup_not_a_backup)),
            controller.restoreBackup(buildJsonObject { put("format", "something-else") })
        )
        val separator = text(R.string.phone_web_list_separator)
        coEvery { tvBackup.restore(backup) } returns TvBackupRestoreResult(listOf("Ada", "Ben"), listOf("Cy"), listOf("Kids"))
        val result = controller.restoreBackup(backup)
        assertTrue(result.ok)
        assertEquals(
            listOf(
                text(R.string.phone_web_backup_restored_merged, "Ada${separator}Ben"),
                text(R.string.phone_web_backup_restored_added, "Cy"),
                text(R.string.phone_web_backup_restored_skipped, "Kids")
            ).joinToString(" "),
            result.message
        )
        // Only skipped profiles: nothing was restored.
        coEvery { tvBackup.restore(backup) } returns TvBackupRestoreResult(emptyList(), emptyList(), listOf("Kids"))
        assertFalse(controller.restoreBackup(backup).ok)
        coEvery { tvBackup.restore(backup) } throws IllegalStateException("disk full")
        assertEquals(PhoneHubResult(false, text(R.string.phone_web_backup_restore_failed)), controller.restoreBackup(backup))
    }

    // ─── Addons sent from the phone ───

    @Test
    fun installedAddonsAreNamedAndMarkedWhenTheyGiveStreams() = runBlocking {
        installed.value = listOf(
            addon("Torrentio", resources = listOf("stream")),
            addon("", displayName = "", url = "https://catalog.example/manifest.json", resources = listOf("catalog", "meta"))
        )
        assertEquals(
            listOf(PhoneHubAddon("Torrentio", streams = true), PhoneHubAddon("https://catalog.example/manifest.json", streams = false)),
            controller.installedAddons()
        )
    }

    @Test
    fun anAddonIsOnlyProposedWhileTheHubScreenIsOpen() = runBlocking {
        assertEquals(
            PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, message = text(R.string.phone_web_addon_open_screen)),
            controller.proposeAddon(URL)
        )
        coVerify(exactly = 0) { addons.fetchAddon(any()) }
    }

    @Test
    fun anAddonThatCantBeReadOrNeedsSetupOrIsInstalledIsNotProposed() = runBlocking {
        controller.onScreenOpened()

        coEvery { addons.fetchAddon(URL) } returns NetworkResult.Error("HTTP 404")
        assertEquals(text(R.string.phone_web_addon_unreadable_reason, "HTTP 404"), controller.proposeAddon(URL).message)
        coEvery { addons.fetchAddon(URL) } returns NetworkResult.Error("")
        assertEquals(text(R.string.phone_web_addon_unreadable), controller.proposeAddon(URL).message)

        coEvery { addons.fetchAddon(URL) } returns NetworkResult.Success(addon("Comet", configurationRequired = true))
        val setup = controller.proposeAddon(URL)
        assertEquals(PhoneHubAddonStatus.NEEDS_CONFIGURATION, setup.status)
        assertEquals("https://addon.example/configure", setup.configureUrl)

        coEvery { addons.fetchAddon(URL) } returns NetworkResult.Success(addon("Torrentio"))
        installed.value = listOf(addon("Torrentio", url = "HTTPS://ADDON.EXAMPLE/manifest.json/"))
        assertEquals(PhoneHubAddonStatus.INSTALLED, controller.proposeAddon(URL).status)
        assertNull(controller.pendingAddon.value)
    }

    @Test
    fun theTvConfirmsAProposedAddonAndThePhoneSeesItInstalled() = runBlocking {
        controller.onScreenOpened()
        coEvery { addons.fetchAddon(URL) } returns NetworkResult.Success(
            addon("Torrentio", description = "Torrent streams", resources = listOf("stream", "stream"), catalogs = 0)
        )
        coEvery { addons.addAddonFor(1, URL) } returns true

        val proposal = controller.proposeAddon(URL)
        assertEquals(PhoneHubAddonStatus.PENDING, proposal.status)
        val request = controller.pendingAddon.value!!
        assertEquals(PhoneAddonRequest(proposal.id!!, URL, "Torrentio", "Torrent streams", listOf("stream"), 0), request)

        controller.confirmPendingAddon()
        assertEquals(PhoneHubAddonStatus.INSTALLED, awaitSettled(proposal.id!!).status)
        assertNull(controller.pendingAddon.value)
        coVerify { addons.addAddonFor(1, URL) }
    }

    @Test
    fun aFailedInstallIsReportedAndClearsTheDialog() = runBlocking {
        controller.onScreenOpened()
        coEvery { addons.fetchAddon(URL) } returns NetworkResult.Success(addon("Torrentio"))
        val id = controller.proposeAddon(URL).id!!
        // The manifest stopped answering between the proposal and the TV's yes.
        coEvery { addons.fetchAddon(URL) } returns NetworkResult.Error("")
        controller.confirmPendingAddon()
        assertEquals(
            PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, id, "Torrentio", message = text(R.string.phone_web_addon_install_failed, "Torrentio")),
            awaitSettled(id)
        )
        assertNull(controller.pendingAddon.value)
    }

    @Test
    fun aProfileLockedBeforeTheTvConfirmsInstallsNothing() = runBlocking {
        controller.onScreenOpened()
        coEvery { addons.fetchAddon(URL) } returns NetworkResult.Success(addon("Torrentio"))
        val id = controller.proposeAddon(URL).id!!
        gated = true
        controller.confirmPendingAddon()
        assertEquals(
            PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, id, "Torrentio", message = text(R.string.phone_hub_parental_blocked)),
            controller.addonProposal(id)
        )
        assertNull(controller.pendingAddon.value)
        coVerify(exactly = 0) { addons.addAddonFor(any(), any()) }
    }

    @Test
    fun aProfileThatSharesTheMainProfilesAddonsCantTakeOne() = runBlocking {
        every { profileManager.activeProfile } returns UserProfile(2, "Kid", "#fff", usesPrimaryAddons = true)
        controller.onScreenOpened()
        assertEquals(
            PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, message = text(R.string.phone_web_addon_read_only)),
            controller.proposeAddon(URL)
        )
    }

    @Test
    fun aNewerProposalReplacesOneNobodyAnsweredAndClosingTheScreenRejectsIt() = runBlocking {
        controller.onScreenOpened()
        coEvery { addons.fetchAddon(any()) } answers { NetworkResult.Success(addon(firstArg<String>().substringAfter("//").substringBefore('.'), url = firstArg())) }
        val first = controller.proposeAddon("https://first.example/manifest.json")
        val second = controller.proposeAddon("https://second.example/manifest.json")
        assertEquals(PhoneHubAddonStatus.REJECTED, controller.addonProposal(first.id!!)!!.status)
        assertEquals(second.id, controller.pendingAddon.value?.id)

        // Closing the screen rejects the request; with no phone paired it also stops the hub,
        // which forgets every proposal. Either way it is no longer waiting for an answer.
        controller.onScreenClosed()
        assertTrue(controller.addonProposal(second.id!!)?.status in setOf(null, PhoneHubAddonStatus.REJECTED))
        assertNull(controller.pendingAddon.value)
        coVerify(exactly = 0) { addons.addAddonFor(any(), any()) }
        // With the screen closed, nobody is there to answer a new one.
        assertEquals(text(R.string.phone_web_addon_open_screen), controller.proposeAddon(URL).message)
    }

    @Test
    fun theTvCanDeclineAProposedAddon() = runBlocking {
        controller.onScreenOpened()
        coEvery { addons.fetchAddon(URL) } returns NetworkResult.Success(addon("Torrentio"))
        val id = controller.proposeAddon(URL).id!!
        controller.rejectPendingAddon()
        assertEquals(PhoneHubAddonProposal(PhoneHubAddonStatus.REJECTED, id, "Torrentio"), controller.addonProposal(id))
        assertNull(controller.pendingAddon.value)
        coVerify(exactly = 0) { addons.addAddonFor(any(), any()) }
    }

    /** The install runs in the background; waits for its outcome to replace the pending one. */
    private suspend fun awaitSettled(id: String): PhoneHubAddonProposal = withTimeout(5_000) {
        while (controller.addonProposal(id)?.status == PhoneHubAddonStatus.PENDING || controller.pendingAddon.value != null) delay(10)
        controller.addonProposal(id)!!
    }

    private fun addon(
        name: String,
        displayName: String = name,
        url: String = URL,
        description: String? = null,
        resources: List<String> = listOf("stream"),
        catalogs: Int = 0,
        configurationRequired: Boolean = false
    ) = Addon(
        id = "org.$name",
        name = name,
        displayName = displayName,
        version = "1.0.0",
        description = description,
        logo = null,
        baseUrl = url,
        catalogs = emptyList<com.fetch.tv.domain.model.CatalogDescriptor>().also { check(catalogs == 0) },
        types = emptyList(),
        resources = resources.map { AddonResource(it, listOf("movie"), null) },
        behaviorHints = AddonBehaviorHints(configurable = configurationRequired, configurationRequired = configurationRequired)
    )

    private companion object {
        const val URL = "https://addon.example/manifest.json"
    }
}
