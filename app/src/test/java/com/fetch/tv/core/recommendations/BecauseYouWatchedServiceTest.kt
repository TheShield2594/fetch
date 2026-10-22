package com.fetch.tv.core.recommendations

import android.content.Context
import com.fetch.tv.R
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.data.remote.api.TmdbApi
import com.fetch.tv.data.remote.api.TmdbCastMember
import com.fetch.tv.data.remote.api.TmdbCollectionPart
import com.fetch.tv.data.remote.api.TmdbCollectionResponse
import com.fetch.tv.data.remote.api.TmdbCollectionSummary
import com.fetch.tv.data.remote.api.TmdbCreditsResponse
import com.fetch.tv.data.remote.api.TmdbCrewMember
import com.fetch.tv.data.remote.api.TmdbDetailsResponse
import com.fetch.tv.data.remote.api.TmdbRecommendationResult
import com.fetch.tv.data.remote.api.TmdbRecommendationsResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class BecauseYouWatchedServiceTest {

    private val seed = BecauseYouWatchedSeed(
        contentId = "tt0468569",
        contentType = "movie",
        title = "The Dark Knight",
        completedAt = 1L
    )

    private val context = mockk<Context> {
        every { getString(R.string.because_you_watched_row_title, any()) } answers {
            "Because you watched ${secondArg<Array<Any>>()[0]}"
        }
        every { getString(R.string.because_you_watched_reason_collection, any()) } answers {
            "Also in ${secondArg<Array<Any>>()[0]}"
        }
        every { getString(R.string.because_you_watched_reason_director, any()) } answers {
            "Also directed by ${secondArg<Array<Any>>()[0]}"
        }
        every { getString(R.string.because_you_watched_reason_cast, any()) } answers {
            "Also stars ${secondArg<Array<Any>>()[0]}"
        }
    }

    private val tmdbService = mockk<TmdbService> {
        coEvery { ensureTmdbId("tt0468569", "movie", any()) } returns "155"
    }

    private fun service(api: TmdbApi, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        BecauseYouWatchedService(context, api, tmdbService, "key", StandardTestDispatcher(scheduler))

    private fun recommendation(id: Int, title: String) = TmdbRecommendationResult(
        id = id,
        title = title,
        mediaType = "movie",
        posterPath = "/$id.jpg",
        releaseDate = "2010-01-01"
    )

    private fun api(failCreditsFor: Int? = null): TmdbApi = mockk {
        coEvery { getMovieDetails(155, any(), any()) } returns Response.success(
            TmdbDetailsResponse(id = 155, belongsToCollection = TmdbCollectionSummary(id = 263, name = "The Dark Knight Collection"))
        )
        coEvery { getCollectionDetails(263, any(), any()) } returns Response.success(
            TmdbCollectionResponse(
                id = 263,
                name = "The Dark Knight Collection",
                parts = listOf(
                    TmdbCollectionPart(id = 272, title = "Batman Begins", releaseDate = "2005-06-10"),
                    TmdbCollectionPart(id = 155, title = "The Dark Knight", releaseDate = "2008-07-16"),
                    TmdbCollectionPart(id = 49026, title = "The Dark Knight Rises", releaseDate = "2012-07-16"),
                    TmdbCollectionPart(id = 999, title = "Unreleased", releaseDate = null)
                )
            )
        )
        coEvery { getMovieCredits(155, any(), any()) } returns Response.success(
            TmdbCreditsResponse(
                cast = listOf(TmdbCastMember(id = 3, name = "Christian Bale"), TmdbCastMember(id = 5, name = "Heath Ledger")),
                crew = listOf(TmdbCrewMember(id = 1, name = "Christopher Nolan", job = "Director"))
            )
        )
        coEvery { getMovieRecommendations(155, any(), any(), any()) } returns Response.success(
            TmdbRecommendationsResponse(
                results = listOf(
                    recommendation(272, "Batman Begins"),
                    recommendation(27205, "Inception"),
                    recommendation(1124, "The Prestige"),
                    recommendation(603, "The Matrix")
                )
            )
        )
        coEvery { getMovieCredits(27205, any(), any()) } returns if (failCreditsFor == 27205) {
            Response.error(500, "{}".toResponseBody("application/json".toMediaType()))
        } else {
            Response.success(
                TmdbCreditsResponse(crew = listOf(TmdbCrewMember(id = 1, name = "Christopher Nolan", job = "Director")))
            )
        }
        coEvery { getMovieCredits(1124, any(), any()) } returns Response.success(
            TmdbCreditsResponse(cast = listOf(TmdbCastMember(id = 3, name = "Christian Bale")))
        )
        coEvery { getMovieCredits(603, any(), any()) } returns Response.success(
            TmdbCreditsResponse(cast = listOf(TmdbCastMember(id = 6382, name = "Keanu Reeves")))
        )
    }

    @Test
    fun `row gives every card a reason and drops the ones without`() = runTest {
        val row = service(api(), testScheduler).buildRow(seed, "en")

        assertNotNull(row)
        row!!
        assertEquals(BECAUSE_YOU_WATCHED_ADDON_ID, row.addonId)
        assertEquals("Because you watched The Dark Knight", row.catalogName)
        assertEquals(
            listOf(
                "tmdb:272" to "Also in The Dark Knight Collection",
                "tmdb:49026" to "Also in The Dark Knight Collection",
                "tmdb:27205" to "Also directed by Christopher Nolan",
                "tmdb:1124" to "Also stars Christian Bale"
            ),
            row.items.map { it.id to it.recommendationReason }
        )
    }

    @Test
    fun `row with a failed candidate is not cached`() = runTest {
        val api = api(failCreditsFor = 27205)
        val service = service(api, testScheduler)

        val first = service.buildRow(seed, "en")
        assertEquals(listOf("tmdb:272", "tmdb:49026", "tmdb:1124"), first?.items?.map { it.id })

        service.buildRow(seed, "en")
        coVerify(exactly = 2) { api.getMovieRecommendations(155, any(), any(), any()) }
    }

    @Test
    fun `complete row is cached`() = runTest {
        val api = api()
        val service = service(api, testScheduler)

        service.buildRow(seed, "en")
        service.buildRow(seed, "en")
        coVerify(exactly = 1) { api.getMovieRecommendations(155, any(), any(), any()) }
    }

    @Test
    fun `no api key builds nothing`() = runTest {
        val api = api()
        val service = BecauseYouWatchedService(context, api, tmdbService, "", StandardTestDispatcher(testScheduler))

        assertNull(service.buildRow(seed, "en"))
        coVerify(exactly = 0) { api.getMovieRecommendations(any(), any(), any(), any()) }
    }
}
