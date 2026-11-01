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
}

/**
 * Installs [url], or swaps it in for an existing entry:
 * - [replacing] names the entry being configured (the Configure flow on a card);
 * - otherwise an installed, still-unconfigured copy of the same addon (same manifest id) is
 *   replaced, so configuring it does not leave a broken duplicate behind. Other installed
 *   copies stay: people keep several configurations of one addon on purpose.
 *
 * A manifest that still says `configurationRequired` is not installed.
 */
suspend fun AddonRepository.installOrReplace(url: String, replacing: String? = null): AddonInstallOutcome {
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
    if (target != null) {
        replaceAddonUrl(target, url)
    } else {
        addAddon(url)
    }
    return AddonInstallOutcome.Installed(addon, target)
}
