package com.fetch.tv.data.simkl

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SimklCalendarAirTimesTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Trimmed from the shape of data.simkl.in/calendar/v2/tv.json.
    private val file = """
        {"calendar":[
          {"simkl_id":1,"date":"2026-11-28T02:00:00Z","finale_type":null,"episode":{"season":3,"episode":4,"title":"A"}},
          {"simkl_id":2,"date":"2026-11-28T05:00:00Z","episode":{"season":1,"episode":7}},
          {"simkl_id":3,"date":"2026-07-20T04:00:00Z","episode":{"season":2,"episode":1}},
          {"simkl_id":4,"date":"2026-11-28T23:00:00Z"},
          {"simkl_id":5,"date":"not a date","episode":{"season":1,"episode":1}}
        ],
        "metadata":{
          "1":{"title":"One","ids":{"simkl_id":1,"imdb":"tt1","tmdb":"10"}},
          "2":{"ids":{"tmdb":"20"}},
          "3":{"ids":{"imdb":"tt3"}},
          "4":{"ids":{"imdb":"tt4"}},
          "5":{"ids":{"imdb":"tt5"}}
        }}
    """.trimIndent()

    @Test
    fun `episodes get their time by IMDb and TMDB id`() {
        val times = simklCalendarAirTimes(json.decodeFromString(SimklCalendarFile.serializer(), file))

        assertEquals("2026-11-28T02:00:00Z", times["tt1:3:4"])
        assertEquals("2026-11-28T02:00:00Z", times["tmdb:10:3:4"])
    }

    @Test
    fun `midnight US Eastern, a film, or an unreadable date gives no time`() {
        val times = simklCalendarAirTimes(json.decodeFromString(SimklCalendarFile.serializer(), file))

        // 05:00Z in November and 04:00Z in July are both midnight in New York: no time known.
        assertNull(times["tmdb:20:1:7"])
        assertNull(times["tt3:2:1"])
        assertFalse(times.keys.any { it.startsWith("tt4") || it.startsWith("tt5") })
        assertEquals(2, times.size)
    }

    @Test
    fun `anime times are keyed by show and air date in Japan and China, ambiguous days left out`() {
        val anime = """
            {"calendar":[
              {"simkl_id":7,"date":"2026-11-26T15:00:00Z","episode":{"episode":17}},
              {"simkl_id":8,"date":"2026-11-27T10:00:00Z","episode":{"episode":3}},
              {"simkl_id":8,"date":"2026-11-27T12:00:00Z","episode":{"episode":4}}
            ],
            "metadata":{"7":{"ids":{"tmdb":"70","imdb":"tt7"}},"8":{"ids":{"imdb":"tt8"}}}}
        """.trimIndent()

        val times = simklAnimeAirTimes(json.decodeFromString(SimklCalendarFile.serializer(), anime))

        // 15:00Z is midnight on the 27th in Tokyo and 23:00 on the 26th in Shanghai.
        assertEquals("2026-11-26T15:00:00Z", times["date:tt7:2026-11-27"])
        assertEquals("2026-11-26T15:00:00Z", times["date:tmdb:70:2026-11-26"])
        // Two episodes of one show on one day: which time is which can't be told.
        assertNull(times["date:tt8:2026-11-27"])
    }
}
