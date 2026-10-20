package com.fetch.tv.data.remote

import com.fetch.tv.domain.model.isPublicServerHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDiscoveryPolicyTest {
    @Test
    fun discoveryUrlAddsSchemeAndKnownPath() {
        assertEquals(
            "https://backend.example.com/.well-known/fetch",
            ServerDiscoveryPolicy.discoveryUrl("backend.example.com")
        )
    }

    @Test
    fun discoveryUrlPreservesBackendPathAndIsIdempotent() {
        assertEquals(
            "https://example.com/backend/.well-known/fetch",
            ServerDiscoveryPolicy.discoveryUrl("https://example.com/backend/.well-known/fetch")
        )
    }

    @Test
    fun parseAcceptsVersionOneDocument() {
        val configuration = ServerDiscoveryPolicy.parse(
            discoveryUrl = "https://example.com/.well-known/fetch",
            document = """
                {
                  "version": 1,
                  "service": "fetch",
                  "self_hosted": true,
                  "backend_url": "https://backend.example.com/",
                  "publishable_key": "public-key",
                  "capabilities": {
                    "email_password_auth": true,
                    "tv_login": true,
                    "watch_party": true
                  }
                }
            """.trimIndent()
        )

        assertEquals("https://backend.example.com", configuration.backendUrl)
        assertEquals("public-key", configuration.publishableKey)
        assertTrue(configuration.isCustom)
        assertTrue(configuration.capabilities.emailPasswordAuth)
        assertTrue(configuration.capabilities.tvLogin)
        assertTrue(configuration.capabilities.watchParty)
    }

    @Test
    fun parseRejectsUnsupportedVersion() {
        val error = assertThrows(ServerDiscoveryException::class.java) {
            ServerDiscoveryPolicy.parse(
                discoveryUrl = "https://example.com/.well-known/fetch",
                document = """
                    {
                      "version": 2,
                      "service": "fetch",
                      "self_hosted": true,
                      "backend_url": "https://backend.example.com",
                      "publishable_key": "public-key",
                      "capabilities": { "email_password_auth": true }
                    }
                """.trimIndent()
            )
        }

        assertEquals(ServerDiscoveryFailure.UNSUPPORTED_VERSION, error.failure)
    }

    @Test
    fun parseRejectsDocumentWithoutSupportedAuthentication() {
        val error = assertThrows(ServerDiscoveryException::class.java) {
            ServerDiscoveryPolicy.parse(
                discoveryUrl = "https://example.com/.well-known/fetch",
                document = """
                    {
                      "version": 1,
                      "service": "fetch",
                      "self_hosted": true,
                      "backend_url": "https://backend.example.com",
                      "publishable_key": "public-key",
                      "capabilities": {}
                    }
                """.trimIndent()
            )
        }

        assertEquals(ServerDiscoveryFailure.NO_SUPPORTED_AUTH, error.failure)
    }

    @Test
    fun publicHostClassificationDistinguishesPrivateNetworks() {
        assertFalse(isPublicServerHost("http://192.168.1.10:8000"))
        assertFalse(isPublicServerHost("http://172.20.0.5"))
        assertFalse(isPublicServerHost("http://server.local"))
        assertTrue(isPublicServerHost("https://backend.example.com"))
    }

    @Test
    fun matchesOfficialBackendByHostAndPort() {
        assertTrue(
            ServerDiscoveryPolicy.matchesBackend(
                "https://api.fetch.tv/.well-known/fetch",
                "https://api.fetch.tv"
            )
        )
        assertFalse(
            ServerDiscoveryPolicy.matchesBackend(
                "https://api.fetch.tv.example.com/.well-known/fetch",
                "https://api.fetch.tv"
            )
        )
    }
}
