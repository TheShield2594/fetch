package com.fetch.tv.core.addon

import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.repository.AddonRepository
import kotlinx.coroutines.flow.first

sealed interface AddonInstallOutcome {
    /** Installed; [replacedUrl] is the entry it took the place of, if any. */
    data class Installed(val addon: Addon, val replacedUrl: String?) : AddonInstallOutcome

    /** Not installed: the manifest says it must be configured first. */
    data class NeedsConfiguration(val addon: Addon, val url: String) : AddonInstallOutcome

    data class Failed(val message: String) : AddonInstallOutcome

    /**
     * Not installed: the profile that confirmed the install was switched away from (or lost the
     * right to change addons) while the manifest was being fetched.
     */
    data class ProfileChanged(val addon: Addon) : AddonInstallOutcome
}

/**
 * Installs [url], or swaps it in for an existing entry:
 * - [replacing] names the entry being configured (the Configure flow on a card);
 * - otherwise an installed, still-unconfigured copy of the same addon (same manifest id) is
 *   replaced, so configuring it does not leave a broken duplicate behind. Other installed
 *   copies stay: people keep several configurations of one addon on purpose.
 *
 * A manifest that still says `configurationRequired` is not installed.
 *
 * [profileId] is the profile that confirmed the install, captured by the caller at that moment
 * (it defaults to the one active when this is called). The write is held to it, and refused
 * with [AddonInstallOutcome.ProfileChanged] when another profile became active during the
 * manifest fetch, so the addon can't land on a profile whose access was never checked.
 */
suspend fun AddonRepository.installOrReplace(
    url: String,
    replacing: String? = null,
    profileId: Int? = addonChangeProfileId()
): AddonInstallOutcome {
    val addon = when (val result = fetchAddon(url)) {
        is NetworkResult.Success -> result.data
        is NetworkResult.Error -> return AddonInstallOutcome.Failed(result.message)
        NetworkResult.Loading -> return AddonInstallOutcome.Failed("")
    }
    if (addon.needsConfiguration) return AddonInstallOutcome.NeedsConfiguration(addon, url)

    val sameUrl = { other: String -> other.trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true) }
    val target = replacing?.takeUnless(sameUrl)
        ?: getInstalledAddons().first().firstOrNull { installed ->
            installed.id == addon.id && installed.needsConfiguration && !sameUrl(installed.baseUrl)
        }?.baseUrl
    val written = when {
        profileId == null && target != null -> { replaceAddonUrl(target, url); true }
        profileId == null -> { addAddon(url); true }
        target != null -> replaceAddonUrlFor(profileId, target, url)
        else -> addAddonFor(profileId, url)
    }
    if (!written) return AddonInstallOutcome.ProfileChanged(addon)
    return AddonInstallOutcome.Installed(addon, target)
}
