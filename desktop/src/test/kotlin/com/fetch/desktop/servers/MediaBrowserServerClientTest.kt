package com.fetch.desktop.servers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaBrowserServerClientTest {

    private val publicInfo = """{"ServerName":"Den","Version":"10.9.11","ProductName":"Jellyfin Server","Id":"srv-1"}"""
    private val auth = """{"User":{"Id":"u1","Name":"bob"},"AccessToken":"tok-1","ServerId":"srv-1"}"""
    private val views = """{"Items":[
        {"Id":"lib-m","Name":"Movies","CollectionType":"movies"},
        {"Id":"lib-t","Name":"Shows","CollectionType":"tvshows"},
        {"Id":"lib-x","Name":"Music","CollectionType":"music"}]}"""

    private fun account(kind: ServerKind = ServerKind.JELLYFIN) = ServerAccount(
        id = "acc-1", kind = kind, name = "Den", address = "http://den:8096", serverId = "srv-1",
        userId = "u1", userName = "bob", token = "tok-1",
        libraries = listOf(ServerLib("lib-m", "Movies", "movie"), ServerLib("lib-t", "Shows", "series"), ServerLib("lib-off", "Off", "movie", selected = false))
    )

    private val movieItem = """{"Id":"m1","Name":"The Matrix","Type":"Movie","RunTimeTicks":81600000000,
        "ProviderIds":{"Imdb":"tt0133093"},
        "UserData":{"PlaybackPositionTicks":6000000000,"Played":false},
        "MediaSources":[{"Id":"src-4k","Path":"/media/The Matrix (1999)/The.Matrix.2160p.mkv","Size":50000000000,"Bitrate":60000000,
          "MediaStreams":[{"Type":"Video","Codec":"hevc","Width":3840,"Height":2160},{"Type":"Audio","Codec":"truehd","Channels":8,"IsDefault":true}]},
          {"Id":"src-hd","Path":"/media/The Matrix (1999)/The.Matrix.1080p.mkv","Size":10000000000,
          "MediaStreams":[{"Type":"Video","Codec":"h264","Width":1920,"Height":1080},{"Type":"Audio","Codec":"ac3","Channels":6}]}]}"""

    @Test
    fun `jellyfin sign-in sends identity, credentials and checks the version`() = runBlocking {
        val fake = FakeServerHttp { req ->
            when (req.url.encodedPath) {
                "/System/Info/Public" -> 200 to publicInfo
                "/Users/AuthenticateByName" -> 200 to auth
                "/UserViews" -> 200 to views
                else -> 404 to "{}"
            }
        }
        val client = MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev-1", "box")
        val account = client.signIn("den:8096/web/", "bob", "pw")

        assertEquals("http://den:8096", account.address)
        assertEquals("Den", account.name)
        assertEquals("srv-1", account.serverId)
        assertEquals("u1", account.userId)
        assertEquals("tok-1", account.token)
        assertEquals(listOf("lib-m" to "movie", "lib-t" to "series"), account.libraries.map { it.id to it.type })
        assertFalse(account.toString().contains("tok-1"))

        val login = fake.sentTo("/Users/AuthenticateByName").single()
        assertEquals("POST", login.request.method)
        assertTrue(login.body.contains("\"Username\":\"bob\"") && login.body.contains("\"Pw\":\"pw\""))
        val header = login.header("Authorization")!!
        assertTrue(header, header.startsWith("MediaBrowser Client=\"Fetch Desktop\", Device=\"box\", DeviceId=\"dev-1\", Version=\""))
        assertFalse(header.contains("Token="))
        assertTrue(fake.sentTo("/UserViews").single().header("Authorization")!!.endsWith("Token=\"tok-1\""))
        assertEquals("u1", fake.sentTo("/UserViews").single().query("userId"))
    }

    @Test
    fun `old jellyfin is rejected before the password is sent`() = runBlocking {
        val fake = FakeServerHttp { req ->
            if (req.url.encodedPath == "/System/Info/Public") 200 to """{"ServerName":"Old","Version":"10.8.13","Id":"x"}""" else 200 to auth
        }
        try {
            MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev-1").signIn("http://old", "bob", "pw")
            fail("expected ServerException")
        } catch (e: ServerException) {
            assertTrue(e.message!!, e.message!!.contains("10.9"))
        }
        assertTrue(fake.sentTo("/Users/AuthenticateByName").isEmpty())
    }

    @Test
    fun `wrong password is an auth error`() = runBlocking {
        val fake = FakeServerHttp { req -> if (req.url.encodedPath == "/System/Info/Public") 200 to publicInfo else 401 to "" }
        try {
            MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev-1").signIn("den", "bob", "bad")
            fail("expected ServerAuthException")
        } catch (e: ServerAuthException) {
            assertEquals(401, e.status)
        }
    }

    @Test
    fun `401 on a library call is ServerAuthException`() = runBlocking {
        val fake = FakeServerHttp { 401 to "" }
        try {
            MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev-1").libraries(account())
            fail("expected ServerAuthException")
        } catch (_: ServerAuthException) {
        }
    }

    @Test
    fun `movie streams found by title search and verified by IMDb id`() = runBlocking {
        val fake = FakeServerHttp { req ->
            when (req.url.encodedPath) {
                "/Items" -> 200 to """{"Items":[{"Id":"m2","Name":"The Matrix Reloaded","ProviderIds":{"Imdb":"tt0234215"}},
                    {"Id":"m1","Name":"The Matrix","ProviderIds":{"Imdb":"tt0133093"}}]}"""
                "/Items/m1" -> 200 to movieItem
                else -> 404 to "{}"
            }
        }
        val streams = MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev-1")
            .streams(account(), "tt0133093", "movie", null, null, "The Matrix", 1999)

        assertEquals(2, streams.size)
        val uhd = streams.first()
        assertEquals("m1", uhd.itemId)
        assertEquals("src-4k", uhd.mediaSourceId)
        assertTrue(uhd.url.startsWith("http://den:8096/Videos/m1/stream?"))
        assertTrue(uhd.url.contains("static=true"))
        assertTrue(uhd.url.contains("MediaSourceId=src-4k"))
        assertTrue(uhd.url.contains("api_key=tok-1"))
        assertEquals(50_000_000_000L, uhd.sizeBytes)
        assertEquals(2160, uhd.height)
        assertEquals("hevc", uhd.videoCodec)
        assertEquals("truehd", uhd.audioCodec)
        assertEquals(8, uhd.audioChannels)
        assertEquals(600_000L, uhd.resumePositionMs)
        assertEquals(8_160_000L, uhd.durationMs)
        assertEquals("The.Matrix.2160p.mkv", uhd.fileName)
        assertEquals(1080, streams[1].height)

        // Only the selected movie library is searched.
        val searches = fake.sentTo("/Items")
        assertEquals(listOf("lib-m"), searches.map { it.query("parentId") })
        assertEquals("The Matrix", searches.single().query("searchTerm"))
        assertEquals("Movie", searches.single().query("includeItemTypes"))
    }

    @Test
    fun `falls back to the library IMDb index when the search misses`() = runBlocking {
        val fake = FakeServerHttp { req ->
            when {
                req.url.encodedPath == "/Items" && req.url.queryParameter("searchTerm") != null -> 200 to """{"Items":[]}"""
                req.url.encodedPath == "/Items" -> 200 to """{"Items":[{"Id":"m1","ProviderIds":{"Imdb":"tt0133093"}}]}"""
                req.url.encodedPath == "/Items/m1" -> 200 to movieItem
                else -> 404 to "{}"
            }
        }
        val streams = MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev-1")
            .streams(account(), "tt0133093", "movie", null, null, "Matrix, The", null)
        assertEquals(2, streams.size)
        assertEquals("0", fake.sentTo("/Items").last().query("startIndex"))
    }

    @Test
    fun `episode streams go through the series and its episode list`() = runBlocking {
        val fake = FakeServerHttp { req ->
            when (req.url.encodedPath) {
                "/Items" -> 200 to """{"Items":[{"Id":"s1","Name":"Show","ProviderIds":{"Imdb":"tt0903747"}}]}"""
                "/Shows/s1/Episodes" -> 200 to """{"Items":[
                    {"Id":"e1","ParentIndexNumber":2,"IndexNumber":1},
                    {"Id":"e2","ParentIndexNumber":2,"IndexNumber":3},
                    {"Id":"e3","ParentIndexNumber":2,"IndexNumber":3,"LocationType":"Virtual"}]}"""
                "/Items/e2" -> 200 to """{"Id":"e2","Type":"Episode","UserData":{"PlaybackPositionTicks":0},
                    "MediaSources":[{"Id":"src-e2","Path":"C:\\TV\\Show.S02E03.720p.mkv","Size":1500000000,
                    "MediaStreams":[{"Type":"Video","Codec":"h264","Width":1280,"Height":720},{"Type":"Audio","Codec":"aac","Channels":2}]}]}"""
                else -> 404 to "{}"
            }
        }
        val streams = MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev-1")
            .streams(account(), "tt0903747", "series", 2, 3, "Show", 2008)

        val stream = streams.single()
        assertEquals("e2", stream.itemId)
        assertTrue(stream.url.contains("/Videos/e2/stream") && stream.url.contains("MediaSourceId=src-e2") && stream.url.contains("api_key=tok-1"))
        assertEquals(720, stream.height)
        assertEquals("Show.S02E03.720p.mkv", stream.fileName)
        assertNull(stream.resumePositionMs)
        assertEquals("2", fake.sentTo("/Shows/s1/Episodes").single().query("season"))
        assertEquals(listOf("lib-t"), fake.sentTo("/Items").map { it.query("parentId") })
    }

    @Test
    fun `emby uses its path prefix, header and provider id lookup`() = runBlocking {
        val fake = FakeServerHttp { req ->
            when (req.url.encodedPath) {
                "/emby/System/Info/Public" -> 200 to """{"ServerName":"Emby Den","Version":"4.8.0.0","Id":"e-srv"}"""
                "/emby/Users/AuthenticateByName" -> 200 to auth
                "/emby/Users/u1/Views" -> 200 to views
                "/emby/Users/u1/Items" -> 200 to """{"Items":[{"Id":"m1","ProviderIds":{"IMDB":"tt0133093"}}]}"""
                "/emby/Users/u1/Items/m1" -> 200 to movieItem
                else -> 404 to "{}"
            }
        }
        val client = MediaBrowserServerClient(ServerKind.EMBY, fake.client, "dev-1")
        val account = client.signIn("https://emby.example.com/emby/", "bob", "pw")
        assertEquals("https://emby.example.com", account.address)
        assertTrue(fake.sentTo("/emby/Users/AuthenticateByName").single().header("X-Emby-Authorization")!!.startsWith("MediaBrowser Client=\"Fetch Desktop\""))
        assertEquals(2, account.libraries.size)

        val streams = client.streams(account, "tt0133093", "movie", null, null, "The Matrix", 1999)
        assertEquals(2, streams.size)
        assertTrue(streams.first().url.startsWith("https://emby.example.com/emby/Videos/m1/stream?"))
        assertEquals("imdb.tt0133093", fake.sentTo("/emby/Users/u1/Items").first().query("anyProviderIdEquals"))
    }

    @Test
    fun `emby rejects a jellyfin server`() = runBlocking {
        val fake = FakeServerHttp { 200 to publicInfo }
        try {
            MediaBrowserServerClient(ServerKind.EMBY, fake.client, "dev-1").signIn("den", "bob", "pw")
            fail("expected ServerException")
        } catch (e: ServerException) {
            assertTrue(e.message!!.contains("Jellyfin"))
        }
    }

    @Test
    fun `latest keeps only items with an IMDb id`() = runBlocking {
        val fake = FakeServerHttp {
            200 to """{"Items":[
                {"Id":"m1","Name":"The Matrix","Type":"Movie","ProductionYear":1999,"Overview":"Neo","Genres":["Action"],
                 "ProviderIds":{"Imdb":"tt0133093"},"ImageTags":{"Primary":"p1"},"BackdropImageTags":["b1"]},
                {"Id":"m9","Name":"Home Video","Type":"Movie","ProviderIds":{}},
                {"Id":"m8","Name":"Tmdb only","Type":"Movie","ProviderIds":{"Tmdb":"603"}}]}"""
        }
        val previews = MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev-1")
            .latest(account(), ServerLib("lib-m", "Movies", "movie"), 10)
        val preview = previews.single()
        assertEquals("tt0133093", preview.id)
        assertEquals(com.fetch.tv.domain.model.ContentType.MOVIE, preview.type)
        assertEquals("1999", preview.releaseInfo)
        assertEquals(listOf("Action"), preview.genres)
        assertTrue(preview.poster!!.startsWith("http://den:8096/Items/m1/Images/Primary?tag=p1"))
        assertTrue(preview.background!!.contains("/Items/m1/Images/Backdrop/0"))
        assertEquals("DateCreated,SortName", fake.sent.single().query("sortBy"))
    }

    @Test
    fun `resume maps episodes to the series IMDb id`() = runBlocking {
        val fake = FakeServerHttp { req ->
            when (req.url.encodedPath) {
                "/UserItems/Resume" -> 200 to """{"Items":[
                    {"Id":"e2","Name":"Pilot","Type":"Episode","SeriesId":"s1","SeriesName":"Show","ParentIndexNumber":1,"IndexNumber":1,
                     "RunTimeTicks":36000000000,"ProviderIds":{"Imdb":"tt9999999"},
                     "UserData":{"PlaybackPositionTicks":12000000000,"LastPlayedDate":"2024-05-01T10:00:00.0000000Z"}},
                    {"Id":"m1","Name":"The Matrix","Type":"Movie","ProviderIds":{"Imdb":"tt0133093"},"UserData":{"PlaybackPositionTicks":10000}},
                    {"Id":"m9","Name":"Home","Type":"Movie","UserData":{"PlaybackPositionTicks":10000}}]}"""
                "/Items" -> 200 to """{"Items":[{"Id":"s1","ProviderIds":{"Imdb":"tt0903747"}}]}"""
                else -> 404 to "{}"
            }
        }
        val items = MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev-1").resume(account(), 20)
        assertEquals(2, items.size)
        val episode = items.first()
        assertEquals("tt0903747", episode.imdbId)
        assertEquals("series", episode.type)
        assertEquals("Show", episode.name)
        assertEquals(1, episode.season)
        assertEquals(1, episode.episode)
        assertEquals("Pilot", episode.episodeTitle)
        assertEquals(1_200_000L, episode.positionMs)
        assertEquals(3_600_000L, episode.durationMs)
        assertEquals(1_714_557_600_000L, episode.lastPlayedEpochMs)
        assertEquals("s1", fake.sentTo("/Items").single().query("ids"))
        assertEquals("movie", items[1].type)
    }

    @Test
    fun `progress and played reports hit the playback endpoints`() = runBlocking {
        val fake = FakeServerHttp { 204 to "" }
        val client = MediaBrowserServerClient(ServerKind.JELLYFIN, fake.client, "dev-1")
        val stream = ServerStream("acc-1", "m1", "src-4k", "u", null, null, null, null, null, null, null, null, null, null)
        assertTrue(client.reportPosition(account(), stream, 90_000L))
        assertTrue(client.markPlayed(account(), stream))
        val progress = fake.sentTo("/Sessions/Playing/Progress").single()
        assertTrue(progress.body.contains("\"PositionTicks\":900000000"))
        assertTrue(progress.body.contains("\"MediaSourceId\":\"src-4k\""))
        assertEquals("POST", fake.sentTo("/UserPlayedItems/m1").single().request.method)

        val offline = MediaBrowserServerClient(ServerKind.JELLYFIN, FakeServerHttp { null }.client, "dev-1")
        assertFalse(offline.markPlayed(account(), stream))
    }
}
