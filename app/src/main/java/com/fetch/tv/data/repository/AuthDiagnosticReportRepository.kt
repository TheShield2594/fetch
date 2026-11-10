package com.fetch.tv.data.repository

import android.content.Context
import com.fetch.tv.BuildConfig
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.remote.api.AuthDiagnosticReportApi
import com.fetch.tv.data.remote.dto.AuthDiagnosticReportRequestDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AuthDiagnosticReportRepository @Inject constructor(
    private val authDiagnosticReportApi: AuthDiagnosticReportApi,
    @ApplicationContext context: Context,
    private val playerSettingsDataStore: Provider<PlayerSettingsDataStore>
) {
    private val mutex = Mutex()

    /** Where older builds queued reports that failed to upload. Deleted, never read. */
    private val legacyQueueFile = File(context.filesDir, "auth_diagnostics_queue.jsonl")

    /**
     * Sends one sign-in diagnostics report, only when the build has a report endpoint and the
     * active profile has turned on Playback issue reports (Settings → Advanced). Nothing is kept
     * on this device: a report that can't be sent now is dropped, so it can never be sent later
     * under another profile's choice or after reports are turned off.
     */
    suspend fun submit(report: AuthDiagnosticReportRequestDto): Result<String> = mutex.withLock {
        deleteLegacyQueue()
        if (!isUploadAllowed()) return@withLock Result.success("disabled")
        return@withLock try {
            Result.success(upload(report))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun isUploadAllowed(): Boolean {
        if (BuildConfig.PLAYBACK_REPORTS_BASE_URL.isBlank()) return false
        return runCatching {
            playerSettingsDataStore.get().playerSettings.first().playbackIssueReportsEnabled
        }.getOrDefault(false)
    }

    private suspend fun deleteLegacyQueue() {
        withContext(Dispatchers.IO) {
            if (legacyQueueFile.exists()) legacyQueueFile.delete()
        }
    }

    private suspend fun upload(report: AuthDiagnosticReportRequestDto): String {
        val response = authDiagnosticReportApi.createAuthDiagnosticReport(report)
        if (!response.isSuccessful) {
            error("Auth diagnostics upload failed: HTTP ${response.code()}")
        }
        val body = response.body()
        return body?.reportId?.trim()?.takeIf { it.isNotBlank() }
            ?: body?.id?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Auth diagnostics upload failed: missing report id")
    }
}
