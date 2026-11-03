package com.fetch.desktop.mdblist

import com.fetch.desktop.ListPrivacy
import com.fetch.desktop.ListProvider
import com.fetch.desktop.ListsState
import com.fetch.desktop.TrackingState
import com.fetch.desktop.localListsFile
import com.fetch.desktop.tracking.FakeHttp
import com.fetch.desktop.tracking.TrackingStore
import com.fetch.desktop.tracking.testCredentials
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val USER_LISTS = """[
  {"id":11,"user_id":5,"name":"Favourites","type":"static","private":true,"mediatype":"movie","updated":"2024-01-01"},
  {"id":12,"user_id":5,"name":"Top rated","dynamic":true,"private":false},
  {"id":13,"user_id":9,"name":"Not mine","type":"static","private":false},
  {"id":14,"user_id":5,"name":"Episodes","type":"static","private":false,"mediatype":"episode"}
]"""

class MdbListAccountClientTest {
    private val auth = MdbListAuth.ApiKey("k")

    @Test
    fun `reads the account's own static and dynamic lists`() = runBlocking {
        val fake = MdbListFakeHttp.routes("/lists/user" to USER_LISTS)
        val lists = MdbListAccountClient(fake.http(), clientId = "").userLists(auth, accountId = 5)
        assertEquals(listOf("mdblist:list:11", "mdblist:dynamic:12"), lists.map { it.key })
        assertEquals("movie", lists[0].mediaType)
        assertTrue(lists[0].private)
        assertTrue(lists[0].writable)
        assertFalse("dynamic lists are read-only", lists[1].writable)
        assertEquals("false", fake.sent.single().query("unified"))
    }

    @Test
    fun `reads every page of a list's items, skipping episodes`() = runBlocking {
        val fake = MdbListFakeHttp { request, _ ->
            when (request.url.queryParameter("offset")) {
                null -> FakeReply(200, """{"items":[
                    {"mediatype":"movie","id":603,"imdb_id":"tt0133093","title":"The Matrix","release_year":1999,"poster":"/m.jpg","listed_at":"2024-02-01T10:00:00Z","rank":1},
                    {"mediatype":"episode","imdb_id":"tt9"}],
                  "pagination":{"offset":0,"limit":2,"total":3}}""")
                else -> FakeReply(200, """{"shows":[{"ids":{"imdb":"tt0903747","tmdb":1396},"title":"Breaking Bad","genres":[{"name":"Drama"}],"listed_at":"2024-03-01 08:00:00"}],
                  "pagination":{"offset":2,"limit":2,"total":3}}""")
            }
        }
        val items = MdbListAccountClient(fake.http(), "").items(MdbListList.WATCHLIST, auth)
        assertEquals(listOf("tt0133093", "tt0903747"), items.map { it.imdb })
        assertEquals("https://image.tmdb.org/t/p/w500/m.jpg", items[0].poster)
        assertEquals("show", items[1].type)
        assertEquals(listOf("Drama"), items[1].genres)
        assertTrue(items[1].listedAt > 0)
        assertEquals(listOf("/watchlist/items", "/watchlist/items"), fake.sent.map { it.path })
        assertEquals("2", fake.sent[1].query("offset"))

        val entry = items[1].toLibraryEntry()!!
        assertEquals("tt0903747", entry.id)
        assertEquals("series", entry.type)
    }

