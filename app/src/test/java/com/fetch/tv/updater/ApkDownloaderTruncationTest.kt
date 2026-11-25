package com.fetch.tv.updater

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTruncationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val downloader = ApkDownloader(OkHttpClient())

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a connection closed before Content-Length is an incomplete download`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(64 * 1024)))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val file = tmp.root.resolve("update.apk")

        val result = downloader.download(server.url("/a.apk").toString(), file) { _, _ -> }

        assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is ApkVerificationException)
        assertFalse(file.exists())
    }

    @Test
    fun `a complete download with the wrong digest fails and is deleted`() = runBlocking {
        server.enqueue(MockResponse().setBody("apk"))
        val file = tmp.root.resolve("update.apk")

        val result = downloader.download(
            url = server.url("/a.apk").toString(),
            destinationFile = file,
            expectedSha256 = "0".repeat(64)
        ) { _, _ -> }

        assertTrue(result.exceptionOrNull() is ApkVerificationException)
        assertFalse(file.exists())
    }

    @Test
    fun `a complete download that matches passes`() = runBlocking {
        server.enqueue(MockResponse().setBody("apk"))
        val file = tmp.root.resolve("update.apk")

        val result = downloader.download(
            url = server.url("/a.apk").toString(),
            destinationFile = file,
            expectedSizeBytes = 3,
            // sha256("apk")
            expectedSha256 = java.security.MessageDigest.getInstance("SHA-256")
                .digest("apk".toByteArray()).joinToString("") { "%02x".format(it) }
        ) { _, _ -> }

        assertTrue(result.exceptionOrNull().toString(), result.isSuccess)
        assertTrue(file.exists())
    }
}
