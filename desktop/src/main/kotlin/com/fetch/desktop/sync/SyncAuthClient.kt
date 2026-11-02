package com.fetch.desktop.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.Base64

/**
 * Sign-in against a sync server, mirroring the TV app's `AuthManager`: email + password and sign-up
 * through GoTrue (`/auth/v1`), the device (QR / code) sign-in through PostgREST RPCs plus the
 * `tv-logins-exchange` edge function, token refresh and sign-out. Auth calls send the anon key as
 * `apikey` and, like the TV app, no `Authorization` header until there is a session.
 *
 * Errors: a rejected sign-in or refresh throws [SyncAuthException]; other HTTP failures throw
 * [SyncException]; network failures surface as `IOException`.
 */
class SyncAuthClient(
    private val http: OkHttpClient,
    val config: SyncServerConfig,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val base = config.baseUrl

    /** POST /auth/v1/token?grant_type=password. */
    suspend fun signIn(email: String, password: String): SyncSession {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        val result = post("/auth/v1/token?grant_type=password", body.toString())
        if (result.code in 400..499) throw SyncAuthException(result.errorText() ?: "Wrong email or password", result.code)
        if (!result.ok) throw result.failure("sign-in")
        return toSession(result)
    }

    /**
     * POST /auth/v1/signup. Returns the session, or null when the server wants the address
     * confirmed first (it then answers with the user and no tokens).
     */
    suspend fun signUp(email: String, password: String): SyncSession? {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        val result = post("/auth/v1/signup", body.toString())
        if (!result.ok) {
            val message = result.errorText() ?: "Sign-up failed (HTTP ${result.code})"
            throw if (result.code in 400..499) SyncAuthException(message, result.code) else SyncException(message, result.code)
        }
        val dto = syncJson.decodeFromString<TokenDto>(result.body)
        if (dto.accessToken.isNullOrBlank() || dto.refreshToken.isNullOrBlank()) return null
        return toSession(dto)
    }

    /**
     * POST /auth/v1/token?grant_type=refresh_token. A 4xx means the refresh token is no longer
     * valid ([SyncAuthException]: sign in again); a 5xx is transient ([SyncException]: keep the session).
     */
    suspend fun refresh(session: SyncSession): SyncSession {
        val body = buildJsonObject { put("refresh_token", session.refreshToken) }
        val result = post("/auth/v1/token?grant_type=refresh_token", body.toString())
        if (result.code in 400..499) {
            throw SyncAuthException(result.errorText() ?: "The sync session has expired; sign in again", result.code)
        }
        if (!result.ok) throw result.failure("token refresh")
        val dto = syncJson.decodeFromString<TokenDto>(result.body)
        return toSession(dto, fallback = session)
    }

    /** POST /auth/v1/logout?scope=local, best effort: a failure still leaves the caller signed out locally. */
    suspend fun signOut(session: SyncSession) {
        runCatching { post("/auth/v1/logout?scope=local", "{}", session.accessToken) }
    }

    /**
     * Starts a device sign-in (RPC `start_device_login_session`), falling back to the older
     * `start_tv_login_session` when the server does not have it, as the TV app does.
     */
    suspend fun startDeviceLogin(deviceName: String? = null, deviceType: String = "tv"): DeviceLogin {
        val nonce = newDeviceNonce()
        val params = buildJsonObject {
            put("p_device_nonce", nonce)
            put("p_redirect_base_url", config.deviceLoginWebUrl)
            put("p_device_type", deviceType)
            if (!deviceName.isNullOrBlank()) put("p_device_name", deviceName)
        }
        val result = post("/rest/v1/rpc/start_device_login_session", params.toString())
        if (result.ok) {
            val dto = syncJson.decodeFromString<List<DeviceLoginStartDto>>(result.body).firstOrNull()
                ?: throw SyncException("Empty response from start_device_login_session")
            return DeviceLogin(
                deviceCode = dto.deviceCode,
                userCode = dto.userCode,
                verificationUrl = dto.verificationUri,
                verificationUrlComplete = dto.verificationUriComplete,
                deviceNonce = nonce,
                expiresAt = dto.expiresAt,
                pollIntervalSec = dto.pollIntervalSeconds.coerceAtLeast(2),
                legacy = false,
            )
        }
        val missing = result.body.contains("could not find the function", ignoreCase = true) &&
            result.body.contains("start_device_login_session")
        if (!missing) throw result.failure("device sign-in")
        return startLegacyTvLogin(nonce, deviceName)
    }

    private suspend fun startLegacyTvLogin(nonce: String, deviceName: String?): DeviceLogin {
        suspend fun call(withName: Boolean): SyncHttpResult {
            val params = buildJsonObject {
                put("p_device_nonce", nonce)
                put("p_redirect_base_url", config.legacyTvLoginWebUrl)
                if (withName && !deviceName.isNullOrBlank()) put("p_device_name", deviceName)
            }
            return post("/rest/v1/rpc/start_tv_login_session", params.toString())
        }
        var result = call(withName = true)
        if (!result.ok && !deviceName.isNullOrBlank() && result.body.contains("p_device_name")) {
            result = call(withName = false)
        }
        if (!result.ok) throw result.failure("device sign-in")
        val dto = syncJson.decodeFromString<List<TvLoginStartDto>>(result.body).firstOrNull()
            ?: throw SyncException("Empty response from start_tv_login_session")
        return DeviceLogin(
            deviceCode = dto.code,
            userCode = dto.code,
            verificationUrl = config.legacyTvLoginWebUrl,
            verificationUrlComplete = dto.webUrl,
            deviceNonce = nonce,
            expiresAt = dto.expiresAt,
            pollIntervalSec = dto.pollIntervalSeconds.coerceAtLeast(2),
            legacy = true,
        )
    }

    /** One poll of a device sign-in (RPC `poll_tv_login_session`, used by both flows). */
    suspend fun pollDeviceLogin(login: DeviceLogin): DeviceLoginPoll {
        val params = buildJsonObject {
            put("p_code", login.deviceCode)
            put("p_device_nonce", login.deviceNonce)
        }
        val result = post("/rest/v1/rpc/poll_tv_login_session", params.toString())
        if (!result.ok) throw result.failure("device sign-in poll")
        val dto = syncJson.decodeFromString<List<TvLoginPollDto>>(result.body).firstOrNull()
            ?: throw SyncException("Empty response from poll_tv_login_session")
        return when (val status = dto.status.lowercase()) {
            "pending" -> DeviceLoginPoll.Pending
            "approved" -> DeviceLoginPoll.Approved
            else -> DeviceLoginPoll.Ended(status)
        }
    }

    /** Trades an approved device sign-in for a session (POST /functions/v1/tv-logins-exchange). */
    suspend fun exchangeDeviceLogin(login: DeviceLogin): SyncSession {
        val body = buildJsonObject {
            put("code", login.deviceCode)
            put("device_nonce", login.deviceNonce)
        }
        val result = post("/functions/v1/tv-logins-exchange", body.toString())
        if (result.code in 400..499) throw SyncAuthException(result.errorText() ?: "The sign-in code was not accepted", result.code)
        if (!result.ok) throw result.failure("device sign-in")
        return toSession(result)
    }

    private suspend fun toSession(result: SyncHttpResult): SyncSession =
        toSession(syncJson.decodeFromString<TokenDto>(result.body))

    /** Builds a session; when the response has no user (the exchange may omit it), reads GET /auth/v1/user. */
    private suspend fun toSession(dto: TokenDto, fallback: SyncSession? = null): SyncSession {
        val access = dto.accessToken?.takeIf { it.isNotBlank() } ?: throw SyncException("The sync server sent no access token")
        val refresh = dto.refreshToken?.takeIf { it.isNotBlank() } ?: fallback?.refreshToken
            ?: throw SyncException("The sync server sent no refresh token")
        val expiresAt = dto.expiresAt ?: dto.expiresIn?.takeIf { it > 0 }?.let { nowEpochSec() + it }
            ?: throw SyncException("The sync server sent no token lifetime")
        val user = dto.user ?: fallback?.let { UserDto(it.userId, it.email) } ?: fetchUser(access)
        return SyncSession(access, refresh, expiresAt, user.id, user.email ?: fallback?.email)
    }

    private suspend fun fetchUser(accessToken: String): UserDto {
        val request = Request.Builder().url("$base/auth/v1/user").syncHeaders(config, accessToken).get().build()
        val result = http.sendSync(request)
        if (result.code == 401 || result.code == 403) throw SyncAuthException("The sync server rejected the new session", result.code)
        if (!result.ok) throw result.failure("account lookup")
        return syncJson.decodeFromString(result.body)
    }

    private suspend fun post(path: String, json: String, accessToken: String? = null): SyncHttpResult {
        val request = Request.Builder()
            .url(base + path)
            .syncHeaders(config, accessToken)
            .post(json.toRequestBody(JSON))
            .build()
        return http.sendSync(request)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** 24 random bytes, base64url without padding, as the TV app generates. */
        fun newDeviceNonce(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /**
         * Reads `<host>/.well-known/fetch`, the document a self-hosted server publishes so a client
         * can be set up from its address alone. [input] may omit the scheme (https is assumed).
         */
        suspend fun discover(http: OkHttpClient, input: String): DiscoveredServer {
            val url = discoveryUrl(input) ?: throw SyncException("Not a valid server address")
            val result = http.sendSync(Request.Builder().url(url).header("Accept", "application/json").get().build())
            if (!result.ok) throw result.failure("discovery")
            val doc = runCatching { syncJson.decodeFromString<DiscoveryDto>(result.body) }
                .getOrElse { throw SyncException("The server's /.well-known/fetch document is not valid") }
            if (doc.version != 1) throw SyncException("Unsupported server version ${doc.version}")
            if (!doc.service.equals("fetch", ignoreCase = true)) throw SyncException("Not a Fetch server")
            if (!doc.selfHosted) throw SyncException("Not a self-hosted Fetch server")
            val backend = doc.backendUrl.trim().toHttpUrlOrNull()
                ?.takeIf { it.scheme == "http" || it.scheme == "https" }
                ?: throw SyncException("The server document has no valid backend URL")
            if (doc.publishableKey.isBlank()) throw SyncException("The server document has no publishable key")
            val caps = doc.capabilities
            if (!caps.emailPasswordAuth && !caps.tvLogin) throw SyncException("The server offers no supported sign-in")
            return DiscoveredServer(
                config = SyncServerConfig(backend.toString().trimEnd('/'), doc.publishableKey.trim()),
                emailPasswordAuth = caps.emailPasswordAuth,
                tvLogin = caps.tvLogin,
                watchParty = caps.watchParty,
            )
        }

        internal fun discoveryUrl(input: String): String? {
            val trimmed = input.trim().ifBlank { return null }
            val parsed = (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "http" && parsed.scheme != "https") return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            val suffix = "/.well-known/fetch"
            val basePath = parsed.encodedPath.removeSuffix(suffix).trimEnd('/')
            return parsed.newBuilder().encodedPath(basePath + suffix).query(null).fragment(null).build().toString()
        }
    }
}

@Serializable
private data class TokenDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val user: UserDto? = null,
)

@Serializable
private data class UserDto(val id: String, val email: String? = null)

@Serializable
private data class DeviceLoginStartDto(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3,
)

@Serializable
private data class TvLoginStartDto(
    val code: String,
    @SerialName("web_url") val webUrl: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3,
)

@Serializable
private data class TvLoginPollDto(val status: String)

@Serializable
private data class DiscoveryDto(
    val version: Int,
    val service: String,
    @SerialName("self_hosted") val selfHosted: Boolean,
    @SerialName("backend_url") val backendUrl: String,
    @SerialName("publishable_key") val publishableKey: String,
    val capabilities: DiscoveryCapabilitiesDto = DiscoveryCapabilitiesDto(),
)

@Serializable
private data class DiscoveryCapabilitiesDto(
    @SerialName("email_password_auth") val emailPasswordAuth: Boolean = false,
    @SerialName("tv_login") val tvLogin: Boolean = false,
    @SerialName("watch_party") val watchParty: Boolean = false,
)
