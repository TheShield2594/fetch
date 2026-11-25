package com.fetch.tv.data.website

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The version handshake between the TV and its Fetch server (#639). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FetchWebsiteCompatibilityTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FetchWebsiteStore
    private lateinit var client: FetchWebsiteClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("compat_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = FetchWebsiteStore(prefs)
        store.save(FetchWebsiteSession(server.url("/").toString().trimEnd('/'), "token", "Living room"))
        client = FetchWebsiteClient(store, OkHttpClient(), appVersion = "1.9.1")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a server at or above the minimum api version is compatible`() {
        assertNull(FetchWebsiteCompatibility.check(WebsiteServerVersion("0.7.0", FetchWebsiteCompatibility.MIN_SERVER_API_VERSION)))
    }

    @Test
    fun `a server without the version route is too old`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val version = client.serverVersion()
        assertEquals(0, version.apiVersion)
        assertEquals(WebsiteVersionMismatch.ServerTooOld(null), FetchWebsiteCompatibility.check(version))
    }

    @Test
    fun `the server's version is read and the app's version is sent`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"server":"0.7.0","apiVersion":1,"minTvApp":"1.9.0","features":["profiles"]}""")
        )
        val version = client.serverVersion()
        assertEquals(WebsiteServerVersion("0.7.0", 1, "1.9.0", listOf("profiles")), version)
        assertEquals("1.9.1", server.takeRequest().getHeader(FetchWebsiteCompatibility.APP_VERSION_HEADER))
    }

    @Test
    fun `a 426 for an old app is a version mismatch, not a sign-out`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(426)
                .setBody("""{"error":"too old","code":"tv_app_too_old","minTvApp":"2.0.0"}""")
        )
        try {
            client.checkSession()
            fail("Expected the session check to be refused")
        } catch (error: FetchWebsiteIncompatibleException) {
            assertEquals(WebsiteVersionMismatch.TvAppTooOld("2.0.0"), error.mismatch)
            assertEquals(426, error.status)
        }
        assertEquals(WebsiteVersionMismatch.TvAppTooOld("2.0.0"), store.versionMismatch.value)
        assertTrue(store.isPaired)
    }
}
