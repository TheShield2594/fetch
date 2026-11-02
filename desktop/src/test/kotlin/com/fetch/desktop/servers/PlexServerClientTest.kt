package com.fetch.desktop.servers

import com.fetch.tv.domain.model.ContentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlexServerClientTest {

    private fun account() = ServerAccount(
        id = "acc-p", kind = ServerKind.PLEX, name = "Plexy", address = "https://10-0-0-5.abc.plex.direct:32400",
        serverId = "mach-1", userId = "42", userName = "bob", token = "srv-tok",
        libraries = listOf(ServerLib("1", "Movies", "movie"), ServerLib("2", "TV", "series"))
    )

    @Test
    fun `link start and poll`() = runBlocking {
        var approved = false
        val fake = FakeServerHttp { req ->
            when (req.url.encodedPath) {
                "/api/v2/pins" -> 201 to """{"id":777,"code":"ABCD","expiresIn":600}"""
                "/api/v2/pins/777" -> 200 to (if (approved) """{"id":777,"code":"ABCD","authToken":"acct-tok"}""" else """{"id":777,"code":"ABCD","authToken":null}""")
                else -> 404 to "{}"
            }
        }
        val client = PlexServerClient(fake.client, "dev-1")
        val link = client.startLink()
        assertEquals(777L, link.id)
        assertEquals("ABCD", link.code)
        assertEquals("https://plex.tv/link", link.url)
        assertTrue(link.expiresAtEpochMs > System.currentTimeMillis() + 500_000)
        val start = fake.sentTo("/api/v2/pins").single()
        assertEquals("POST", start.request.method)
        assertEquals("plex.tv", start.request.url.host)
        assertEquals("Fetch Desktop", start.header("X-Plex-Product"))
        assertEquals("dev-1", start.header("X-Plex-Client-Identifier"))
        assertNull(start.header("X-Plex-Token"))

        assertNull(client.pollLink(link))
        approved = true
        assertEquals("acct-tok", client.pollLink(link))
        assertEquals("ABCD", fake.sentTo("/api/v2/pins/777").last().query("code"))
    }

    @Test
    fun `an expired link throws`() = runBlocking {
        val client = PlexServerClient(FakeServerHttp { 404 to "" }.client, "dev-1")
        try {
            client.pollLink(PlexLink(1, "X", expiresAtEpochMs = System.currentTimeMillis() + 60_000))
            fail("expected ServerException")
        } catch (e: ServerException) {
            assertTrue(e.message!!.contains("expired"))
        }
        try {
            client.pollLink(PlexLink(1, "X", expiresAtEpochMs = 1))
            fail("expected ServerException")
        } catch (e: ServerException) {
            assertTrue(e.message!!.contains("expired"))
        }
    }

    @Test
    fun `servers picks a reachable connection, the server token and libraries`() = runBlocking {
        val fake = FakeServerHttp { req ->
            val host = req.url.host
            when {
                req.url.encodedPath == "/api/v2/user" -> 200 to """{"id":42,"username":"bob","email":"b@x"}"""
                req.url.encodedPath == "/api/v2/resources" -> 200 to """[
                    {"name":"Plexy","product":"Plex Media Server","productVersion":"1.40.2.8395-c67dce28e","clientIdentifier":"mach-1",
                     "provides":"server","accessToken":"srv-tok","connections":[
                       {"uri":"https://relay.plex.direct:8443","local":false,"relay":true},
                       {"uri":"https://1-2-3-4.abc.plex.direct:32400","local":false,"relay":false},
                       {"uri":"https://10-0-0-5.abc.plex.direct:32400","local":true,"relay":false},
                       {"uri":"https://10-0-0-9.abc.plex.direct:32400","local":true,"relay":false}]},
                    {"name":"Old","productVersion":"1.20.0","clientIdentifier":"mach-old","provides":"server","connections":[{"uri":"http://old:32400"}]},
                    {"name":"Phone","clientIdentifier":"player","provides":"player,client","connections":[]}]"""
                // The first local address is down, the second is another server: the remote one wins.
                host == "10-0-0-5.abc.plex.direct" -> null
                host == "10-0-0-9.abc.plex.direct" && req.url.encodedPath == "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"someone-else"}}"""
                req.url.encodedPath == "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"mach-1","version":"1.40.2"}}"""
                req.url.encodedPath == "/library/sections" -> 200 to """{"MediaContainer":{"Directory":[
                    {"key":"1","title":"Movies","type":"movie"},{"key":"2","title":"TV","type":"show"},{"key":"3","title":"Music","type":"artist"}]}}"""
                else -> 404 to "{}"
            }
        }
        val servers = PlexServerClient(fake.client, "dev-1").servers("acct-tok")
        val server = servers.single()
        assertEquals("Plexy", server.name)
        assertEquals("https://1-2-3-4.abc.plex.direct:32400", server.address)
        assertEquals("mach-1", server.serverId)
        assertEquals("42", server.userId)
        assertEquals("bob", server.userName)
        assertEquals("srv-tok", server.token)
        assertEquals(ServerKind.PLEX, server.kind)
        assertEquals(listOf("1" to "movie", "2" to "series"), server.libraries.map { it.id to it.type })
        assertEquals("acct-tok", fake.sentTo("/api/v2/resources").single().header("X-Plex-Token"))
        assertEquals("srv-tok", fake.sentTo("/library/sections").single().header("X-Plex-Token"))
        assertTrue(fake.sent.none { it.request.url.host == "old" })
    }

    @Test
    fun `connections are ordered local, remote, relay`() {
        val ordered = PlexServerClient.orderedConnections(
            listOf(
                PlexConnection("r", relay = true),
                PlexConnection("w6", ipv6 = true),
                PlexConnection("w"),
                PlexConnection("l", local = true)
            )
        )
        assertEquals(listOf("l", "w", "w6", "r"), ordered.map { it.uri })
    }

    @Test
    fun `movie streams via IMDb guid`() = runBlocking {
        val fake = FakeServerHttp { req ->
            when (req.url.encodedPath) {
                "/library/sections/1/all" -> 200 to """{"MediaContainer":{"Metadata":[
                    {"ratingKey":"55","title":"The Matrix Reloaded","Guid":[{"id":"imdb://tt0234215"}]},
                    {"ratingKey":"54","title":"The Matrix","guid":"plex://movie/5d77","Guid":[{"id":"imdb://tt0133093"},{"id":"tmdb://603"}]}]}}"""
                "/library/metadata/54" -> 200 to """{"MediaContainer":{"Metadata":[{"ratingKey":"54","type":"movie","title":"The Matrix",
                    "duration":8160000,"viewOffset":120000,
                    "Media":[{"id":901,"width":3840,"height":2160,"bitrate":60000,"videoCodec":"hevc","audioCodec":"truehd","audioChannels":8,
                      "Part":[{"id":1001,"key":"/library/parts/1001/1600000000/file.mkv","file":"/data/Movies/The.Matrix.2160p.mkv","size":50000000000}]}]}]}}"""
                else -> 404 to "{}"
            }
        }
        val stream = PlexServerClient(fake.client, "dev-1")
            .streams(account(), "tt0133093", "movie", null, null, "The Matrix", 1999)
            .single()
        assertEquals("54", stream.itemId)
        assertEquals("901", stream.mediaSourceId)
        assertEquals("https://10-0-0-5.abc.plex.direct:32400/library/parts/1001/1600000000/file.mkv?X-Plex-Token=srv-tok", stream.url)
        assertEquals(50_000_000_000L, stream.sizeBytes)
        assertEquals(2160, stream.height)
        assertEquals(60_000_000L, stream.bitrate)
        assertEquals(120_000L, stream.resumePositionMs)
        assertEquals(8_160_000L, stream.durationMs)
        assertEquals("The.Matrix.2160p.mkv", stream.fileName)
        val search = fake.sentTo("/library/sections/1/all").single()
        assertEquals("The Matrix", search.query("title"))
        assertEquals("1", search.query("type"))
        assertEquals("1", search.query("includeGuids"))
    }

    @Test
    fun `episode streams via the show's leaves`() = runBlocking {
        val fake = FakeServerHttp { req ->
            when (req.url.encodedPath) {
                "/library/sections/2/all" -> 200 to """{"MediaContainer":{"Metadata":[{"ratingKey":"70","Guid":[{"id":"imdb://tt0903747"}]}]}}"""
                "/library/metadata/70/allLeaves" -> 200 to """{"MediaContainer":{"Metadata":[
                    {"ratingKey":"71","parentIndex":1,"index":1,"Media":[{"id":1}]},
                    {"ratingKey":"72","parentIndex":1,"index":2,"Media":[{"id":2}]}]}}"""
                "/library/metadata/72" -> 200 to """{"MediaContainer":{"Metadata":[{"ratingKey":"72","type":"episode",
                    "Media":[{"id":2,"height":1080,"Part":[{"id":5,"key":"/library/parts/5/1/file.mkv","file":"/tv/S01E02.mkv","size":2000}]}]}]}}"""
                else -> 404 to "{}"
            }
        }
        val stream = PlexServerClient(fake.client, "dev-1").streams(account(), "tt0903747", "series", 1, 2, "Breaking Bad", 2008).single()
        assertEquals("72", stream.itemId)
        assertTrue(stream.url.contains("/library/parts/5/1/file.mkv"))
    }

    @Test
    fun `latest keeps only IMDb items and tokenises images`() = runBlocking {
        val fake = FakeServerHttp {
            200 to """{"MediaContainer":{"Metadata":[
                {"ratingKey":"54","type":"movie","title":"The Matrix","year":1999,"thumb":"/library/metadata/54/thumb/1","art":"/a",
                 "Genre":[{"tag":"Action"}],"Guid":[{"id":"imdb://tt0133093"}]},
                {"ratingKey":"60","type":"movie","title":"Legacy","guid":"com.plexapp.agents.imdb://tt0111161?lang=en"},
                {"ratingKey":"61","type":"movie","title":"Home video","guid":"local://61"}]}}"""
        }
        val previews = PlexServerClient(fake.client, "dev-1").latest(account(), ServerLib("1", "Movies", "movie"), 10)
        assertEquals(listOf("tt0133093", "tt0111161"), previews.map { it.id })
        assertEquals(ContentType.MOVIE, previews[0].type)
        assertEquals("1999", previews[0].releaseInfo)
        assertTrue(previews[0].poster!!.contains("/photo/:/transcode") && previews[0].poster!!.contains("X-Plex-Token=srv-tok"))
        assertEquals("addedAt:desc", fake.sent.single().query("sort"))
    }

    @Test
    fun `resume falls back to on deck and uses the show's IMDb id`() = runBlocking {
        val fake = FakeServerHttp { req ->
            when (req.url.encodedPath) {
                "/hubs/continueWatching/items" -> 404 to ""
                "/library/onDeck" -> 200 to """{"MediaContainer":{"Metadata":[
                    {"ratingKey":"72","type":"episode","title":"Cat's in the Bag","grandparentTitle":"Breaking Bad","grandparentRatingKey":"70",
                     "parentIndex":1,"index":2,"viewOffset":60000,"duration":2900000,"lastViewedAt":1700000000,"librarySectionID":2}]}}"""
                "/library/metadata/70" -> 200 to """{"MediaContainer":{"Metadata":[{"ratingKey":"70","Guid":[{"id":"imdb://tt0903747"}]}]}}"""
                else -> 404 to "{}"
            }
        }
        val item = PlexServerClient(fake.client, "dev-1").resume(account(), 10).single()
        assertEquals("tt0903747", item.imdbId)
        assertEquals("series", item.type)
        assertEquals("Breaking Bad", item.name)
        assertEquals(1, item.season)
        assertEquals(2, item.episode)
        assertEquals(60_000L, item.positionMs)
        assertEquals(1_700_000_000_000L, item.lastPlayedEpochMs)
    }

    @Test
    fun `401 from a Plex server is ServerAuthException`() = runBlocking {
        try {
            PlexServerClient(FakeServerHttp { 401 to "" }.client, "dev-1").libraries(account())
            fail("expected ServerAuthException")
        } catch (_: ServerAuthException) {
        }
    }

    @Test
    fun `timeline and scrobble reports`() = runBlocking {
        val fake = FakeServerHttp { 200 to "" }
        val client = PlexServerClient(fake.client, "dev-1")
        val stream = ServerStream("acc-p", "54", "901", "u", null, null, null, null, null, null, null, null, 8_160_000L, null)
        assertTrue(client.reportPosition(account(), stream, 30_000L))
        assertTrue(client.markPlayed(account(), stream))
        val timeline = fake.sentTo("/:/timeline").single()
        assertEquals("54", timeline.query("ratingKey"))
        assertEquals("30000", timeline.query("time"))
        assertEquals("playing", timeline.query("state"))
        assertEquals("8160000", timeline.query("duration"))
        assertEquals("54", fake.sentTo("/:/scrobble").single().query("key"))
    }
}
