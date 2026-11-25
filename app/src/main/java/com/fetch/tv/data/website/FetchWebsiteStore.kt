package com.fetch.tv.data.website

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A TV signed in to a Fetch website: where the API is and the token this TV was issued. */
data class FetchWebsiteSession(
    val serverUrl: String,
    val deviceToken: String,
    val deviceName: String
)

/**
 * The website sign-in, kept on this device only. The token is this TV's own (issued by
 * pairing, revocable from the website), never the website's shared API token.
 *
 * [signedOutByWebsite] is set when the website answers the token with a bearer 401, which is
 * how a revoked device finds out; the Account screen says so until the TV pairs again.
 */
@Singleton
class FetchWebsiteStore internal constructor(
    private val prefs: android.content.SharedPreferences
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<FetchWebsiteSession?> = _session.asStateFlow()

    private val _signedOutByWebsite = MutableStateFlow(prefs.getBoolean(KEY_SIGNED_OUT, false))
    val signedOutByWebsite: StateFlow<Boolean> = _signedOutByWebsite.asStateFlow()

    val isPaired: Boolean get() = _session.value != null

    /**
     * Set when this TV and its Fetch server can't sync until one of them is updated (#639); the
     * Account screen says which. Not kept on disk: the next sync finds out again.
     */
    private val _versionMismatch = MutableStateFlow<WebsiteVersionMismatch?>(null)
    val versionMismatch: StateFlow<WebsiteVersionMismatch?> = _versionMismatch.asStateFlow()

    fun setVersionMismatch(mismatch: WebsiteVersionMismatch?) {
        _versionMismatch.value = mismatch
    }

    /** The last server address used, so pairing again doesn't mean typing it again. */
    val lastServerUrl: String? get() = prefs.getString(KEY_SERVER_URL, null)

    fun save(session: FetchWebsiteSession) {
        clearSettingsBases()
        prefs.edit {
            putString(KEY_SERVER_URL, session.serverUrl)
            putString(KEY_TOKEN, session.deviceToken)
            putString(KEY_DEVICE_NAME, session.deviceName)
            putBoolean(KEY_SIGNED_OUT, false)
            putBoolean(KEY_INITIAL_PROFILE_PUSH, true)
        }
        _signedOutByWebsite.value = false
        _versionMismatch.value = null
        _session.value = session
    }

    /**
     * True from pairing until the first sync has added this TV's profiles to the website, so
     * that pass runs once per pairing even if the app is closed before it does.
     */
    val needsInitialProfilePush: Boolean get() = prefs.getBoolean(KEY_INITIAL_PROFILE_PUSH, false)

    fun markInitialProfilePushDone() {
        prefs.edit { putBoolean(KEY_INITIAL_PROFILE_PUSH, false) }
    }

    /**
     * Grows with each profile change made on this TV (added, renamed, a parental limit) and is
     * cleared once a push has carried it; 0 when the website has everything (#617). Kept on disk,
     * so a change made offline or just before the app closed isn't pulled over on the next sync.
     */
    val profilesRevision: Long get() = prefs.getLong(KEY_PROFILES_REVISION, 0L)

    @Synchronized
    fun markProfilesChanged() {
        prefs.edit(commit = true) { putLong(KEY_PROFILES_REVISION, profilesRevision + 1L) }
    }

    /** A push that read the profiles at [revision] got through; a later change stays pending. */
    @Synchronized
    fun markProfilesSent(revision: Long) {
        if (revision != 0L && profilesRevision == revision) prefs.edit(commit = true) { remove(KEY_PROFILES_REVISION) }
    }

    /** Profiles deleted on this TV that the website still has, by slot (#617). */
    val pendingProfileDeletes: Set<Int>
        get() = prefs.getStringSet(KEY_PENDING_PROFILE_DELETES, emptySet()).orEmpty().mapNotNullTo(sortedSetOf()) { it.toIntOrNull() }

    @Synchronized
    fun addPendingProfileDelete(slot: Int) {
        val slots = pendingProfileDeletes + slot
        prefs.edit(commit = true) { putStringSet(KEY_PENDING_PROFILE_DELETES, slots.mapTo(HashSet()) { it.toString() }) }
    }

    @Synchronized
    fun removePendingProfileDelete(slot: Int) {
        val slots = pendingProfileDeletes - slot
        prefs.edit(commit = true) { putStringSet(KEY_PENDING_PROFILE_DELETES, slots.mapTo(HashSet()) { it.toString() }) }
    }

    /** Signing out on the TV: forget the token, keep the address for next time. */
    fun clear() {
        prefs.edit { remove(KEY_TOKEN).remove(KEY_DEVICE_NAME).remove(KEY_PROFILES_REVISION).remove(KEY_PENDING_PROFILE_DELETES) }
        clearSettingsBases()
        _versionMismatch.value = null
        _session.value = null
    }

    /** The website revoked this TV. Same as [clear], but remembered so the TV can say why. */
    fun markSignedOutByWebsite() {
        prefs.edit {
            remove(KEY_TOKEN).remove(KEY_DEVICE_NAME).remove(KEY_PROFILES_REVISION).remove(KEY_PENDING_PROFILE_DELETES)
                .putBoolean(KEY_SIGNED_OUT, true)
        }
        clearSettingsBases()
        _signedOutByWebsite.value = true
        _session.value = null
    }

    fun dismissSignedOutNotice() {
        prefs.edit { putBoolean(KEY_SIGNED_OUT, false) }
        _signedOutByWebsite.value = false
    }

    /**
     * The settings blob this TV and the website last agreed on for a website profile: the base
     * of the three-way merge in [FetchWebsiteTvMappings.mergeSettings]. Kept across restarts, so
     * a change made on the website while the TV was off is told apart from one made here.
     */
    fun settingsBase(websiteProfileId: String): String? = prefs.getString(KEY_SETTINGS_BASE_PREFIX + websiteProfileId, null)

    fun saveSettingsBase(websiteProfileId: String, blob: String) {
        prefs.edit { putString(KEY_SETTINGS_BASE_PREFIX + websiteProfileId, blob) }
    }

    /**
     * The collections this TV and the website last agreed on for a website profile, as
     * `{"version": n, "collections": [...]}`: the version a push names, and the base of the merge
     * in [FetchWebsiteCollectionsMerge] when the website has moved on from it (#669).
     */
    fun collectionsBase(websiteProfileId: String): String? =
        prefs.getString(KEY_COLLECTIONS_BASE_PREFIX + websiteProfileId, null)

    fun saveCollectionsBase(websiteProfileId: String, blob: String) {
        prefs.edit { putString(KEY_COLLECTIONS_BASE_PREFIX + websiteProfileId, blob) }
    }

    /** A new pairing, or the website a TV left, starts from the TV's own settings and collections again. */
    private fun clearSettingsBases() {
        val keys = prefs.all.keys.filter {
            it.startsWith(KEY_SETTINGS_BASE_PREFIX) || it.startsWith(KEY_COLLECTIONS_BASE_PREFIX)
        }
        prefs.edit { keys.forEach { remove(it) } }
    }

    private fun readSession(): FetchWebsiteSession? {
        val url = prefs.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return FetchWebsiteSession(url, token, prefs.getString(KEY_DEVICE_NAME, null).orEmpty())
    }

    private companion object {
        const val PREFS_NAME = "fetch_website"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "device_token"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_SIGNED_OUT = "signed_out_by_website"
        const val KEY_INITIAL_PROFILE_PUSH = "initial_profile_push"
        const val KEY_SETTINGS_BASE_PREFIX = "settings_base_"
        const val KEY_COLLECTIONS_BASE_PREFIX = "collections_base_"
        const val KEY_PROFILES_REVISION = "profiles_revision"
        const val KEY_PENDING_PROFILE_DELETES = "pending_profile_deletes"
    }
}