    @Test
    fun `adding to the watchlist wraps ids, a list takes them bare, and the reply is checked`() = runBlocking {
        val fake = MdbListFakeHttp { request, _ ->
            when {
                request.url.encodedPath.endsWith("/add") -> FakeReply(200, """{"added":{"movies":1},"existing":{"movies":0},"not_found":{"movies":0}}""")
                else -> FakeReply(200, """{"removed":{"shows":0},"not_found":{"shows":0}}""")
            }
        }
        val client = MdbListAccountClient(fake.http(), "")
        val movie = MdbListItem("movie", imdb = "tt1", tmdb = 2)
        client.setMember(MdbListList.WATCHLIST, movie, on = true, auth = auth)
        client.setMember(MdbListList(MdbListListKind.STATIC, 11, "Favourites"), movie, on = true, auth = auth)
        assertEquals("""{"movies":[{"ids":{"imdb":"tt1","tmdb":2}}]}""", fake.sent[0].body)
        assertEquals("/watchlist/items/add", fake.sent[0].path)
        assertEquals("""{"movies":[{"imdb":"tt1","tmdb":2}]}""", fake.sent[1].body)
        assertEquals("/lists/11/items/add", fake.sent[1].path)
        try {
            client.setMember(MdbListList.WATCHLIST, MdbListItem("show", imdb = "tt3"), on = false, auth = auth)
            fail("nothing was removed")
        } catch (_: MdbListException) {
        }
        try {
            client.setMember(MdbListList(MdbListListKind.EXTERNAL, 1, "Ext"), movie, on = true, auth = auth)
            fail("external lists are read-only")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `device sign-in polls until approved`() = runBlocking {
        var polls = 0
        val fake = MdbListFakeHttp { request, body ->
            when (request.url.encodedPath) {
                "/oauth/device-authorization/" -> FakeReply(200, """{"device_code":"dc","user_code":"ABCD","verification_uri":"https://mdblist.com/device","expires_in":600,"interval":5}""")
                "/oauth/token/" -> {
                    assertTrue(body.contains("client_id=cid"))
                    if (polls++ == 0) FakeReply(400, """{"error":"authorization_pending"}""")
                    else FakeReply(200, """{"access_token":"at","refresh_token":"rt","token_type":"Bearer","expires_in":3600,"scope":"read write"}""")
                }
                else -> FakeReply(404, "{}")
            }
        }
        val client = MdbListAccountClient(fake.http(), clientId = "cid")
        assertTrue(client.deviceSignInAvailable)
        val code = client.startDeviceAuth()
        assertEquals("ABCD", code.userCode)
        assertEquals("https://mdblist.com/device?user_code=ABCD", code.verificationUrlComplete)
        assertEquals(MdbListPoll.Pending, client.pollDeviceAuth("dc", now = 0))
        val authorized = client.pollDeviceAuth("dc", now = 0) as MdbListPoll.Authorized
        assertEquals("at", authorized.accessToken)
        assertEquals(3_600_000L, authorized.expiresAt)
        assertFalse(MdbListAccountClient(fake.http(), clientId = " ").deviceSignInAvailable)
    }

    @Test
    fun `a refused refresh token reads as unauthorized`() = runBlocking {
        val fake = MdbListFakeHttp { _, _ -> FakeReply(400, """{"error":"invalid_grant"}""") }
        val error = runCatching {
            MdbListAccountClient(fake.http(), "cid").refresh(MdbListAccount(5, accessToken = "a", refreshToken = "r"))
        }.exceptionOrNull() as MdbListException
        assertTrue(error.unauthorized)
    }

    @Test
    fun `a title becomes MDBList ids`() {
        val preview = MetaPreview(
            id = "tmdb:603", type = ContentType.MOVIE, name = "The Matrix", poster = null, posterShape = PosterShape.POSTER,
            background = null, logo = null, description = null, releaseInfo = "1999", imdbRating = null, genres = emptyList()
        )
        val item = MdbListAccountState.mdbListItemOf(preview)!!
        assertEquals(603L, item.tmdb)
        assertNull(item.imdb)
        assertEquals(1999, item.year)
        assertEquals("tt1", MdbListAccountState.mdbListItemOf(preview.copy(id = "tt1:1:1", type = ContentType.SERIES))!!.imdb)
        assertNull(MdbListAccountState.mdbListItemOf(preview.copy(id = "kitsu:1")))
        assertEquals("cid", MdbListCredentials.from(java.util.Properties().apply { setProperty("mdblistClientId", "\${mdblistClientId}") }) { "cid" })
    }
}

/** The account as a Library provider, through ListsState as the Library and Manage lists use it. */
class MdbListLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private val matrix = MetaPreview(
        id = "tt0133093", type = ContentType.MOVIE, name = "The Matrix", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = "1999", imdbRating = null, genres = emptyList()
    )

    private fun routes() = MdbListFakeHttp { request, _ ->
        val path = request.url.encodedPath
        when {
            path == "/user" -> FakeReply(200, """{"user_id":5,"username":"neo"}""")
            path == "/lists/user/add" -> FakeReply(200, """{"id":21,"name":"Weekend"}""")
            path == "/lists/user" -> FakeReply(200, USER_LISTS)
            path == "/external/lists/user" -> FakeReply(500, "{}")
            path == "/lists/liked" -> FakeReply(200, """{"lists":[{"id":31,"user_id":7,"name":"Someone's picks"}],"pagination":{"has_more":false}}""")
            path == "/watchlist/items" -> FakeReply(200, """{"movies":[],"shows":[{"ids":{"imdb":"tt0903747"},"title":"Breaking Bad"}]}""")
            path.endsWith("/items/add") -> FakeReply(200, """{"added":{"movies":1}}""")
            path.endsWith("/items") -> FakeReply(200, "[]")
            request.method == "DELETE" -> FakeReply(204, "")
            else -> FakeReply(404, "{}")
        }
    }

    private suspend fun settle() = delay(300)

    @Test
    fun `an API key account adds its lists to the Library, and titles to them`() = runBlocking {
        val fake = routes()
        val account = MdbListAccountState(scope, mdbListAccountFile(tmp.root), MdbListAccountClient(fake.http(), clientId = ""))
        val tracking = TrackingState(scope, TrackingStore(tmp.root.resolve("tracking.json")), testCredentials, FakeHttp.always(200, "[]").client)
        val lists = ListsState(scope, tracking, localListsFile(tmp.root), { emptyList() }, { _, _ -> }, onMessage = {}, mdblist = account)
        assertEquals(listOf(ListProvider.LOCAL), lists.providers)

        account.connectWithKey("key"); settle()
        assertEquals("neo", account.account?.userName)
        assertTrue(account.account!!.usesApiKey)
        assertEquals(listOf(ListProvider.LOCAL, ListProvider.MDBLIST), lists.providers)

        lists.refresh(); settle()
        val tabs = lists.lists(ListProvider.MDBLIST)
        assertEquals(listOf("Watchlist", "Favourites", "Top rated", "Someone's picks"), tabs.map { it.title })
        assertEquals(listOf("tt0903747"), tabs[0].items.map { it.id })
        assertEquals("key", fake.sent.first { it.path == "/watchlist/items" }.query("apikey"))
        // Favourites takes movies only; dynamic and liked lists can't be picked.
        assertEquals(listOf("mdblist:watchlist", "mdblist:list:11"), lists.destinations(matrix).filter { it.provider == ListProvider.MDBLIST }.map { it.key })
        assertEquals(ListPrivacy.PRIVATE, tabs[1].privacy)
        assertTrue(tabs[1].editable)

        lists.apply(matrix, mapOf("mdblist:list:11" to true)); settle()
        assertTrue(lists.isMember(lists.lists(ListProvider.MDBLIST)[1], matrix.id))
        assertEquals("""{"movies":[{"imdb":"tt0133093"}]}""", fake.sent.last { it.path == "/lists/11/items/add" }.body)

        // Hiding a list drops it from the Library and Manage lists, and is remembered.
        account.setListVisible("mdblist:liked:31", false)
        assertFalse(lists.lists(ListProvider.MDBLIST).any { it.key == "mdblist:liked:31" })
        assertEquals(setOf("mdblist:liked:31"), mdbListAccountFile(tmp.root).load().hiddenLists)

        lists.create(ListProvider.MDBLIST, "Weekend", null, ListPrivacy.PUBLIC); settle()
        val created = Json.parseToJsonElement(fake.sent.last { it.path == "/lists/user/add" }.body).jsonObject
        assertEquals("false", created["private"].toString())
        assertNotNull(lists.lists(ListProvider.MDBLIST).firstOrNull { it.title == "Weekend" })

        lists.delete(lists.lists(ListProvider.MDBLIST).first { it.title == "Weekend" }); settle()
        assertTrue(fake.sent.any { it.method == "DELETE" && it.path == "/lists/21" })
        assertFalse(lists.lists(ListProvider.MDBLIST).any { it.title == "Weekend" })

        val perms = java.nio.file.Files.getPosixFilePermissions(tmp.root.resolve("mdblist.json").toPath())
        assertEquals("the key is readable by its owner only", "rw-------", java.nio.file.attribute.PosixFilePermissions.toString(perms))

        account.signOut()
        assertEquals(listOf(ListProvider.LOCAL), lists.providers)
        assertNull(mdbListAccountFile(tmp.root).load().account)
    }

    @Test
    fun `a rejected key doesn't connect`() = runBlocking {
        val fake = MdbListFakeHttp { _, _ -> FakeReply(401, """{"error":"invalid_api_key"}""") }
        val messages = mutableListOf<String>()
        val account = MdbListAccountState(scope, mdbListAccountFile(tmp.root), MdbListAccountClient(fake.http(), ""), onMessage = { messages += it })
        account.connectWithKey("bad"); settle()
        assertFalse(account.connected)
        assertEquals(listOf("MDBList didn't accept this API key"), messages)
    }

    @Test
    fun `an expiring token is refreshed before use`() = runBlocking {
        var now = 1_000_000L
        val fake = MdbListFakeHttp { request, _ ->
            when (request.url.encodedPath) {
                "/oauth/token/" -> FakeReply(200, """{"access_token":"new","token_type":"Bearer","expires_in":3600}""")
                else -> FakeReply(404, "{}")
            }
        }
        val store = mdbListAccountFile(tmp.root).apply {
            save(MdbListAccountFile(MdbListAccount(5, "neo", accessToken = "old", refreshToken = "rt", expiresAt = now + 30_000)))
        }
        val account = MdbListAccountState(scope, store, MdbListAccountClient(fake.http(), "cid"), clock = { now })
        assertEquals(MdbListAuth.Bearer("new"), account.auth())
        assertEquals("rt", store.load().account?.refreshToken)
        assertEquals(now + 3_600_000, store.load().account?.expiresAt)
        now += 1_000
        assertEquals(MdbListAuth.Bearer("new"), account.auth())
        assertEquals(1, fake.sent.size)
    }
}
