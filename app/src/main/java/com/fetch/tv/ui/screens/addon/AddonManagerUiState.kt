package com.fetch.tv.ui.screens.addon

import android.graphics.Bitmap
import com.fetch.tv.domain.model.Addon

data class AddonManagerUiState(
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val installUrl: String = "",
    val installedAddons: List<Addon> = emptyList(),
    /** The installed-addon list couldn't be read; the screen offers a retry instead of the empty state. */
    val installedAddonsLoadFailed: Boolean = false,
    /** Install-field validation and install failures only; other failures go through [transientMessage]. */
    val error: String? = null,
    val transientMessage: String? = null,
    val transientMessageIsError: Boolean = false,
    // QR mode
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    // Pending change from phone
    val pendingChange: PendingChangeInfo? = null,
    val configureTarget: AddonConfigureTarget? = null
)

data class PendingChangeInfo(
    val changeId: String,
    val proposedUrls: List<String>,
    val proposedCatalogOrderKeys: List<String> = emptyList(),
    val proposedDisabledCatalogKeys: List<String> = emptyList(),
    val addedUrls: List<String>,
    val removedUrls: List<String>,
    val catalogsReordered: Boolean = false,
    val disabledCatalogNames: List<String> = emptyList(),
    val enabledCatalogNames: List<String> = emptyList(),
    val addedNames: Map<String, String> = emptyMap(),
    val removedNames: Map<String, String> = emptyMap(),
    val collectionsChanged: Boolean = false,
    val proposedCollectionsJson: String? = null,
    val proposedCollectionCount: Int = 0,
    val proposedDisabledCollectionKeys: List<String> = emptyList(),
    val proposedFollowAddonsOrder: Boolean? = null,
    val isApplying: Boolean = false
)

/** The addon being configured from the TV (Configure action, or an install that needs setup). */
data class AddonConfigureTarget(
    val name: String,
    /** Installed entry the configured URL replaces; null when installing a new addon. */
    val replacingUrl: String?,
    val configureUrl: String,
    val configureQr: Bitmap?,
    /** Local "Send to TV" page; null when the local server could not start. */
    val sendToTvUrl: String?,
    val sendToTvQr: Bitmap?,
    /** Link the phone sent, waiting for the viewer to confirm it on the TV. */
    val receivedUrl: String? = null,
    val isInstalling: Boolean = false
)
