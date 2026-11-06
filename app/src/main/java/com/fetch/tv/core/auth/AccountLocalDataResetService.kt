package com.fetch.tv.core.auth

import android.content.Context
import android.util.Log
import com.fetch.tv.core.sync.androidtv.AndroidTvChannelManager
import com.fetch.tv.core.profile.ProfileScopedCredentialStore
import com.fetch.tv.data.local.ProfileDataStore
import com.fetch.tv.data.local.ProfileDataStoreFactory
import com.fetch.tv.data.local.ProfileLockStateDataStore
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ACCOUNT_RESET_TAG = "AccountDataReset"

/**
 * Local data is only cleared at sign-in when it is known to belong to another
 * account. With no recorded owner (fresh install, data made while signed out, or
 * data already cleared by an explicit sign-out) it is kept.
 */
internal fun shouldResetLocalDataForSignIn(previousOwnerUserId: String?, signedInUserId: String): Boolean =
    !previousOwnerUserId.isNullOrBlank() && previousOwnerUserId != signedInUserId

@Singleton
class AccountLocalDataResetService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profileDataStoreFactory: ProfileDataStoreFactory,
    private val profileDataStore: ProfileDataStore,
    private val profileLockStateDataStore: ProfileLockStateDataStore,
    private val profileRestrictionsDataStore: ProfileRestrictionsDataStore,
    private val screenTimeStore: com.fetch.tv.core.parental.ScreenTimeStore,
    private val credentialStores: Set<@JvmSuppressWildcards ProfileScopedCredentialStore>,
    private val androidTvChannelManager: AndroidTvChannelManager
) {
    /**
     * Clears every account-owned local store. Each step runs even if an earlier one
     * fails; returns false when any step failed, so callers can keep the recorded
     * local-data owner and retry the cleanup on the next sign-in.
     */
    suspend fun clearAfterSignOut(): Boolean = withContext(Dispatchers.IO) {
        listOf(
            attempt("Android TV channel data") { androidTvChannelManager.clearAll() },
            attempt("profile-scoped data") { profileDataStoreFactory.clearProfileScopedData() },
            attempt("profile metadata") { profileDataStore.clearAll() },
            attempt("profile lock states") { profileLockStateDataStore.clearAll() },
            attempt("profile restrictions") { profileRestrictionsDataStore.clearAll() },
            attempt("screen time") { screenTimeStore.clearAll() },
            attempt("profile credentials") {
                credentialStores.forEach(ProfileScopedCredentialStore::clearAllProfiles)
            },
            attempt("account files") { clearAccountFiles() }
        ).all { it }
    }

    private suspend fun attempt(description: String, block: suspend () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(ACCOUNT_RESET_TAG, "Failed to clear $description", error)
            return false
        }
    }

    private fun clearAccountFiles() {
        val files = context.filesDir.listFiles() ?: return
        val undeleted = files.filter { file -> isAccountFile(file) && !file.deleteRecursively() }
        if (undeleted.isNotEmpty()) {
            throw IOException("Could not delete ${undeleted.joinToString { it.name }}")
        }
    }

    private fun isAccountFile(file: File): Boolean {
        // plugin_code* is left behind by versions that still ran plugins.
        return file.name == "cw_enrichment" ||
            file.name == "plugin_code" ||
            file.name.startsWith("plugin_code_p")
    }
}
