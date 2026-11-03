package com.fetch.desktop.playback

import com.fetch.desktop.ratings.RatingsFakeHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentalGuideClientTest {
    private val body = """
        {"parentsGuide":[
          {"category":"VIOLENCE","severityBreakdowns":[{"severityLevel":"none","voteCount":3},{"severityLevel":"moderate","voteCount":40},{"severityLevel":"severe","voteCount":12}]},
          {"category":"PROFANITY","severityBreakdowns":[{"severityLevel":"none","voteCount":50},{"severityLevel":"mild","voteCount":50}]},
          {"category":"SEXUAL_CONTENT","severityBreakdowns":[{"severityLevel":"severe","voteCount":9}]},
          {"category":"FRIGHTENING_INTENSE_SCENES","severityBreakdowns":[{"severityLevel":"mild","voteCount":4}]},
          {"category":"SOMETHING_NEW","severityBreakdowns":[{"severityLevel":"severe","voteCount":99}]}
        ]}
    """.trimIndent()

    @Test
    fun `one severity per category, worst first, as the TV resolves them`() = runBlocking {
        val http = RatingsFakeHttp.routes("/titles/tt0111161/parentsGuide" to body)
        val client = ParentalGuideClient(http.client, baseUrl = "https://guide.example/")
        val warnings = client.warnings("tt0111161")
        assertEquals(
            listOf(ParentalWarning("Nudity", "Severe"), ParentalWarning("Violence", "Moderate"), ParentalWarning("Frightening", "Mild")),
            warnings
        )
        // "none" with as many votes wins; cached after the first lookup.
        client.warnings("tt0111161")
        assertEquals(1, http.sent.size)
    }

    @Test
    fun `nothing for unknown ids or a failing server, and the rating leads`() = runBlocking {
        val client = ParentalGuideClient(RatingsFakeHttp.routes().client, baseUrl = "https://guide.example/")
        assertTrue(client.warnings("tt1").isEmpty())
        assertTrue(client.warnings("tmdb:5").isEmpty())
        assertEquals(listOf(ParentalWarning("Rated", "R"), ParentalWarning("Violence", "Mild")), ParentalGuideClient.lines("R", listOf(ParentalWarning("Violence", "Mild"))))
        assertTrue(ParentalGuideClient.lines(" ", emptyList()).isEmpty())
    }
}
