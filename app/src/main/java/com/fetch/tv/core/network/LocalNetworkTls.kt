package com.fetch.tv.core.network

import android.annotation.SuppressLint
import com.fetch.tv.core.server.PairingGuard
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
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

    // Lint (CustomX509TrustManager): accepting any certificate is deliberate and bounded. This
    // manager is only installed by [unverifiedClient], whose network interceptor refuses to send a
    // request unless both the URL host and the address actually connected to are local. Public
    // hosts always use the platform trust manager. Narrowing further (trust-on-first-use pinning
    // per local server) would also stop a LAN device impersonating a known server; see #492.
    @SuppressLint("CustomX509TrustManager")
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

    /**
     * [base] with certificate and hostname checks disabled, for [isLocalNetworkHost] hosts only.
     *
     * A network interceptor runs on every hop, redirects included, after the connection is made
     * but before the request is written. It refuses to send anything unless both the host name
     * and the address actually connected to are local, so a local server redirecting to a public
     * URL, or a local-looking name that DNS resolves to a public address, never gets an
     * unverified request (and the URL's query, which can hold debrid keys).
     *
     * The client never uses a proxy: through one, the address connected to would be the
     * proxy's, so the address check could pass while the request reaches a public target.
     * Local servers are reached directly anyway.
     */
    fun unverifiedClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .sslSocketFactory(trustAllSslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (url.isHttps) {
                    val route = chain.connection()?.route()
                    val address = route?.socketAddress?.address?.hostAddress
                    val direct = route?.proxy?.type() == Proxy.Type.DIRECT
                    if (!direct || !isLocalNetworkHost(url.host) || !PairingGuard.isLocalNetworkAddress(address)) {
                        throw SSLPeerUnverifiedException(
                            "Refusing an unverified TLS connection to a non-local host: ${url.host}"
                        )
                    }
                }
                chain.proceed(chain.request())
            }
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
