package com.fetch.desktop.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncAuthClientTest {

    private fun body(sent: SyncFakeHttp.Sent) = Json.parseToJsonElement(sent.body).jsonObject

    @Test
    fun `password sign-in posts to GoTrue with the anon key and no bearer`() = runBlocking {
        val http = SyncFakeHttp {
            200 to """{"access_token":"a","refresh_token":"r","expires_in":3600,"expires_at":1700003600,"token_type":"bearer","user":{"id":"u-9","email":"me@example.com"}}"""
        }
        val session = SyncAuthClient(http.client, testServer).signIn(" me@example.com ", "pw")

        val sent = http.sent.single()
        assertEquals("POST", sent.request.method)
        assertEquals("https://sync.example.com/auth/v1/token?grant_type=password", sent.request.url.toString())
        assertEquals("anon-key", sent.header("apikey"))
        assertNull(sent.header("Authorization"))
        assertEquals("application/json", sent.request.body!!.contentType().toString().substringBefore(";"))
        assertEquals("me@example.com", body(sent)["email"]!!.jsonPrimitive.content)
        assertEquals("pw", body(sent)["password"]!!.jsonPrimitive.content)
        assertEquals(SyncSession("a", "r", 1700003600L, "u-9", "me@example.com"), session)
    }

    @Test
    fun `expires_in is used when expires_at is missing`() = runBlocking {
        val http = SyncFakeHttp { 200 to """{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"u"}}""" }
        val session = SyncAuthClient(http.client, testServer, nowEpochSec = { 1000L }).signIn("e", "p")
        assertEquals(4600L, session.expiresAtEpochSec)
    }

    @Test
    fun `wrong password is an auth error with the server's message`() = runBlocking {
        val http = SyncFakeHttp { 400 to """{"error":"invalid_grant","error_description":"Invalid login credentials"}""" }
        try {
            SyncAuthClient(http.client, testServer).signIn("e", "p")
            fail("expected SyncAuthException")
        } catch (e: SyncAuthException) {
            assertEquals("Invalid login credentials", e.message)
            assertEquals(400, e.code)
        }
    }

    @Test
    fun `sign-up awaiting email confirmation returns no session`() = runBlocking {
        val http = SyncFakeHttp { 200 to """{"id":"u-1","email":"me@example.com","confirmation_sent_at":"2026-01-01T00:00:00Z"}""" }
        assertNull(SyncAuthClient(http.client, testServer).signUp("me@example.com", "pw"))
        assertEquals("/auth/v1/signup", http.sent.single().path)
    }

    @Test
    fun `refresh sends the refresh token and keeps the user`() = runBlocking {
        val http = SyncFakeHttp { 200 to """{"access_token":"a2","refresh_token":"r2","expires_in":3600,"expires_at":5}""" }
        val refreshed = SyncAuthClient(http.client, testServer).refresh(testSession)
        val sent = http.sent.single()
        assertEquals("https://sync.example.com/auth/v1/token?grant_type=refresh_token", sent.request.url.toString())
        assertEquals("refresh-1", body(sent)["refresh_token"]!!.jsonPrimitive.content)
        assertEquals(SyncSession("a2", "r2", 5L, "user-1", "me@example.com"), refreshed)
    }

    @Test
    fun `sign-out posts logout with the bearer token`() = runBlocking {
        val http = SyncFakeHttp { 204 to "" }
        SyncAuthClient(http.client, testServer).signOut(testSession)
        val sent = http.sent.single()
        assertEquals("https://sync.example.com/auth/v1/logout?scope=local", sent.request.url.toString())
        assertEquals("Bearer access-1", sent.header("Authorization"))
    }

    @Test
    fun `device sign-in starts, polls and exchanges, reading the user when the exchange omits it`() = runBlocking {
        val http = SyncFakeHttp { request ->
            when (request.url.encodedPath) {
                "/rest/v1/rpc/start_device_login_session" -> 200 to
                    """[{"device_code":"dc","user_code":"ABCD","verification_uri":"https://sync.example.com/link","verification_uri_complete":"https://sync.example.com/link?code=ABCD","expires_at":"2026-11-02T10:00:00Z","poll_interval_seconds":1}]"""
                "/rest/v1/rpc/poll_tv_login_session" -> 200 to """[{"status":"APPROVED"}]"""
                "/functions/v1/tv-logins-exchange" -> 200 to """{"access_token":"a","refresh_token":"r","expires_in":60,"expires_at":99}"""
                "/auth/v1/user" -> 200 to """{"id":"u-7","email":"tv@example.com"}"""
                else -> 404 to "{}"
            }
        }
        val auth = SyncAuthClient(http.client, testServer)
        val login = auth.startDeviceLogin(deviceName = "My PC")
        assertEquals("ABCD", login.userCode)
        assertEquals(2, login.pollIntervalSec)
        val start = body(http.sent[0])
        assertEquals("https://sync.example.com/link", start["p_redirect_base_url"]!!.jsonPrimitive.content)
        assertEquals("tv", start["p_device_type"]!!.jsonPrimitive.content)
        assertEquals("My PC", start["p_device_name"]!!.jsonPrimitive.content)
        assertEquals(login.deviceNonce, start["p_device_nonce"]!!.jsonPrimitive.content)

        assertEquals(DeviceLoginPoll.Approved, auth.pollDeviceLogin(login))
        val poll = body(http.sent[1])
        assertEquals("dc", poll["p_code"]!!.jsonPrimitive.content)

        val session = auth.exchangeDeviceLogin(login)
        val exchange = body(http.sent[2])
        assertEquals("dc", exchange["code"]!!.jsonPrimitive.content)
        assertEquals(login.deviceNonce, exchange["device_nonce"]!!.jsonPrimitive.content)
        assertEquals("Bearer a", http.sent[3].header("Authorization"))
        assertEquals(SyncSession("a", "r", 99L, "u-7", "tv@example.com"), session)
    }

    @Test
    fun `device sign-in falls back to the legacy TV login`() = runBlocking {
        val http = SyncFakeHttp { request ->
            when (request.url.encodedPath) {
                "/rest/v1/rpc/start_device_login_session" -> 404 to
                    """{"code":"PGRST202","message":"Could not find the function public.start_device_login_session(p_device_nonce) in the schema cache"}"""
                "/rest/v1/rpc/start_tv_login_session" -> 200 to
                    """[{"code":"XYZ","web_url":"https://sync.example.com/tv-login?code=XYZ","expires_at":"2026-11-02T10:00:00Z"}]"""
                "/rest/v1/rpc/poll_tv_login_session" -> 200 to """[{"status":"expired"}]"""
                else -> 404 to "{}"
            }
        }
        val auth = SyncAuthClient(http.client, testServer)
        val login = auth.startDeviceLogin()
        assertTrue(login.legacy)
        assertEquals("XYZ", login.deviceCode)
        assertEquals("https://sync.example.com/tv-login", body(http.sent[1])["p_redirect_base_url"]!!.jsonPrimitive.content)
        assertEquals(DeviceLoginPoll.Ended("expired"), auth.pollDeviceLogin(login))
    }

    @Test
    fun `discovery reads the well-known document`() = runBlocking {
        val http = SyncFakeHttp {
            200 to """{"version":1,"service":"fetch","self_hosted":true,"backend_url":"https://api.example.com/","publishable_key":" pk ","capabilities":{"email_password_auth":true}}"""
        }
        val found = SyncAuthClient.discover(http.client, "example.com/")
        assertEquals("https://example.com/.well-known/fetch", http.sent.single().request.url.toString())
        assertEquals(SyncServerConfig("https://api.example.com", "pk"), found.config)
        assertTrue(found.emailPasswordAuth)
        assertEquals(false, found.tvLogin)
    }

    @Test
    fun `discovery url keeps a base path and drops the query`() {
        assertEquals("https://h.example/sub/.well-known/fetch", SyncAuthClient.discoveryUrl("https://h.example/sub/.well-known/fetch?x=1"))
        assertEquals("http://10.0.0.2:8000/.well-known/fetch", SyncAuthClient.discoveryUrl("http://10.0.0.2:8000"))
        assertNull(SyncAuthClient.discoveryUrl("ftp://h.example"))
        assertNull(SyncAuthClient.discoveryUrl("  "))
    }
}
