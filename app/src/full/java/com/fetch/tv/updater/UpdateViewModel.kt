package com.fetch.tv.updater

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.BuildConfig
import com.fetch.tv.R
import com.fetch.tv.updater.model.AppUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showBanner: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null,
    val updateBannerEnabled: Boolean = true,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()
    private var updateCheckJob: Job? = null
    // What the running check was asked for; a request made while it runs joins it.
    private var checkForced = false
    private var checkShowsFeedback = false
    private var downloadJob: Job? = null

    private val updatesDir: File
        get() = File(context.cacheDir, UpdateCacheCleaner.DIRECTORY)

    init {
        viewModelScope.launch {
            // APKs from earlier sessions: either installed already or orphaned when the process
            // died before install. Nothing points at them any more.
            withContext(Dispatchers.IO) { UpdateCacheCleaner.clean(updatesDir) }
            val enabled = updatePreferences.updateBannerEnabled.first()
            val channel = updatePreferences.getOrInitializeUpdateChannel()
            _uiState.update {
                it.copy(
                    updateBannerEnabled = enabled,
                    updateChannel = channel
                )
            }
            if (enabled && !BuildConfig.IS_DEBUG_BUILD) {
                checkForUpdates(force = false, showNoUpdateFeedback = false)
            }
        }
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!force && !_uiState.value.updateBannerEnabled) return

        // Pressing "Check for updates" again while a check runs joins it instead of starting over.
        if (updateCheckJob?.isActive == true) {
            checkForced = checkForced || force
            checkShowsFeedback = checkShowsFeedback || showNoUpdateFeedback
            return
        }
        checkForced = force
        checkShowsFeedback = showNoUpdateFeedback
        updateCheckJob = viewModelScope.launch {
            val channel = _uiState.value.updateChannel
            _uiState.update {
                it.copy(
                    isChecking = true,
                    showBanner = false,
                    showUnknownSourcesDialog = false,
                    errorMessage = null,
                    feedbackMessage = null
                )
            }

            val dismissedTag = updatePreferences.ignoredTag.first()
            val result = updateRepository.getLatestUpdate(channel)

            result
                .onSuccess { update ->
                    val remoteNewer = VersionUtils.isRemoteNewer(update.tag, BuildConfig.VERSION_NAME)
                    val shouldShow = UpdateBannerPolicy.shouldShow(
                        isRemoteNewer = remoteNewer,
                        force = checkForced,
                        bannerEnabled = _uiState.value.updateBannerEnabled,
                        dismissedTag = dismissedTag,
                        updateTag = update.tag
                    )

                    _uiState.update { state ->
                        state.copy(
                            isChecking = false,
                            update = update.takeIf { remoteNewer },
                            isUpdateAvailable = remoteNewer,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = state.downloadedApkPath.takeIf {
                                remoteNewer && state.update?.tag == update.tag
                            },
                            showBanner = shouldShow,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                            feedbackMessage = if (checkShowsFeedback && !remoteNewer) {
                                noUpdateFeedback(channel)
                            } else {
                                null
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            update = null,
                            isUpdateAvailable = false,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            showBanner = false,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                            feedbackMessage = if (checkShowsFeedback) {
                                if (error is NoEligibleUpdateException) {
                                    noUpdateFeedback(channel)
                                } else {
                                    // The raw message (an HTTP code, a parser error) means
                                    // nothing on a TV; it goes to the log instead.
                                    Log.w(TAG, "Update check failed", error)
                                    context.getString(R.string.update_error_check_failed)
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
        }
    }

    private fun noUpdateFeedback(channel: UpdateChannel): String =
        if (channel == UpdateChannel.STABLE && VersionUtils.isPrerelease(BuildConfig.VERSION_NAME)) {
            context.getString(R.string.update_waiting_for_stable)
        } else {
            context.getString(R.string.update_latest_version)
        }

    fun dismissBanner() {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                showBanner = false,
                showUnknownSourcesDialog = false,
                errorMessage = null
            )
        }
        val tag = state.update?.tag
        if (tag != null) {
            viewModelScope.launch {
                updatePreferences.setIgnoredTag(tag)
            }
        }
    }

    fun dismissUnknownSourcesDialog() {
        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
    }

    fun consumeFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    fun setUpdateBannerEnabled(enabled: Boolean) {
        val changed = _uiState.value.updateBannerEnabled != enabled
        _uiState.update {
            it.copy(
                updateBannerEnabled = enabled,
                showBanner = if (enabled) it.showBanner else false,
                showUnknownSourcesDialog = if (enabled) it.showUnknownSourcesDialog else false
            )
        }
        viewModelScope.launch {
            updatePreferences.setUpdateBannerEnabled(enabled)
            if (enabled && changed && !BuildConfig.IS_DEBUG_BUILD) {
                checkForUpdates(force = false, showNoUpdateFeedback = false)
            }
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        val state = _uiState.value
        if (state.updateChannel == channel) return

        updateCheckJob?.cancel()
        downloadJob?.cancel()
        state.downloadedApkPath?.let { File(it).delete() }
        _uiState.update {
            it.copy(
                updateChannel = channel,
                isChecking = false,
                update = null,
                isUpdateAvailable = false,
                isDownloading = false,
                downloadProgress = null,
                downloadedApkPath = null,
                showBanner = false,
                showUnknownSourcesDialog = false,
                errorMessage = null,
                feedbackMessage = null
            )
        }
        viewModelScope.launch {
            updatePreferences.setUpdateChannel(channel)
            if (!BuildConfig.IS_DEBUG_BUILD) {
                checkForUpdates(force = true, showNoUpdateFeedback = false)
            }
        }
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return
        // A second download would clean the first one's file out from under it.
        if (_uiState.value.isDownloading) return

        _uiState.update {
            it.copy(
                isDownloading = true,
                downloadProgress = 0f,
                errorMessage = null
            )
        }
        val previous = downloadJob
        downloadJob = viewModelScope.launch {
            // A cancelled download may still be finishing its last read; let it clean up its
            // partial file before this one writes to the same path.
            previous?.cancelAndJoin()

            val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destination = File(updatesDir, safeName)
            val result = withContext(Dispatchers.IO) {
                // Only one update APK is ever kept: drop earlier downloads before this one.
                UpdateCacheCleaner.clean(updatesDir)
                apkDownloader.download(update.assetUrl, destination) { downloaded, total ->
                    val progress = if (total != null && total > 0) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    _uiState.update { it.copy(downloadProgress = progress) }
                }
            }

            result
                .onSuccess { file ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = file.absolutePath,
                            errorMessage = null
                        )
                    }
                    installUpdateOrRequestPermission()
                }
                .onFailure { error ->
                    Log.w(TAG, "Update download failed", error)
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            errorMessage = context.getString(R.string.update_error_download_failed),
                            showBanner = true
                        )
                    }
                }
        }
    }

    /** Stops a running download; the banner goes back to offering the download. */
    fun cancelDownload() {
        if (!_uiState.value.isDownloading) return
        // The downloader stops at its next read and deletes the partial file.
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update {
            it.copy(
                isDownloading = false,
                downloadProgress = null,
                downloadedApkPath = null,
                errorMessage = null
            )
        }
    }

    fun installUpdateOrRequestPermission() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            _uiState.update {
                it.copy(
                    errorMessage = context.getString(R.string.update_error_apk_missing),
                    showBanner = true
                )
            }
            return
        }

        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(showUnknownSourcesDialog = true, showBanner = true) }
            return
        }

        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
        if (!ApkInstaller.launchInstall(context, apkFile)) {
            _uiState.update {
                it.copy(
                    errorMessage = context.getString(R.string.update_error_installer_unavailable),
                    showBanner = true
                )
            }
        }
    }

    fun openUnknownSourcesSettings() {
        if (!ApkInstaller.openUnknownSourcesSettings(context)) {
            _uiState.update {
                it.copy(
                    showUnknownSourcesDialog = false,
                    errorMessage = context.getString(R.string.update_error_unknown_sources_unavailable),
                    showBanner = true
                )
            }
        }
    }
}

private const val TAG = "UpdateViewModel"
