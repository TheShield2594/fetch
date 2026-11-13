package com.fetch.tv.data.mal

import com.fetch.tv.core.server.parseMalCallback
import com.fetch.tv.core.tracking.buildAnimeListSnapshot
import com.fetch.tv.data.anime.AnimeListStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MalTest {

    @Test
    fun `statuses map both ways, a rewatch counting as a repeat`() {
        assertEquals(AnimeListStatus.CURRENT, malStatusOf("watching", rewatching = false))
        assertEquals(AnimeListStatus.PAUSED, malStatusOf("on_hold", rewatching = false))
        assertEquals(AnimeListStatus.PLANNING, malStatusOf("plan_to_watch", rewatching = false))
        assertEquals(AnimeListStatus.REPEATING, malStatusOf("completed", rewatching = true))
        assertNull(malStatusOf("", rewatching = false))
        AnimeListStatus.entries.filter { it != AnimeListStatus.REPEATING }.forEach { status ->
            assertEquals(status, malStatusOf(malStatusName(status), rewatching = false))
        }
    }

    @Test
    fun `parses a list page into import entries keyed by MAL id`() {
        val page = JSONObject(
            """
            {"data":[
              {"node":{"id":5114,"title":"Fullmetal Alchemist: Brotherhood","media_type":"tv","main_picture":{"large":"https://p/l.jpg"}},
               "list_status":{"status":"completed","num_episodes_watched":64,"is_rewatching":false,"updated_at":"2023-11-14T22:13:20+00:00"}},
              {"node":{"id":199,"title":"A Film","media_type":"movie"},
               "list_status":{"status":"plan_to_watch","num_episodes_watched":0,"is_rewatching":false}}
            ],"paging":{}}
            """.trimIndent()
        )

        val entries = parseMalAnimeList(page)

        assertEquals(listOf(5114L, 199L), entries.map { it.malId })
        assertEquals(64, entries[0].progress)
        assertEquals(1_700_000_000L, entries[0].updatedAt)
        assertTrue(entries[1].isMovie)

        val snapshot = buildAnimeListSnapshot(entries)
        assertEquals(64, snapshot.watched.size)
        assertTrue(snapshot.watched.all { it.contentId == "mal:5114" })
    }

    @Test
    fun `the phone can paste the code or the whole callback address`() {
        assertEquals("def502abc", parseMalCallback("  def502abc ")?.code)
        assertNull(parseMalCallback("def502abc")?.state)

        val pasted = parseMalCallback("https://example.github.io/fetch/mal-callback.html?code=def%2B502&state=xyz")
        assertEquals("def+502", pasted?.code)
        assertEquals("xyz", pasted?.state)

        assertNull(parseMalCallback(""))
        assertNull(parseMalCallback("two words"))
        assertNull(parseMalCallback("https://example.github.io/mal-callback.html?error=access_denied&code="))
    }

    @Test
    fun `verifiers use only the characters PKCE allows`() {
        val verifier = MalApi.newVerifier()
        assertEquals(128, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
    }
}
