package com.fetch.tv.core.livetv

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this profile shows Live TV in the sidebar (#660). Off by default: the entry also needs
 * a Live TV source (an addon with a `tv` catalog, or a media server with channels), so a profile
 * that turns it on with neither still sees nothing.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val enabledKey = booleanPreferencesKey("live_tv_enabled")

    val enabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { id ->
        factory.get(id, FEATURE).data
            .catch { error -> if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error }
            .map { it[enabledKey] ?: DEFAULT_ENABLED }
    }

    suspend fun setEnabled(enabled: Boolean) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { it[enabledKey] = enabled }
    }

    companion object {
        const val FEATURE = "live_tv_settings"
        const val DEFAULT_ENABLED = false
    }
}
