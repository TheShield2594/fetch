package com.fetch.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.fetch.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Public IntroDB instance used when neither the build nor the user names another server. */
const val DEFAULT_INTRODB_API_URL = "https://api.introdb.app/"

/**
 * Normalizes a user-entered IntroDB server into a Retrofit-style base URL ending in `/`.
 * Returns null for anything that is not an http(s) URL.
 */
fun normalizeIntroDbUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
    return withScheme.trimEnd('/') + "/"
}

/**
 * Device-wide settings for community skip markers (IntroDB). Not tied to a profile: the
 * marker server and the submission key describe this device's setup, not a viewer's taste.
 */
@Singleton
class SkipMarkerSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            androidx.datastore.preferences.core.emptyPreferences()
        }
    ) {
        context.preferencesDataStoreFile("skip_marker_settings")
    }

    private val introDbUrlKey = stringPreferencesKey("introdb_url")
    private val introDbApiKeyKey = stringPreferencesKey("introdb_api_key")

    /** The user's server override; blank means "use the default". */
    val introDbUrlOverride: Flow<String> = store.data.map { it[introDbUrlKey].orEmpty() }

    /** The server marker lookups go to: the user's override, else the build's, else the public one. */
    val introDbBaseUrl: Flow<String> = introDbUrlOverride.map { override ->
        normalizeIntroDbUrl(override)
            ?: normalizeIntroDbUrl(BuildConfig.INTRODB_API_URL)
            ?: DEFAULT_INTRODB_API_URL
    }

    /** IntroDB API key (`idb_…`) used to submit markers; blank disables submitting. */
    val introDbApiKey: Flow<String> = store.data.map { it[introDbApiKeyKey].orEmpty() }

    suspend fun setIntroDbUrlOverride(url: String) {
        store.edit { it[introDbUrlKey] = url.trim() }
    }

    suspend fun setIntroDbApiKey(key: String) {
        store.edit { it[introDbApiKeyKey] = key.trim() }
    }
}
