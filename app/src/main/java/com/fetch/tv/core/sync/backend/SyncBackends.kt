package com.fetch.tv.core.sync.backend

import android.content.Context
import com.fetch.tv.core.auth.AuthManager
import com.fetch.tv.data.website.FetchWebsiteStore
import com.fetch.tv.domain.model.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which sync backend this TV uses, and the one in use right now. Every sync service asks here
 * instead of checking the website pairing and the Supabase sign-in on its own.
 *
 * The choice is made in Settings → Account and kept on the device; choosing ends the other
 * backend's connection there, so normally only the chosen one is connected. When that isn't so
 * the connection wins over the stored choice: a TV signed in some other way (the first-run
 * sign-in, say) syncs with what it signed in to, and with nothing stored the order is the one
 * from before there was a choice, a paired website ahead of a Supabase sign-in.
 */
@Singleton
class SyncBackends @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val websiteStore: FetchWebsiteStore,
    private val authManager: AuthManager,
    private val websiteBackend: dagger.Lazy<FetchWebsiteSyncBackend>,
    private val supabaseBackend: dagger.Lazy<SupabaseSyncBackend>
) {
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

    private val _storedChoice by lazy { MutableStateFlow(readStoredChoice()) }

    /** The choice made in Settings → Account, or null while nobody has made one. */
    val storedChoice: StateFlow<SyncBackendKind?> get() = _storedChoice.asStateFlow()

    /**
     * The backend this TV uses: the stored choice while it is connected or nothing else is,
     * otherwise the connected one. [SyncBackendKind.LOCAL_ONLY] when there is neither.
     */
    val chosen: SyncBackendKind
        get() {
            val stored = _storedChoice.value
            val connected = connectedKinds()
            return when {
                stored != null && stored in connected -> stored
                connected.isNotEmpty() -> connected.first()
                else -> stored ?: SyncBackendKind.LOCAL_ONLY
            }
        }

    /** The chosen backend while it is connected; null when sync is off or not set up yet. */
    val active: SyncBackend?
        get() = backend(chosen)?.takeIf { it.isConnected }

    /** Somewhere to sync to right now. */
    val canSync: Boolean get() = active != null

    val isWebsiteActive: Boolean get() = active?.kind == SyncBackendKind.FETCH_WEBSITE

    val isSupabaseActive: Boolean get() = active?.kind == SyncBackendKind.SUPABASE

    val website: FetchWebsiteSyncBackend get() = websiteBackend.get()

    val supabase: SupabaseSyncBackend get() = supabaseBackend.get()

    fun backend(kind: SyncBackendKind): SyncBackend? = when (kind) {
        SyncBackendKind.LOCAL_ONLY -> null
        SyncBackendKind.FETCH_WEBSITE -> websiteBackend.get()
        SyncBackendKind.SUPABASE -> supabaseBackend.get()
    }

    /** Records the choice. Ending the other backend's connection is up to the caller. */
    fun choose(kind: SyncBackendKind) {
        preferences.edit().putString(KEY_CHOICE, kind.name).apply()
        _storedChoice.value = kind
    }

    private fun connectedKinds(): List<SyncBackendKind> = buildList {
        if (websiteStore.isPaired) add(SyncBackendKind.FETCH_WEBSITE)
        if (authManager.authState.value is AuthState.FullAccount) add(SyncBackendKind.SUPABASE)
    }

    private fun readStoredChoice(): SyncBackendKind? =
        preferences.getString(KEY_CHOICE, null)?.let { stored ->
            SyncBackendKind.entries.firstOrNull { it.name == stored }
        }

    private companion object {
        const val PREFERENCES_NAME = "sync_backend"
        const val KEY_CHOICE = "choice"
    }
}
