package com.fetch.desktop.mdblist

import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.LibrarySort
import com.fetch.desktop.data.PlaybackTarget
import com.fetch.desktop.sync.ProviderCredentialSync
import com.fetch.desktop.sync.providerCredentialBaseFile
import com.fetch.desktop.tracking.ScrobbleAction
import com.fetch.tv.domain.model.Video
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MdbListSyncTest {
    private val auth = MdbListAuth.ApiKey("k")
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `playback becomes Continue Watching, finished sessions don't`() = runBlocking {
        val fake = MdbListFakeHttp.routes(
            "/sync/playback" to """[
              {"id":1,"type":"movie","progress":42.5,"paused_at":"2024-05-01T10:00:00Z","movie":{"title":"Heat","ids":{"imdb":"tt0113277"}}},
              {"id":2,"type":"episode","progress":10,"paused_at":"2024-05-02T10:00:00Z","show":{"title":"Lost","ids":{"imdb":"tt0411008"}},"episode":{"season":1,"number":3,"title":"Tabula Rasa"}},
              {"id":3,"type":"movie","progress":95,"paused_at":"2024-05-03T10:00:00Z","movie":{"ids":{"imdb":"tt1"}}},
              {"id":4,"type":"movie","progress":5,"paused_at":"2024-05-03T10:00:00Z","movie":{"ids":{"tmdb":5}}}
            ]"""
        )
        val playback = MdbListSyncClient(fake.http()).playback(auth)
        assertEquals(3, playback.size)
        val entries = playback.mapNotNull { it.toProgressEntry() }
        assertEquals(listOf("tt0113277", "tt0411008:1:3"), entries.map { it.videoId })
        assertEquals(42.5f, entries[0].remotePercent)
        assertEquals("mdblist", entries[1].source)
        assertEquals("Tabula Rasa", entries[1].episodeTitle)
    }

    @Test
    fun `watched history reads movies, shows' seasons and episodes across pages`() = runBlocking {
        val fake = MdbListFakeHttp { request, _ ->
            when (request.url.queryParameter("cursor")) {
                null -> FakeReply(200, """{"movies":[{"movie":{"ids":{"imdb":"tt1"}},"last_watched_at":"2024-01-01T00:00:00Z"}],
                    "shows":[{"show":{"ids":{"imdb":"tt2"}},"seasons":[{"number":1,"episodes":[{"number":1},{"number":2}]}]}],
                    "pagination":{"next_cursor":"c2"}}""")
                else -> FakeReply(200, """{"episodes":[{"episode":{"season":2,"number":5},"show":{"ids":{"imdb":"tt2"}}}],"pagination":{"has_more":false}}""")
            }
        }
        val watched = MdbListSyncClient(fake.http()).watched(auth)
        assertEquals(setOf("tt1", "tt2:1:1", "tt2:1:2", "tt2:2:5"), watched.videoIds())
        assertEquals(2, fake.sent.size)
        assertEquals("c2", fake.sent[1].query("cursor"))
    }

    @Test
    fun `scrobbles and history changes send the TV's bodies`() = runBlocking {
        val fake = MdbListFakeHttp { _, _ -> FakeReply(200, "{}") }
        val client = MdbListSyncClient(fake.http())
        val episode = PlaybackTarget("tt2", "series", "Lost", null, null, null,
            Video(id = "tt2:1:3", title = "", released = null, thumbnail = null, season = 1, episode = 3, overview = null))
        client.scrobble(auth, ScrobbleAction.PAUSE, MdbListTarget.of(episode)!!, 33.339)
        assertEquals("/scrobble/pause", fake.sent[0].path)
        assertEquals(json("""{"show":{"ids":{"imdb":"tt2"},"season":1,"episode":3},"progress":33.33}"""), json(fake.sent[0].body))

        val movie = MdbListTarget.of(PlaybackTarget("tt1", "movie", "Heat", null, null, null, null))!!
        client.setWatched(auth, listOf(movie, MdbListTarget.of(episode)!!), watched = true, nowMs = 0)
        assertEquals("/sync/watched", fake.sent[1].path)
        assertEquals("true", fake.sent[1].query("report_added"))
        assertEquals(
            json("""{"movies":[{"ids":{"imdb":"tt1"},"watched_at":"1970-01-01T00:00:00Z"}],
                "shows":[{"ids":{"imdb":"tt2"},"seasons":[{"number":1,"episodes":[{"number":3,"watched_at":"1970-01-01T00:00:00Z"}]}]}]}"""),
            json(fake.sent[1].body)
        )
        client.setWatched(auth, listOf(movie), watched = false)
        assertEquals("/sync/watched/remove", fake.sent[2].path)
        assertEquals(json("""{"movies":[{"ids":{"imdb":"tt1"}}]}"""), json(fake.sent[2].body))
        // Titles without an IMDb id aren't sent.
        assertNull(MdbListTarget.of(PlaybackTarget("tmdb:5", "movie", "X", null, null, null, null)))
    }

    @Test
    fun `release order comes from MDBList and reorders the list`() {
        fun entry(id: String) = LibraryEntry(id = id, type = "movie", name = id)
        val ordered = LibrarySort.byOrder(listOf(entry("a"), entry("b"), entry("c")), listOf("c", "a")) { it.id }
        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
    }

    @Test
    fun `provider credentials - the account's key comes down, a change here goes up`() = runBlocking {
        val base = providerCredentialBaseFile(Files.createTempDirectory("pc").toFile())
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var remoteKey: String? = null
        val rpc: suspend (String, JsonObject) -> String = { name, params ->
            calls += name to params
            when (name) {
                "sync_pull_provider_credentials" -> remoteKey?.let {
                    """[{"provider":"mdblist","credential_json":{"api_key":"$it"}},{"provider":"animeskip","credential_json":{"client_id":"as-1"}}]"""
                } ?: "[]"
                else -> "null"
            }
        }
        // An empty account is seeded with this device's key.
        assertEquals("local", ProviderCredentialSync.sync(rpc, 2, "local", base, "origin"))
        assertEquals(listOf("sync_pull_provider_credentials", "sync_seed_provider_credentials"), calls.map { it.first })
        assertEquals(json("""{"p_profile_id":2,"p_credentials":[{"provider":"mdblist","credential_json":{"api_key":"local"}},
            {"provider":"animeskip","credential_json":{"client_id":""}}],"p_origin_client_id":"origin"}"""), calls[1].second)

        // The TV's key wins on the next pull.
        calls.clear()
        remoteKey = "from-tv"
        assertEquals("from-tv", ProviderCredentialSync.sync(rpc, 2, "local", base, null))
        assertEquals(listOf("sync_pull_provider_credentials"), calls.map { it.first })

        // A key changed here goes up, keeping the account's AnimeSkip id.
        calls.clear()
        remoteKey = "mine"
        assertEquals("mine", ProviderCredentialSync.sync(rpc, 2, "mine", base, null))
        assertEquals("sync_push_provider_credentials", calls.first().first)
        assertTrue(calls.first().second.toString().contains("as-1"))
    }
}
