package com.fetch.tv.core.addon

import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.repository.AddonRepository
import kotlinx.coroutines.flow.first

/** Whether the addon has its own `/configure` page (Stremio `behaviorHints.configurable`). */
val Addon.isConfigurable: Boolean
    get() = behaviorHints?.configurable == true || behaviorHints?.configurationRequired == true

/** Whether the addon refuses to work until it is configured (`behaviorHints.configurationRequired`). */
val Addon.needsConfiguration: Boolean
    get() = behaviorHints?.configurationRequired == true

/**
 * The addon's configuration page: `<transportUrl base>/configure`. Query strings belong to the
 * manifest request, not the page, so they are dropped.
 */
fun addonConfigureUrl(baseUrl: String): String {
    val path = baseUrl.trim().substringBefore('?').trimEnd('/')
    val base = if (path.endsWith("/manifest.json", ignoreCase = true)) {
        path.dropLast("/manifest.json".length).trimEnd('/')
    } else path
    return "$base/configure"
}

/**
 * Normalizes what a user pastes or an addon hands back (`stremio://…/manifest.json`,
 * `https://…/manifest.json`, a bare base URL) into an http(s) base URL, or null.
 */
fun normalizeAddonInstallUrl(input: String): String? {
    var trimmed = input.trim()
    if (trimmed.startsWith("stremio://", ignoreCase = true)) {
        trimmed = "https://" + trimmed.substring("stremio://".length)
    }
    if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        return null
    }
    val queryStart = trimmed.indexOf('?')
    val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
    val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
    val cleanPath = if (path.endsWith("/manifest.json", ignoreCase = true)) {
        path.dropLast("/manifest.json".length)
    } else path
    return cleanPath.trimEnd('/') + query
}

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
