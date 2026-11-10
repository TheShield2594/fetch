package com.fetch.tv.core.deeplink

import android.content.Context
import com.fetch.tv.R
import com.fetch.tv.core.addon.AddonInstallOutcome
import com.fetch.tv.core.addon.installOrReplace
import com.fetch.tv.core.addon.needsConfiguration
import com.fetch.tv.core.addon.normalizeAddonInstallUrl
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.server.PhoneAddonRequest
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.ui.screens.addon.AddonManagementAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Addon installs from stremio:// and fetch:// links.
 *
 * MainActivity is exported for those schemes, so any app or web page on the TV can fire one.
 * A link therefore never installs on its own: [requestInstall] reads the manifest and puts it
 * up as [pendingAddon] for someone at the TV to confirm, as the phone hub does, and both
 * steps check that the active profile may change its addons.
 */
@Singleton
class DeepLinkHandler @Inject constructor(
    private val addonRepository: AddonRepository,
    private val profileManager: ProfileManager,
    private val restrictionsDataStore: ProfileRestrictionsDataStore,
    @ApplicationContext private val context: Context
) {
    private val _pendingAddon = MutableStateFlow<PhoneAddonRequest?>(null)

    /** The addon a link asked to install, waiting for an answer at the TV; null when none. */
    val pendingAddon: StateFlow<PhoneAddonRequest?> = _pendingAddon.asStateFlow()

    /**
     * Why the active profile can't take an addon now, or null when it can.
     *
     * Reads the profile id, the profile list and the restrictions as loaded from disk rather than
     * the StateFlows' placeholders: on a cold start a link can arrive before those have loaded,
     * and the placeholders (profile 1, no restrictions) would let it through.
     */
    suspend fun installBlocked(): String? {
        val loaded = withTimeoutOrNull(PROFILE_READY_TIMEOUT_MS) {
            val profileId = profileManager.loadedActiveProfileId.first { it != null }!!
            val profiles = profileManager.loadedProfiles.first { it != null }!!
            profileId to profiles
        } ?: return context.getString(R.string.link_addon_profile_not_ready)
        val (profileId, profiles) = loaded
        return when {
            restrictionsDataStore.restrictions.first()[profileId]?.isRestricted == true ->
                context.getString(R.string.parental_addons_blocked)
            AddonManagementAccess.isReadOnly(profiles.find { it.id == profileId }) ->
                context.getString(R.string.phone_web_addon_read_only)
            else -> null
        }
    }

    /**
     * Reads the manifest at [manifestUrl] and, when it can be installed, asks the TV to
     * confirm it. Returns a message to show now (blocked, unreadable, already installed),
     * or null when the question is on screen.
     */
    suspend fun requestInstall(manifestUrl: String): DeepLinkInstallResult? {
        installBlocked()?.let { return DeepLinkInstallResult.Error(it) }
        val url = normalizeAddonInstallUrl(manifestUrl) ?: manifestUrl
        val addon = when (val result = withTimeoutOrNull(MANIFEST_TIMEOUT_MS) { addonRepository.fetchAddon(url) }) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> return DeepLinkInstallResult.Error(
                result.message.takeIf { it.isNotBlank() }
                    ?.let { context.getString(R.string.phone_web_addon_unreadable_reason, it) }
                    ?: context.getString(R.string.phone_web_addon_unreadable)
            )
            else -> return DeepLinkInstallResult.Error(context.getString(R.string.phone_web_addon_no_answer))
        }
        val name = addon.displayName.ifBlank { addon.name.ifBlank { url } }
        if (addon.needsConfiguration) {
            return DeepLinkInstallResult.Error(context.getString(R.string.addon_needs_configuration, name))
        }
        val alreadyInstalled = addonRepository.getInstalledAddons().first()
            .any { installed ->
                val installedUrl = installed.baseUrl.trimEnd('/')
                installedUrl.equals(addon.baseUrl.trimEnd('/'), ignoreCase = true) ||
                    installedUrl.equals(url.trimEnd('/'), ignoreCase = true)
            }
        if (alreadyInstalled) {
            return DeepLinkInstallResult.Success(context.getString(R.string.phone_web_addon_already_installed, name))
        }
        // A link that is still installing keeps the dialog; a newer link replaces one nobody answered.
        if (_pendingAddon.value?.installing == true) return null
        _pendingAddon.value = PhoneAddonRequest(
            id = UUID.randomUUID().toString(),
            url = url,
            name = name,
            description = addon.description?.takeIf { it.isNotBlank() },
            resources = addon.resources.map { it.name }.distinct(),
            catalogCount = addon.catalogs.size
        )
        return null
    }

    /**
     * Installs the pending addon with [requestId], as someone at the TV just confirmed it. Null when
     * there was nothing to do. Bound to the id the dialog showed, so a newer link fired just before
     * the press can't swap in a different addon under the viewer's Install.
     */
    suspend fun confirmPending(requestId: String): DeepLinkInstallResult? {
        val request = _pendingAddon.value?.takeUnless { it.installing }?.takeIf { it.id == requestId } ?: return null
        // The profile may have changed, or been locked, since the link arrived.
        installBlocked()?.let { reason ->
            _pendingAddon.compareAndSet(request, null)
            return DeepLinkInstallResult.Error(reason)
        }
        val installing = request.copy(installing = true)
        if (!_pendingAddon.compareAndSet(request, installing)) return null
        return try {
            installAddon(request.url)
        } finally {
            _pendingAddon.compareAndSet(installing, null)
        }
    }

    fun rejectPending(requestId: String) {
        val request = _pendingAddon.value?.takeUnless { it.installing }?.takeIf { it.id == requestId } ?: return
        _pendingAddon.compareAndSet(request, null)
    }

    private suspend fun installAddon(manifestUrl: String): DeepLinkInstallResult {
        val url = normalizeAddonInstallUrl(manifestUrl) ?: manifestUrl
        return when (val outcome = addonRepository.installOrReplace(url)) {
            is AddonInstallOutcome.Installed -> {
                val addonName = outcome.addon.displayName.ifBlank { outcome.addon.baseUrl }
                DeepLinkInstallResult.Success(
                    context.getString(
                        if (outcome.replacedUrl != null) R.string.addon_configure_updated else R.string.addon_install_success,
                        addonName
                    )
                )
            }
            is AddonInstallOutcome.NeedsConfiguration -> DeepLinkInstallResult.Error(
                context.getString(
                    R.string.addon_needs_configuration,
                    outcome.addon.displayName.ifBlank { outcome.addon.baseUrl }
                )
            )
            is AddonInstallOutcome.Failed -> DeepLinkInstallResult.Error(
                outcome.message.ifBlank { context.getString(R.string.addon_error_invalid_url) }
            )
        }
    }

    private companion object {
        const val MANIFEST_TIMEOUT_MS = 15_000L
        const val PROFILE_READY_TIMEOUT_MS = 5_000L
    }
}

sealed interface DeepLinkInstallResult {
    val message: String

    data class Success(
        override val message: String
    ) : DeepLinkInstallResult

    data class Error(
        override val message: String
    ) : DeepLinkInstallResult
}
