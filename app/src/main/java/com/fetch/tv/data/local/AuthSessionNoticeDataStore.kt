package com.fetch.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authSessionNoticeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auth_session_notice_store",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

enum class StartupAuthNotice {
    FETCH,
    TRAKT
}

@Singleton
class AuthSessionNoticeDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val hadFetchAuthKey = booleanPreferencesKey("had_fetch_auth")
    private val fetchExplicitLogoutKey = booleanPreferencesKey("fetch_explicit_logout")
    private val pendingFetchNoticeKey = booleanPreferencesKey("pending_fetch_notice")

    private val hadTraktAuthKey = booleanPreferencesKey("had_trakt_auth")
    private val traktExplicitLogoutKey = booleanPreferencesKey("trakt_explicit_logout")
    private val pendingTraktNoticeKey = booleanPreferencesKey("pending_trakt_notice")

    val pendingNotice: Flow<StartupAuthNotice?> = context.authSessionNoticeDataStore.data.map { preferences ->
        when {
            preferences[pendingFetchNoticeKey] == true -> StartupAuthNotice.FETCH
            preferences[pendingTraktNoticeKey] == true -> StartupAuthNotice.TRAKT
            else -> null
        }
    }

    suspend fun markFetchAuthenticated() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadFetchAuthKey] = true
            preferences[fetchExplicitLogoutKey] = false
            preferences[pendingFetchNoticeKey] = false
        }
    }

    suspend fun markFetchExplicitLogout() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadFetchAuthKey] = false
            preferences[fetchExplicitLogoutKey] = true
            preferences[pendingFetchNoticeKey] = false
        }
    }

    suspend fun markUnexpectedFetchLogoutIfNeeded(): Boolean {
        var marked = false
        context.authSessionNoticeDataStore.edit { preferences ->
            val hadAuth = preferences[hadFetchAuthKey] == true
            val explicitLogout = preferences[fetchExplicitLogoutKey] == true
            if (hadAuth && !explicitLogout) {
                preferences[pendingFetchNoticeKey] = true
                marked = true
            }
            preferences[hadFetchAuthKey] = false
            preferences[fetchExplicitLogoutKey] = false
        }
        return marked
    }

    suspend fun markTraktAuthenticated() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadTraktAuthKey] = true
            preferences[traktExplicitLogoutKey] = false
            preferences[pendingTraktNoticeKey] = false
        }
    }

    suspend fun markTraktExplicitLogout() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadTraktAuthKey] = false
            preferences[traktExplicitLogoutKey] = true
            preferences[pendingTraktNoticeKey] = false
        }
    }

    suspend fun markUnexpectedTraktLogoutIfNeeded(): Boolean {
        var marked = false
        context.authSessionNoticeDataStore.edit { preferences ->
            val hadAuth = preferences[hadTraktAuthKey] == true
            val explicitLogout = preferences[traktExplicitLogoutKey] == true
            if (hadAuth && !explicitLogout) {
                preferences[pendingTraktNoticeKey] = true
                marked = true
            }
            preferences[hadTraktAuthKey] = false
            preferences[traktExplicitLogoutKey] = false
        }
        return marked
    }

    suspend fun markTraktReconnectRequired() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[pendingTraktNoticeKey] = true
            preferences[hadTraktAuthKey] = false
            preferences[traktExplicitLogoutKey] = false
        }
    }

    suspend fun consumeNotice(notice: StartupAuthNotice) {
        context.authSessionNoticeDataStore.edit { preferences ->
            when (notice) {
                StartupAuthNotice.FETCH -> preferences[pendingFetchNoticeKey] = false
                StartupAuthNotice.TRAKT -> preferences[pendingTraktNoticeKey] = false
            }
        }
    }
}
