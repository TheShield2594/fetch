package com.fetch.tv.data.website

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The stats and calendar bodies as the website's routes send them. */
class FetchWebsiteStatsModelsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `detailed stats decode, including null ratings and posters`() {
        val body = """
            {"monthly":[{"month":"2026-10","movies":2,"episodes":7}],
             "genreDistribution":[{"genre":"Drama","count":12}],
             "currentStreak":3,"longestStreak":9,
             "topRated":[{"imdbId":"tt1","name":"Film","type":"movie","rating":8.4,"poster":null}]}
        """.trimIndent()

        val stats = json.decodeFromString(WebsiteDetailedStats.serializer(), body)

        assertEquals(9, stats.monthly.single().total)
        assertEquals(9, stats.longestStreak)
        assertEquals(8.4, stats.topRated.single().rating!!, 0.0)
        assertNull(stats.topRated.single().poster)
    }

    @Test
    fun `a year in review with no busiest month decodes`() {
        val body = """
            {"year":2026,"totalMovies":0,"totalEpisodes":0,"totalRuntimeMinutes":0,
             "topGenres":[],"topRated":[{"imdbId":"tt2","type":"series","name":null,"poster":null,"rating":9}],
             "busiestMonth":null,"busiestMonthCount":0}
        """.trimIndent()

        val review = json.decodeFromString(WebsiteYearInReview.serializer(), body)

        assertNull(review.busiestMonth)
        assertEquals("series", review.topRated.single().type)
    }

    @Test
    fun `calendar entries decode with an absent overview`() {
        val body = """
            {"calendar":[{"seriesImdbId":"tt3","seriesName":"Show","poster":null,"season":2,"episode":5,
             "episodeName":"Return","airDate":"2026-11-09","overview":null}]}
        """.trimIndent()

        val entry = json.decodeFromString(WebsiteCalendarBody.serializer(), body).calendar.single()

        assertEquals("2026-11-09", entry.airDate)
        assertNull(entry.overview)
    }
}
