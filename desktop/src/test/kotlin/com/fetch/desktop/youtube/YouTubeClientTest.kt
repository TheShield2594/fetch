package com.fetch.desktop.youtube

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

class YouTubeClientTest {

    private var clock = 1_000_000L
    private val signedIn = YouTubeAuth(accessToken = "AT", refreshToken = "RT", accessTokenExpiresAt = clock + 3_600_000L, accountName = "Ada")

    private fun client(http: YouTubeFakeHttp, auth: YouTubeAuthStore = InMemoryYouTubeAuthStore(signedIn)) =
        YouTubeClient(http.client, auth, locale = { Locale.UK }, now = { clock })

    /** youtube.com/tv can't be read here, so the known TV client is used, as after a failed read. */
    private fun routes(innertube: (String, SentRequest) -> String = { _, _ -> "{}" }, token: () -> Pair<Int, String> = { 400 to "{}" }) =
        YouTubeFakeHttp { req ->
            when {
                req.path == "/tv" -> 404 to ""
                req.path == "/o/oauth2/device/code" ->
                    200 to """{"device_code":"DEV","user_code":"ABC-DEF","verification_url":"https://www.youtube.com/activate","expires_in":1800,"interval":5}"""
                req.path == "/o/oauth2/token" -> token()
                req.path.startsWith("/youtubei/v1/") -> 200 to innertube(req.path.removePrefix("/youtubei/v1/"), req)
                else -> 200 to ""
            }
        }

    private fun bodyOf(req: SentRequest) = Json.parseToJsonElement(req.body).jsonObject

    // --- Sign-in: Google's device flow for TVs, as the TV app does it ---

