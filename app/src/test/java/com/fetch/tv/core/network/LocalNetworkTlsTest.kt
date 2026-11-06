package com.fetch.tv.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
