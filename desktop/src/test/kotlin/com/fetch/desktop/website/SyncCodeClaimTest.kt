package com.fetch.desktop.website

import com.fetch.desktop.SyncAccountState
import com.fetch.desktop.parseSyncClaim
import com.fetch.desktop.sync.SyncAuthClient
import com.fetch.desktop.sync.SyncAuthException
import com.fetch.desktop.sync.SyncFakeHttp
import com.fetch.desktop.sync.SyncStore
import com.fetch.desktop.sync.testServer
import com.fetch.desktop.syncClaimParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Claiming a sync code from this computer: an anonymous sign-in when signed out, then `claim_sync_code`. */
class SyncCodeClaimTest {
    private val discovery =
        """{"version":1,"service":"fetch","self_hosted":true,"backend_url":"https://sync.example.com","publishable_key":"anon-key","capabilities":{"tv_login":true}}"""

    private val anonymousTokens =
        """{"access_token":"anon-access","refresh_token":"anon-refresh","expires_in":3600,"expires_at":2000003600,"user":{"id":"anon-1","is_anonymous":true}}"""

    private fun store() = SyncStore(File(Files.createTempDirectory("sync").toFile(), "sync.json"))

    /** Waits for every coroutine the account launched, including ones those launched. */
    private suspend fun Job.settle() {
        while (true) {
            val running = children.toList()
            if (running.isEmpty()) return
            running.forEach { it.join() }
        }
    }

    private fun respond(claim: String): (Request) -> Pair<Int, String> = { request ->
        when (request.url.encodedPath) {
            "/.well-known/fetch" -> 200 to discovery
            "/auth/v1/signup" -> 200 to anonymousTokens
            "/rest/v1/rpc/claim_sync_code" -> 200 to claim
            "/auth/v1/logout" -> 204 to ""
            else -> 404 to "{}"
        }
    }

    @Test
    fun `the anonymous sign-in sends no email and keeps the session`() = runBlocking {
        val http = SyncFakeHttp { 200 to anonymousTokens }
        val session = SyncAuthClient(http.client, testServer).signInAnonymously()
        val sent = http.sent.single()
        assertEquals("/auth/v1/signup", sent.path)
        assertEquals("anon-key", sent.header("apikey"))
        assertNull(sent.header("Authorization"))
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertFalse("email" in body)
        assertEquals("anon-1", session.userId)
        assertNull(session.email)
    }

    @Test
    fun `a server without anonymous sign-ins says so`() = runBlocking {
        val http = SyncFakeHttp { 422 to """{"msg":"Anonymous sign-ins are disabled"}""" }
        try {
            SyncAuthClient(http.client, testServer).signInAnonymously()
            fail("expected SyncAuthException")
        } catch (e: SyncAuthException) {
            assertEquals("Anonymous sign-ins are disabled", e.message)
        }
    }

    @Test
    fun `signed out, a code is claimed under an anonymous sign-in`() = runBlocking {
        val http = SyncFakeHttp(respond("""[{"result_owner_id":"owner-1","success":true,"message":"Device linked"}]"""))
        val job = SupervisorJob()
        val account = SyncAccountState(CoroutineScope(Dispatchers.Default + job), store(), http.client)
        account.findServer("sync.example.com")
        job.settle()
        assertNotNull(account.discovered)

        var claimed = false
        account.claimSyncCode(" abcd-efgh ", "1234", deviceName = "Desk") { claimed = true }
        job.settle()

        assertTrue(claimed)
        assertNull(account.error)
        assertEquals("anon-access", account.state.session?.accessToken)
        assertTrue(account.signedIn)
        val claim = http.sent.single { it.path == "/rest/v1/rpc/claim_sync_code" }
        assertEquals("Bearer anon-access", claim.header("Authorization"))
        val params = Json.parseToJsonElement(claim.body).jsonObject
        assertEquals("ABCD-EFGH", params["p_code"]!!.jsonPrimitive.content)
        assertEquals("1234", params["p_pin"]!!.jsonPrimitive.content)
        assertEquals("Desk", params["p_device_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a code that isn't taken signs the anonymous user out again`() = runBlocking {
        val http = SyncFakeHttp(respond("""[{"result_owner_id":null,"success":false,"message":"Invalid code or PIN"}]"""))
        val job = SupervisorJob()
        val account = SyncAccountState(CoroutineScope(Dispatchers.Default + job), store(), http.client)
        account.findServer("sync.example.com")
        job.settle()

        var claimed = false
        account.claimSyncCode("WRONG", "0000") { claimed = true }
        job.settle()

        assertFalse(claimed)
        assertEquals("Invalid code or PIN", account.error)
        assertNull(account.state.session)
        assertFalse(account.signedIn)
        assertTrue(http.sent.any { it.path == "/auth/v1/logout" })
    }

    @Test
    fun `claim answers are read from the RPC's list`() {
        assertEquals(true, parseSyncClaim("""[{"result_owner_id":"o","success":true,"message":"ok"}]""")?.success)
        assertEquals("o", parseSyncClaim("""{"result_owner_id":"o","success":true,"message":"ok"}""")?.ownerId)
        assertNull(parseSyncClaim("[]"))
        assertFalse("p_device_name" in syncClaimParams("A", "1234", null))
    }
}
