package com.fetch.tv.data.repository

import android.content.Context
import com.fetch.tv.data.local.AuthSessionNoticeDataStore
import com.fetch.tv.data.local.TraktAuthDataStore
import com.fetch.tv.data.local.TraktAuthState
import com.fetch.tv.data.remote.api.TraktApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import com.fetch.tv.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Response
import com.fetch.tv.data.remote.dto.trakt.TraktTokenResponseDto
import org.junit.Assert.assertTrue

class TraktAuthServiceTest {
    @Test
    fun `refresh token 400 retries once then clears tokens but keeps the account name`() = runTest {
        // refreshTokenIfNeeded() returns early without a Trakt client ID, which builds
        // without a local.properties (CI, forks) don't have.
        assumeTrue(BuildConfig.TRAKT_CLIENT_ID.isNotBlank())
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>()
        var authState = authenticatedState()

        every { traktAuthDataStore.activeProfileId() } returns 1
        coEvery { traktAuthDataStore.getCurrentState(1) } answers { authState }
        coEvery { traktAuthDataStore.clearTokensForReconnect(1) } answers {
            authState = TraktAuthState(username = authState.username)
        }
        coEvery { authSessionNoticeDataStore.markTraktReconnectRequired() } returns Unit
        coEvery { traktApi.refreshToken(any()) } returns Response.error(400, "invalid_grant".toResponseBody())

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = authSessionNoticeDataStore
        )

        assertFalse(service.refreshTokenIfNeeded(force = true))
        assertFalse(service.refreshTokenIfNeeded(force = true))

        // One retry with the re-read token, then no more attempts once the grant is gone.
        coVerify(exactly = 2) { traktApi.refreshToken(any()) }
        coVerify(exactly = 1) { authSessionNoticeDataStore.markTraktReconnectRequired() }
        coVerify(exactly = 1) { traktAuthDataStore.clearTokensForReconnect(1) }
        coVerify(exactly = 0) { traktAuthDataStore.clearAuth(any<Int>()) }
        assertTrue(authState.isReconnectRequired)
    }

    @Test
    fun `refresh rejected once succeeds with the stored token on retry`() = runTest {
        assumeTrue(BuildConfig.TRAKT_CLIENT_ID.isNotBlank())
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>(relaxed = true)
        val authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        val token = TraktTokenResponseDto(
            accessToken = "new-access",
            tokenType = "bearer",
            expiresIn = 3600,
            refreshToken = "new-refresh",
            scope = "public",
            createdAt = 2L
        )
        var calls = 0

        every { traktAuthDataStore.activeProfileId() } returns 1
        coEvery { traktAuthDataStore.getCurrentState(1) } returns authenticatedState()
        coEvery { traktApi.refreshToken(any()) } coAnswers {
            calls++
            if (calls == 1) Response.error(401, "invalid_grant".toResponseBody()) else Response.success(token)
        }

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = authSessionNoticeDataStore
        )

        assertTrue(service.refreshTokenIfNeeded(force = true))
        coVerify(exactly = 1) { traktAuthDataStore.saveToken(1, token) }
        coVerify(exactly = 0) { traktAuthDataStore.clearTokensForReconnect(any()) }
        coVerify(exactly = 0) { authSessionNoticeDataStore.markTraktReconnectRequired() }
    }

    @Test
    fun `network error during refresh keeps the tokens`() = runTest {
        assumeTrue(BuildConfig.TRAKT_CLIENT_ID.isNotBlank())
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>(relaxed = true)
        val authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)

        every { traktAuthDataStore.activeProfileId() } returns 1
        coEvery { traktAuthDataStore.getCurrentState(1) } returns authenticatedState()
        coEvery { traktApi.refreshToken(any()) } throws java.io.IOException("reset")

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = authSessionNoticeDataStore
        )

        assertFalse(service.refreshTokenIfNeeded(force = true))
        coVerify(exactly = 0) { traktAuthDataStore.clearTokensForReconnect(any()) }
        coVerify(exactly = 0) { traktAuthDataStore.clearAuth(any<Int>()) }
    }

    @Test
    fun `refresh saves rotated tokens to the profile that started it after a profile switch`() = runTest {
        assumeTrue(BuildConfig.TRAKT_CLIENT_ID.isNotBlank())
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>(relaxed = true)
        val authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        var activeProfile = 1
        val token = TraktTokenResponseDto(
            accessToken = "new-access",
            tokenType = "bearer",
            expiresIn = 3600,
            refreshToken = "new-refresh",
            scope = "public",
            createdAt = 2L
        )

        every { traktAuthDataStore.activeProfileId() } answers { activeProfile }
        coEvery { traktAuthDataStore.getCurrentState(1) } returns authenticatedState()
        coEvery { traktApi.refreshToken(any()) } coAnswers {
            // The user switches to profile 2 while the refresh is in flight.
            activeProfile = 2
            Response.success(token)
        }

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = authSessionNoticeDataStore
        )

        assertTrue(service.refreshTokenIfNeeded(force = true))

        coVerify(exactly = 1) { traktAuthDataStore.saveToken(1, token) }
        coVerify(exactly = 0) { traktAuthDataStore.saveToken(2, any()) }
        coVerify(exactly = 0) { traktAuthDataStore.saveToken(any<TraktTokenResponseDto>()) }
    }

    private fun authenticatedState(): TraktAuthState {
        return TraktAuthState(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            tokenType = "bearer",
            createdAt = 1L,
            expiresIn = 3600,
            username = "sam"
        )
    }
}
