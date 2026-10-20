package com.fetch.tv.core.server

import fi.iki.elonen.NanoHTTPD
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Access control for the phone pages served from the TV.
 *
 * - Only devices on the local network (private, link-local or loopback addresses) are served.
 * - The QR code carries a one-time pairing token (`/?pair=<token>`). The first phone that
 *   opens it gets a random session cookie and the token is spent; later requests must carry
 *   that cookie. Scanning a spent code shows an error; reopening the page on the TV makes a
 *   new server with a new code.
 *
 * See docs/architecture/phone-config-security.md for the threat model.
 */
class PairingGuard(
    private val random: SecureRandom = SecureRandom(),
    private val maxSessions: Int = 4
) {
    sealed interface Decision {
        data object Allow : Decision
        /** Token accepted: set the cookie and redirect to [location] without the token. */
        data class Paired(val cookie: String, val location: String) : Decision
        data class Deny(val status: NanoHTTPD.Response.Status, val message: String) : Decision
    }

    @Volatile
    private var token: String? = newSecret()
    private val sessions = ConcurrentHashMap.newKeySet<String>()

    /** Token to put in the QR code; null once a phone has paired. */
    val pairingToken: String? get() = token

    val pairedSessionCount: Int get() = sessions.size

    fun pairingUrl(host: String, port: Int): String =
        token?.let { "http://$host:$port/?$PAIR_PARAM=$it" } ?: "http://$host:$port/"

    fun decide(
        remoteIp: String?,
        uri: String,
        pairParam: String?,
        cookieHeader: String?
    ): Decision {
        if (!isLocalNetworkAddress(remoteIp)) {
            return Decision.Deny(NanoHTTPD.Response.Status.FORBIDDEN, "Only devices on your local network can open this page.")
        }
        val session = parseCookie(cookieHeader, COOKIE_NAME)
        if (session != null && session in sessions) return Decision.Allow

        if (pairParam != null) {
            synchronized(this) {
                val expected = token
                if (expected != null && constantTimeEquals(pairParam, expected)) {
                    token = null
                    val sessionId = newSecret()
                    if (sessions.size >= maxSessions) sessions.clear()
                    sessions += sessionId
                    return Decision.Paired(
                        cookie = "$COOKIE_NAME=$sessionId; Path=/; HttpOnly; SameSite=Strict",
                        location = uri.ifBlank { "/" }
                    )
                }
            }
            return Decision.Deny(
                NanoHTTPD.Response.Status.FORBIDDEN,
                "This code was already used or has expired. Reopen the page on your TV to get a new code."
            )
        }
        return Decision.Deny(
            NanoHTTPD.Response.Status.UNAUTHORIZED,
            "Scan the QR code on your TV to open this page."
        )
    }

    /** Applies [decide] to a NanoHTTPD request; returns null when the request may proceed. */
    fun check(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val decision = decide(
            remoteIp = session.headers["remote-addr"],
            uri = session.uri.orEmpty(),
            pairParam = session.parameters[PAIR_PARAM]?.firstOrNull(),
            cookieHeader = session.headers["cookie"]
        )
        return when (decision) {
            Decision.Allow -> null
            is Decision.Paired -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.REDIRECT_SEE_OTHER,
                NanoHTTPD.MIME_PLAINTEXT,
                ""
            ).apply {
                addHeader("Set-Cookie", decision.cookie)
                addHeader("Location", decision.location)
                addHeader("Cache-Control", "no-store")
            }
            is Decision.Deny -> NanoHTTPD.newFixedLengthResponse(
                decision.status,
                "text/plain; charset=utf-8",
                decision.message
            )
        }
    }

    private fun newSecret(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PAIR_PARAM = "pair"
        const val COOKIE_NAME = "fetch_pair"

        fun parseCookie(header: String?, name: String): String? =
            header?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }

        /** True for loopback, link-local and private (RFC 1918 / IPv6 ULA) addresses. */
        fun isLocalNetworkAddress(ip: String?): Boolean {
            if (ip.isNullOrBlank()) return false
            val literal = ip.trim().removePrefix("/").substringBefore('%')
            // Only parse literals; never resolve host names.
            if (!literal.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }) return false
            val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            val bytes = address.address
            // IPv6 unique local addresses (fc00::/7).
            if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
            // Carrier-grade NAT range (100.64.0.0/10), used by some home and travel routers.
            return bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
