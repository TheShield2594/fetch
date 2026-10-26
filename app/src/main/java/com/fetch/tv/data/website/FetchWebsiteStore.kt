package com.fetch.tv.data.website

import android.content.Context
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

    /** The last server address used, so pairing again doesn't mean typing it again. */
    val lastServerUrl: String? get() = prefs.getString(KEY_SERVER_URL, null)

    fun save(session: FetchWebsiteSession) {
        prefs.edit()
            .putString(KEY_SERVER_URL, session.serverUrl)
            .putString(KEY_TOKEN, session.deviceToken)
            .putString(KEY_DEVICE_NAME, session.deviceName)
            .putBoolean(KEY_SIGNED_OUT, false)
            .apply()
        _signedOutByWebsite.value = false
        _session.value = session
    }

    /** Signing out on the TV: forget the token, keep the address for next time. */
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_DEVICE_NAME).apply()
        _session.value = null
    }

    /** The website revoked this TV. Same as [clear], but remembered so the TV can say why. */
    fun markSignedOutByWebsite() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_DEVICE_NAME).putBoolean(KEY_SIGNED_OUT, true).apply()
        _signedOutByWebsite.value = true
        _session.value = null
    }

    fun dismissSignedOutNotice() {
        prefs.edit().putBoolean(KEY_SIGNED_OUT, false).apply()
        _signedOutByWebsite.value = false
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
    }
}
