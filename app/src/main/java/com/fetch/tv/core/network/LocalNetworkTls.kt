package com.fetch.tv.core.network

import com.fetch.tv.core.server.PairingGuard
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Certificate checks are skipped only for hosts on the local network, where self-signed
 * certificates are common (self-hosted addons, Jellyfin, WebDAV). Every other host gets normal
 * validation: addon and stream URLs often carry debrid API keys, so a failed check must fail the
 * request rather than fall back to an unverified connection.
 *
 * The decision uses the host name as written in the URL, never a DNS answer, so a public name
 * cannot be made "local" by whoever controls DNS on the network.
 */
object LocalNetworkTls {

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain")

    /** True for loopback/private IP literals, `localhost`, single-label names and local-only suffixes. */
    fun isLocalNetworkHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        if (normalized.isEmpty()) return false
        // Only hand IP literals to the address check: it accepts hex letters, so a name such as
        // "bad.cafe" would otherwise reach InetAddress.getByName and be judged by a DNS answer.
        val isIpLiteral = normalized.all { it.isDigit() || it == '.' } || normalized.contains(':')
        if (isIpLiteral) return PairingGuard.isLocalNetworkAddress(normalized)
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        if (!normalized.contains('.')) return true
        return LOCAL_SUFFIXES.any { normalized.endsWith(it) }
    }

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trustAllSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    /** [base] with certificate and hostname checks disabled. Only use for [isLocalNetworkHost] hosts. */
    fun unverifiedClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .sslSocketFactory(trustAllSslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .build()

    /**
     * Retries a request whose TLS handshake failed through [unverifiedClient] when, and only when,
     * the host is on the local network. Public hosts rethrow the original error.
     */
    fun localFallbackInterceptor(unverified: () -> OkHttpClient): Interceptor = Interceptor { chain ->
        val request = chain.request()
        try {
            chain.proceed(request)
        } catch (e: SSLException) {
            if (!request.url.isHttps || !isLocalNetworkHost(request.url.host)) throw e
            unverified().newCall(request).execute()
        }
    }
}
