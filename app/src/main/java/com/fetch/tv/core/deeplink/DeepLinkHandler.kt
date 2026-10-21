package com.fetch.tv.core.deeplink

import android.content.Context
import com.fetch.tv.R
import com.fetch.tv.core.addon.AddonInstallOutcome
import com.fetch.tv.core.addon.installOrReplace
import com.fetch.tv.core.addon.normalizeAddonInstallUrl
import com.fetch.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class DeepLinkHandler @Inject constructor(
    private val addonRepository: AddonRepository,
    @ApplicationContext private val context: Context
) {
    suspend fun installAddon(manifestUrl: String): DeepLinkInstallResult {
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
