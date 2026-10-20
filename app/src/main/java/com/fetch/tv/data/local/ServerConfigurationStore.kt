package com.fetch.tv.data.local

import android.content.Context
import com.fetch.tv.BuildConfig
import com.fetch.tv.domain.model.ServerCapabilities
import com.fetch.tv.domain.model.ServerConfiguration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerConfigurationStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun loadActive(): ServerConfiguration {
        if (!BuildConfig.FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED) return officialConfiguration()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_CUSTOM_ENABLED, false)) return officialConfiguration()
        val backendUrl = preferences.getString(KEY_BACKEND_URL, null)?.trim().orEmpty()
        val publishableKey = preferences.getString(KEY_PUBLISHABLE_KEY, null)?.trim().orEmpty()
        val emailPasswordAuth = preferences.getBoolean(KEY_EMAIL_PASSWORD_AUTH, false)
        val tvLogin = preferences.getBoolean(KEY_TV_LOGIN, false)
        if (backendUrl.isBlank() || publishableKey.isBlank() || (!emailPasswordAuth && !tvLogin)) {
            return officialConfiguration()
        }
        return ServerConfiguration(
            backendUrl = backendUrl,
            publishableKey = publishableKey,
            capabilities = ServerCapabilities(
                emailPasswordAuth = emailPasswordAuth,
                tvLogin = tvLogin,
                watchParty = preferences.getBoolean(KEY_WATCH_PARTY, false)
            ),
            isCustom = true,
            discoveryUrl = preferences.getString(KEY_DISCOVERY_URL, null),
            tvLoginWebBaseUrl = "$backendUrl/tv-login",
            deviceLoginWebBaseUrl = "$backendUrl/link",
            avatarPublicBaseUrl = "$backendUrl/storage/v1/object/public/avatars"
        )
    }

    fun saveCustom(configuration: ServerConfiguration): Boolean {
        if (!BuildConfig.FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED) return false
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CUSTOM_ENABLED, true)
            .putString(KEY_BACKEND_URL, configuration.backendUrl)
            .putString(KEY_PUBLISHABLE_KEY, configuration.publishableKey)
            .putBoolean(KEY_EMAIL_PASSWORD_AUTH, configuration.capabilities.emailPasswordAuth)
            .putBoolean(KEY_TV_LOGIN, configuration.capabilities.tvLogin)
            .putBoolean(KEY_WATCH_PARTY, configuration.capabilities.watchParty)
            .putString(KEY_DISCOVERY_URL, configuration.discoveryUrl)
            .commit()
    }

    fun useOfficial(): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

    /**
     * The built-in default. Fetch ships with no hosted backend, so unless a builder bakes a
     * self-hosted server into local.properties this is local-only mode: no account, no sync.
     */
    private fun officialConfiguration(): ServerConfiguration {
        val bakedIn = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
        return ServerConfiguration(
        backendUrl = BuildConfig.SUPABASE_URL.trimEnd('/').ifBlank { LOCAL_ONLY_BACKEND_URL },
        publishableKey = BuildConfig.SUPABASE_ANON_KEY,
        capabilities = ServerCapabilities(
            emailPasswordAuth = false,
            tvLogin = bakedIn,
            watchParty = bakedIn
        ),
        isCustom = false,
        fallbackBackendUrl = BuildConfig.SUPABASE_FALLBACK_URL.trim().trimEnd('/').takeIf { it.isNotBlank() },
        tvLoginWebBaseUrl = BuildConfig.TV_LOGIN_WEB_BASE_URL,
        deviceLoginWebBaseUrl = BuildConfig.DEVICE_LOGIN_WEB_BASE_URL,
        avatarPublicBaseUrl = BuildConfig.AVATAR_PUBLIC_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
        )
    }

    private companion object {
        /** Never contacted: the client needs a syntactically valid URL even when sync is off. */
        const val LOCAL_ONLY_BACKEND_URL = "https://sync.invalid"
        const val PREFERENCES_NAME = "server_configuration"
        const val KEY_CUSTOM_ENABLED = "custom_enabled"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_PUBLISHABLE_KEY = "publishable_key"
        const val KEY_EMAIL_PASSWORD_AUTH = "email_password_auth"
        const val KEY_TV_LOGIN = "tv_login"
        const val KEY_WATCH_PARTY = "watch_party"
        const val KEY_DISCOVERY_URL = "discovery_url"
    }
}