    @Test
    fun `sign-in asks for a code as YouTube's TV app`() = runBlocking {
        val http = routes()
        val code = client(http, InMemoryYouTubeAuthStore()).startDeviceAuth()
        assertEquals("ABC-DEF", code.userCode)
        assertEquals("https://www.youtube.com/activate", code.verificationUrl)
        assertEquals(YouTubeClient.CLIENT_ID, code.clientId)
        val body = bodyOf(http.sent.single { it.path == "/o/oauth2/device/code" })
        assertEquals(YouTubeClient.CLIENT_ID, body["client_id"]!!.jsonPrimitive.content)
        assertEquals(YouTubeClient.SCOPE, body["scope"]!!.jsonPrimitive.content)
        assertEquals("ytlr::", body["model_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an approved code saves the tokens with the client and names the account`() = runBlocking {
        val auth = InMemoryYouTubeAuthStore()
        var answer = 428 to """{"error":"authorization_pending"}"""
        val http = routes(innertube = { endpoint, _ -> if (endpoint == "account/accounts_list") ACCOUNTS_JSON else "{}" }, token = { answer })
        val youtube = client(http, auth)
        val code = youtube.startDeviceAuth()
        assertEquals(YouTubeTokenPollResult.Pending, youtube.pollDeviceToken(code))
        assertFalse(auth.load().isSignedIn)

        answer = 200 to """{"access_token":"AT1","refresh_token":"RT1","expires_in":3600}"""
        assertEquals(YouTubeTokenPollResult.Approved("Ada"), youtube.pollDeviceToken(code))
        val saved = auth.load()
        assertEquals("RT1", saved.refreshToken)
        assertEquals(clock + 3_600_000L, saved.accessTokenExpiresAt)
        assertEquals(YouTubeClient.CLIENT_ID, saved.clientId)
        assertEquals("Ada", saved.accountName)
        assertEquals("@ada", saved.accountHandle)
        assertNull("the Google account's own channel", saved.pageId)
        val poll = bodyOf(http.sent.last { it.path == "/o/oauth2/token" })
        assertEquals(YouTubeClient.DEVICE_GRANT_TYPE, poll["grant_type"]!!.jsonPrimitive.content)
        assertEquals("DEV", poll["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `denied and expired codes say so`() = runBlocking {
        var answer = 400 to """{"error":"access_denied"}"""
        val youtube = client(routes(token = { answer }), InMemoryYouTubeAuthStore())
        val code = youtube.startDeviceAuth()
        assertEquals(YouTubeTokenPollResult.Denied, youtube.pollDeviceToken(code))
        answer = 400 to """{"error":"expired_token"}"""
        assertEquals(YouTubeTokenPollResult.Expired, youtube.pollDeviceToken(code))
        answer = 400 to """{"error":"slow_down","interval":5}"""
        assertEquals(YouTubeTokenPollResult.SlowDown(10), youtube.pollDeviceToken(code))
    }

    @Test
    fun `signing out forgets the sign-in and revokes it`() = runBlocking {
        val auth = InMemoryYouTubeAuthStore(signedIn)
        val http = routes()
        client(http, auth).signOut()
        assertFalse(auth.load().isSignedIn)
        val revoke = http.sent.single { it.host == "oauth2.googleapis.com" }
        assertEquals("RT", revoke.request.url.queryParameter("token"))
    }

    // --- Tokens ---

    @Test
    fun `an expiring access token is refreshed before the request, with the sign-in's client`() = runBlocking {
        val auth = InMemoryYouTubeAuthStore(signedIn.copy(accessTokenExpiresAt = clock + 30_000L, clientId = "cid", clientSecret = "cs"))
        val http = routes(innertube = { _, _ -> subscriptionsJson() }, token = { 200 to """{"access_token":"AT2","expires_in":3600}""" })
        client(http, auth).loadFeed(YouTubeFeed.SUBSCRIPTIONS)
        val refresh = bodyOf(http.sent.single { it.path == "/o/oauth2/token" })
        assertEquals("refresh_token", refresh["grant_type"]!!.jsonPrimitive.content)
        assertEquals("RT", refresh["refresh_token"]!!.jsonPrimitive.content)
        assertEquals("cid", refresh["client_id"]!!.jsonPrimitive.content)
        assertEquals("Bearer AT2", http.innertube("browse").single().request.header("Authorization"))
        assertEquals("the refresh token stays", "RT", auth.load().refreshToken)
        assertEquals("AT2", auth.load().accessToken)
    }

    @Test
    fun `a revoked sign-in signs the profile out`() = runBlocking {
        val auth = InMemoryYouTubeAuthStore(signedIn.copy(accessTokenExpiresAt = 0L))
        val youtube = client(routes(token = { 400 to """{"error":"invalid_grant"}""" }), auth)
        try {
            youtube.loadFeed(YouTubeFeed.HOME)
            fail("expected the sign-in to be gone")
        } catch (_: YouTubeSignedOutException) {
        }
        assertFalse(auth.load().isSignedIn)
    }

    @Test
    fun `nothing is asked of YouTube while signed out`() = runBlocking {
        val http = routes()
        try {
            client(http, InMemoryYouTubeAuthStore()).loadFeed(YouTubeFeed.SUBSCRIPTIONS)
            fail("expected signed out")
        } catch (_: YouTubeSignedOutException) {
        }
        assertTrue(http.innertube("browse").isEmpty())
    }

    // --- Feeds ---

    @Test
    fun `subscriptions are the TV browse feed, with its tiles and next page`() = runBlocking {
        val http = routes(innertube = { _, _ -> subscriptionsJson() })
        val page = client(http).loadFeed(YouTubeFeed.SUBSCRIPTIONS)
        val videos = page.shelves.flatMap { it.videos }
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), videos.map { it.videoId })
        assertEquals("Channel 0", videos[0].channelName)
        assertEquals("UCchan0", videos[0].channelId)
        assertEquals(40, videos[0].watchedPercent)
        assertTrue(videos[1].isShort)
        assertTrue(videos[2].isLive)
        assertEquals("FB_aaaaaaaaaaa", videos[0].notInterestedToken)
        assertEquals("NEXT_PAGE", page.continuation)

        val sent = http.innertube("browse").single()
        val body = bodyOf(sent)
        assertEquals(YouTubeClient.SUBSCRIPTIONS_BROWSE_ID, body["browseId"]!!.jsonPrimitive.content)
        val clientContext = body["context"]!!.jsonObject["client"]!!.jsonObject
        assertEquals("TVHTML5", clientContext["clientName"]!!.jsonPrimitive.content)
        assertEquals("en", clientContext["hl"]!!.jsonPrimitive.content)
        assertEquals("GB", clientContext["gl"]!!.jsonPrimitive.content)
        assertEquals("Bearer AT", sent.request.header("Authorization"))
        assertNull(sent.request.header("X-Goog-Pageid"))
    }

    @Test
    fun `history tiles take the remove-from-history token`() = runBlocking {
        val http = routes(innertube = { _, _ -> subscriptionsJson() })
        val page = client(http).loadFeed(YouTubeFeed.HISTORY)
        val first = page.shelves.flatMap { it.videos }.first()
        assertEquals("FB_aaaaaaaaaaa", first.removeFromHistoryToken)
        assertNull(first.notInterestedToken)
        assertEquals(YouTubeClient.HISTORY_BROWSE_ID, bodyOf(http.innertube("browse").single())["browseId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search sends the query and reads the saved TV search response`() = runBlocking {
        val http = routes(innertube = { endpoint, _ -> if (endpoint == "search") YouTubeFakeHttp.fixture("search") else "{}" })
        val page = client(http).loadFeed(YouTubeFeed.SEARCH, "  nasa  ")
        assertTrue(page.shelves.flatMap { it.videos }.isNotEmpty())
        assertEquals("nasa", bodyOf(http.innertube("search").single())["query"]!!.jsonPrimitive.content)
        // A blank search asks nothing.
        assertTrue(client(http).loadFeed(YouTubeFeed.SEARCH, " ").shelves.isEmpty())
        assertEquals(1, http.innertube("search").size)
    }

    @Test
    fun `more of search pages through search, everything else through browse`() = runBlocking {
        val http = routes(innertube = { endpoint, _ -> if (endpoint == "search") YouTubeFakeHttp.fixture("search_continuation") else subscriptionsJson() })
        val youtube = client(http)
        assertTrue(youtube.loadMore(YouTubeFeed.SEARCH, "TOKEN").shelves.isNotEmpty())
        assertEquals("TOKEN", bodyOf(http.innertube("search").single())["continuation"]!!.jsonPrimitive.content)
        youtube.loadMore(null, "PAGE2")
        assertEquals("PAGE2", bodyOf(http.innertube("browse").single())["continuation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a channel or playlist page is browsed with its params`() = runBlocking {
        val http = routes(innertube = { _, _ -> YouTubeFakeHttp.fixture("playlist") })
        val page = client(http).loadBrowse(YouTubeBrowseTarget("VLPL123", params = "cGFyYW1z"))
        assertTrue(page.shelves.flatMap { it.videos }.isNotEmpty())
        val body = bodyOf(http.innertube("browse").single())
        assertEquals("VLPL123", body["browseId"]!!.jsonPrimitive.content)
        assertEquals("cGFyYW1z", body["params"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the library leads with Watch later and Liked videos`() = runBlocking {
        val page = client(routes(innertube = { _, _ -> YouTubeFakeHttp.fixture("channel_tab_playlists") })).loadFeed(YouTubeFeed.LIBRARY)
        val links = page.shelves.first().links
        assertEquals(YouTubeLinkKind.WATCH_LATER, links[0].kind)
        assertEquals(YouTubeClient.WATCH_LATER_BROWSE_ID, links[0].target.browseId)
        assertEquals(YouTubeLinkKind.LIKED, links[1].kind)
        assertTrue("the playlists follow", page.shelves.flatMap { it.links }.any { it.kind == YouTubeLinkKind.PLAYLIST })
    }

    @Test
    fun `the home feed falls back to the older browse id`() = runBlocking {
        val http = routes(innertube = { _, req ->
            if (bodyOf(req)["browseId"]!!.jsonPrimitive.content == "default") "{}" else subscriptionsJson()
        })
        val page = client(http).loadFeed(YouTubeFeed.HOME)
        assertEquals(3, page.shelves.flatMap { it.videos }.size)
        assertEquals(listOf("default", "FEwhat_to_watch"), http.innertube("browse").map { bodyOf(it)["browseId"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a chosen brand channel is acted as`() = runBlocking {
        val auth = InMemoryYouTubeAuthStore(signedIn)
        val http = routes(innertube = { endpoint, _ -> if (endpoint == "account/accounts_list") ACCOUNTS_JSON else subscriptionsJson() })
        val youtube = client(http, auth)
        val accounts = youtube.listAccounts()
        assertEquals(listOf("Ada", "Ada Makes Things"), accounts.map { it.name })
        youtube.selectAccount(accounts[1])
        assertEquals("PAGE1", auth.load().pageId)
        assertEquals("Ada Makes Things", auth.load().accountName)
        youtube.loadFeed(YouTubeFeed.SUBSCRIPTIONS)
        assertEquals("PAGE1", http.innertube("browse").single().request.header("X-Goog-Pageid"))
        // The account list itself is asked as the Google account, so every channel shows.
        assertNull(http.innertube("account/accounts_list").last().request.header("X-Goog-Pageid"))
    }

    @Test
    fun `a 401 makes the next request refresh the token`() = runBlocking {
        val auth = InMemoryYouTubeAuthStore(signedIn)
        val youtube = YouTubeClient(YouTubeFakeHttp { req -> if (req.path == "/tv") 404 to "" else 401 to "{}" }.client, auth, now = { clock })
        try {
            youtube.loadFeed(YouTubeFeed.SUBSCRIPTIONS)
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("401"))
        }
        assertEquals(0L, auth.load().accessTokenExpiresAt)
    }

    // --- Actions ---

    @Test
    fun `tile menu actions send what the TV sends`() = runBlocking {
        val http = routes(innertube = { endpoint, _ ->
            when (endpoint) {
                "feedback" -> """{"feedbackResponses":[{"isProcessed":true}]}"""
                "browse/edit_playlist" -> """{"status":"STATUS_SUCCEEDED"}"""
                "playlist/create" -> """{"playlistId":"PLnew"}"""
                else -> "{}"
            }
        })
        val youtube = client(http)
        youtube.sendFeedback("TOKEN")
        assertEquals("[\"TOKEN\"]", bodyOf(http.innertube("feedback").single())["feedbackTokens"].toString())
        youtube.setInPlaylist("WL", "vid", inPlaylist = true)
        val edit = bodyOf(http.innertube("browse/edit_playlist").single())
        assertEquals("WL", edit["playlistId"]!!.jsonPrimitive.content)
        assertTrue(edit.toString().contains("ACTION_ADD_VIDEO"))
        assertEquals("PLnew", youtube.createPlaylist("Mine", "vid", YouTubePlaylistPrivacy.UNLISTED))
        val create = http.innertube("playlist/create").single()
        assertEquals("WEB", bodyOf(create)["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
        assertEquals("UNLISTED", bodyOf(create)["privacyStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun `refused feedback and playlist edits throw`() = runBlocking {
        val http = routes(innertube = { endpoint, _ ->
            if (endpoint == "feedback") """{"feedbackResponses":[{"isProcessed":false}]}""" else """{"status":"STATUS_FAILED"}"""
        })
        val youtube = client(http)
        assertTrue(runCatching { youtube.sendFeedback("T") }.isFailure)
        assertTrue(runCatching { youtube.setInPlaylist("PLx", "vid", inPlaylist = false) }.isFailure)
    }

    @Test
    fun `up next comes from the signed-in watch page, with the playlist`() = runBlocking {
        val http = routes(innertube = { endpoint, _ -> if (endpoint == "next") YouTubeFakeHttp.fixture("next") else "{}" })
        val next = client(http).loadWatchNext("vid00000000", playlistId = "PL1", playlistIndex = 3)
        assertTrue(next.related.isNotEmpty())
        val body = bodyOf(http.innertube("next").single())
        assertEquals("PL1", body["playlistId"]!!.jsonPrimitive.content)
        assertEquals(3, body["playlistIndex"]!!.jsonPrimitive.content.toInt())
    }

    // --- Watch history ---

    @Test
    fun `watching is reported to the account's history through the player's tracking links`() = runBlocking {
        val http = routes(innertube = { endpoint, _ ->
            if (endpoint == "player") """{"playbackTracking":{
                "videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?ei=E"},
                "videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?ei=E"}}}""" else "{}"
        })
        val youtube = client(http)
        val info = youtube.loadPlayerInfo("vid")!!
        val session = youtube.newWatchSession("vid", info, startPositionSec = 0f)!!
        youtube.reportWatchTime(session, positionSec = 30f, lengthSec = 600f)
        val pings = http.sent.filter { it.host == "s.youtube.com" }
        assertEquals(listOf("/api/stats/playback", "/api/stats/watchtime"), pings.map { it.path })
        val watchtime = pings[1].request.url
        assertEquals("0.000", watchtime.queryParameter("st"))
        assertEquals("30.000", watchtime.queryParameter("et"))
        assertEquals("vid", watchtime.queryParameter("docid"))
        assertEquals("Bearer AT", pings[1].request.header("Authorization"))

        // Later reports only move the position; the last 5% marks it watched.
        youtube.reportWatchTime(session, positionSec = 590f, lengthSec = 600f)
        val last = http.sent.filter { it.host == "s.youtube.com" }.drop(2)
        assertEquals(listOf("/api/stats/playback", "/api/stats/watchtime"), last.map { it.path })
        assertEquals("1", last[1].request.url.queryParameter("final"))
    }

    @Test
    fun `a start past three minutes is where history counts from`() {
        val youtube = client(routes())
        val info = YouTubePlayerInfo("https://s.youtube.com/a" to "https://s.youtube.com/b")
        assertEquals(600f, youtube.newWatchSession("v", info, 600f)!!.lastPositionSec)
        assertEquals(0f, youtube.newWatchSession("v", info, 60f)!!.lastPositionSec)
        assertNull(youtube.newWatchSession("v", YouTubePlayerInfo(null), 0f))
    }

    // --- Parsing, ported from the TV's YouTubeServiceParsingTest and YouTubeExtrasParsingTest ---

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `device code and token responses are read`() {
        val youtube = client(routes())
        assertEquals(YouTubeClient.ACTIVATION_URL, youtube.parseDeviceCode(json("""{"device_code":"d","user_code":"C"}"""))?.verificationUrl)
        assertNull(youtube.parseDeviceCode(json("""{"error":"invalid_client"}""")))
        assertEquals(YouTubeToken("new", null, 3600), youtube.parseToken(json("""{"access_token":"new","expires_in":3600}""")))
        assertTrue((youtube.parseTokenPoll(json("""{"error":"server_error"}""")) as YouTubeClient.TokenPoll.Status).status is YouTubeTokenPollResult.Failed)
    }

    @Test
    fun `the tv client is read from the first match in the tv script`() {
        val script = "var a={clientId:\"111-tv.apps.googleusercontent.com\",\nzx:\"tvSecret\"};" +
            """var b={clientId:"222-other.apps.googleusercontent.com",q:"otherSecret"};"""
        assertEquals("111-tv.apps.googleusercontent.com" to "tvSecret", client(routes()).parseClientCredentials(script))
    }

    @Test
    fun `a watch time url keeps the tracking params and adds the position`() {
        val session = YouTubeWatchSession("vid", "p", "w", clientPlaybackNonce = "NONCE", lastPositionSec = 0f)
        val parsed = client(routes()).watchTrackingUrl("https://s.youtube.com/api/stats/watchtime?ei=E&len=1", session, 600f, cmt = 120f, from = 60f, to = 120f)
            .toHttpUrl()
        assertEquals("E", parsed.queryParameter("ei"))
        assertEquals("600.000", parsed.queryParameter("len"))
        assertEquals("NONCE", parsed.queryParameter("cpn"))
        assertNull(parsed.queryParameter("final"))
    }

    @Test
    fun `suggestions are read from the suggest service's script`() = runBlocking {
        val body = """window.google.ac.h(["nasa art",[["nasa artemis",0,[512,433]],["nasa artemis lego",0,[512]],["nasa artemis",0,[512]]],{"k":1}])"""
        val http = YouTubeFakeHttp { 200 to body }
        assertEquals(listOf("nasa artemis", "nasa artemis lego"), client(http).searchSuggestions("nasa art"))
        val url = http.sent.single().request.url
        assertEquals("nasa art", url.queryParameter("q"))
        assertEquals("en", url.queryParameter("hl"))
        assertTrue(client(routes()).parseSuggestions("<html>").isEmpty())
    }
}
