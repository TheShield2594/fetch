package com.fetch.tv.core.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalNetworkTlsTest {

    @Test
    fun `private and loopback addresses are local`() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.4.1", "127.0.0.1", "169.254.3.3", "100.64.1.1", "[::1]", "fd12::1")
            .forEach { assertTrue(it, LocalNetworkTls.isLocalNetworkHost(it)) }
    }

    @Test
    fun `local names are local`() {
        listOf("localhost", "nas", "jellyfin.local", "addon.lan", "media.home.arpa", "box.internal", "NAS.LOCAL.")
            .forEach { assertTrue(it, LocalNetworkTls.isLocalNetworkHost(it)) }
    }

    @Test
    fun `public hosts are never local`() {
        listOf(
            "torrentio.strem.fun",
            "api.real-debrid.com",
            "8.8.8.8",
            "2606:4700::1111",
            // All hex letters: must not be treated as an IP literal and resolved through DNS.
            "bad.cafe",
            "dead.beef.ad",
            "",
            null,
        ).forEach { assertFalse(it.toString(), LocalNetworkTls.isLocalNetworkHost(it)) }
    }

    @Test
    fun `a public host's TLS failure rethrows the original error without the unverified client`() {
        val handshakeFailure = SSLHandshakeException("certificate rejected")
        var unverifiedRequested = false
        val client = clientFailingTls(handshakeFailure) {
            unverifiedRequested = true
            error("The unverified client must not be used for a public host")
        }

        val thrown = runCatching {
            client.newCall(Request.Builder().url("https://api.real-debrid.com/rest/1.0/user?token=secret").build()).execute()
        }.exceptionOrNull()

        assertSame(handshakeFailure, thrown)
        assertFalse(unverifiedRequested)
    }

    @Test
    fun `a local host's TLS failure is retried through the unverified client`() {
        var unverifiedRequested = false
        val client = clientFailingTls(SSLHandshakeException("self-signed")) {
            unverifiedRequested = true
            throw UnsupportedOperationException("stop here")
        }

        runCatching { client.newCall(Request.Builder().url("https://nas.local/manifest.json").build()).execute() }

        assertTrue(unverifiedRequested)
    }

    @Test
    fun `the unverified client refuses a local server's redirect to a public host`() {
        MockWebServer().use { server ->
            server.useHttps(selfSignedServerContext().socketFactory, false)
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://public.example.test:${server.port}/stream?key=secret")
            )
            server.enqueue(MockResponse().setBody("must not be reached"))
            // The public name resolves to the local server, so only the host check can refuse it.
            val base = OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        if (hostname == "public.example.test") listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(hostname)
                })
                .build()
            val client = LocalNetworkTls.unverifiedClient(base)

            try {
                client.newCall(Request.Builder().url(server.url("/start")).build()).execute().close()
                fail("The redirect to a public host should have been refused")
            } catch (expected: SSLPeerUnverifiedException) {
                // Refused before the request was written.
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `the unverified client always connects directly, never through a proxy`() {
        val base = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 3128)))
            .build()

        assertEquals(Proxy.NO_PROXY, LocalNetworkTls.unverifiedClient(base).proxy)
    }

    private fun clientFailingTls(failure: SSLHandshakeException, unverified: () -> OkHttpClient): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(LocalNetworkTls.localFallbackInterceptor(unverified))
            // Stands in for the validating connection, whose TLS handshake fails.
            .addInterceptor(Interceptor { throw failure })
            .build()

    /** Self-signed cert for 127.0.0.1 shared with SubtitleCredentialScopeTest, valid until 2126. */
    private fun selfSignedServerContext(): SSLContext {
        val password = "changeit".toCharArray()
        val resource = requireNotNull(javaClass.getResourceAsStream("/subtitle-redirect-test.p12")) {
            "subtitle-redirect-test.p12 missing from test resources"
        }
        val keyStore = KeyStore.getInstance("PKCS12").apply { resource.use { load(it, password) } }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }.keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }
}
