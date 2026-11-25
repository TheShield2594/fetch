package com.fetch.tv.data.website

import kotlinx.serialization.Serializable

/**
 * The TV and the Fetch server check each other's version when the TV syncs (#639). See
 * `web/docs/compatibility.md`.
 *
 * The server and the TV are on separate version lines, so the TV compares integers: the
 * server's `apiVersion` (from `GET /version`) against [MIN_SERVER_API_VERSION]. The other way
 * round, the TV sends its version in [APP_VERSION_HEADER] and the server answers a TV it no
 * longer serves with 426 and [TV_APP_TOO_OLD].
 */
object FetchWebsiteCompatibility {
    /** The lowest server `apiVersion` this TV build syncs with. Raise with a breaking server change. */
    const val MIN_SERVER_API_VERSION = 1

    const val APP_VERSION_HEADER = "X-Fetch-App-Version"

    /** The server's `code` for a TV app below its `minTvApp`. */
    const val TV_APP_TOO_OLD = "tv_app_too_old"

    /** The mismatch, if any, for a server that answered `GET /version` with [version]. */
    fun check(version: WebsiteServerVersion): WebsiteVersionMismatch? =
        if (version.apiVersion < MIN_SERVER_API_VERSION) {
            WebsiteVersionMismatch.ServerTooOld(version.server)
        } else {
            null
        }
}

/** What `GET /version` says. A server too old to have the route reads as [LEGACY]. */
@Serializable
data class WebsiteServerVersion(
    val server: String? = null,
    val apiVersion: Int = 0,
    val minTvApp: String? = null,
    val features: List<String> = emptyList()
) {
    companion object {
        val LEGACY = WebsiteServerVersion()
    }
}

/** Why this TV and its Fetch server can't sync until one of them is updated. */
sealed interface WebsiteVersionMismatch {
    /** The server is older than this TV needs. [serverVersion] is null when it didn't say. */
    data class ServerTooOld(val serverVersion: String?) : WebsiteVersionMismatch

    /** The server no longer serves this TV's app version; [minTvApp] is the oldest it does. */
    data class TvAppTooOld(val minTvApp: String?) : WebsiteVersionMismatch
}

/**
 * Thrown by a sync that stopped because of [mismatch]; its message is for the website's TV list.
 * A [FetchWebsiteException] (426), so callers that handle the website refusing a call handle this too.
 */
class FetchWebsiteIncompatibleException(val mismatch: WebsiteVersionMismatch) : FetchWebsiteException(
    message = when (mismatch) {
        is WebsiteVersionMismatch.ServerTooOld ->
            "This TV's Fetch app needs a newer Fetch server" +
                (mismatch.serverVersion?.let { " (this one is $it)" } ?: "") + ". Update the server."
        is WebsiteVersionMismatch.TvAppTooOld ->
            "This server needs Fetch " + (mismatch.minTvApp ?: "a newer version") + " or newer on the TV. Update the app."
    },
    status = 426,
    code = (mismatch as? WebsiteVersionMismatch.TvAppTooOld)?.let { FetchWebsiteCompatibility.TV_APP_TOO_OLD }
)
