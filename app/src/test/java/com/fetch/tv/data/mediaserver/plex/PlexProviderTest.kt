package com.fetch.tv.data.mediaserver.plex

import com.fetch.tv.core.tracking.TrackingExternalIds
import com.fetch.tv.data.mediaserver.ServerConnection
import com.fetch.tv.data.mediaserver.ServerException
import com.fetch.tv.data.mediaserver.ServerFailure
import com.fetch.tv.data.mediaserver.ServerIndexEntry
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.data.mediaserver.ServerLibrary
import com.fetch.tv.data.mediaserver.ServerLinkCode
import com.fetch.tv.data.mediaserver.ServerMediaKind
import com.fetch.tv.data.mediaserver.ServerPlayMethod
import com.fetch.tv.data.mediaserver.ServerPlaybackRequest
import com.fetch.tv.data.mediaserver.ServerPlaybackTarget
import com.fetch.tv.data.mediaserver.ServerPlayerCapabilities
import com.fetch.tv.data.mediaserver.ServerSession
import com.fetch.tv.data.mediaserver.ServerTitleQuery
import com.fetch.tv.data.mediaserver.mediabrowser.TestHttp
import com.fetch.tv.data.mediaserver.mediabrowser.testIdentity
import com.fetch.tv.domain.model.FilterRowFilters
import com.fetch.tv.domain.model.FilterRowSort
import com.fetch.tv.domain.model.WatchedFilter
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlexProviderTest {
    private val link = ServerLinkCode("42", "ABCD", "plex.tv/link", "https://plex.tv/link", Long.MAX_VALUE, 1L)

    @Test
    fun linkIsPendingUntilPlexTvHasAToken() = runTest {
        val http = TestHttp { """{"id":42,"code":"ABCD","authToken":null}""" }
        assertNull(PlexProvider(http.client, testIdentity).completeLink(link))
        val request = http.requests.single()
        assertEquals("/api/v2/pins/42", request.url.encodedPath)
        assertEquals("ABCD", request.url.queryParameter("code"))
        assertEquals("device-1", request.header("X-Plex-Client-Identifier"))
        assertEquals("Fetch", request.header("X-Plex-Product"))
    }

    @Test
    fun approvedLinkFindsEachServerOnItsBestAnsweringAddress() = runTest {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath == "/api/v2/pins/42" -> """{"id":42,"code":"ABCD","authToken":"account-token"}"""
                request.url.encodedPath == "/api/v2/user" -> """{"id":7,"username":"sam"}"""
                request.url.encodedPath == "/api/v2/resources" -> RESOURCES
                // The LAN address is down, so the remote one is used.
                request.url.host == "10-0-0-5.abc.plex.direct" -> throw IOException("unreachable")
                request.url.encodedPath == "/identity" -> """{"MediaContainer":{"machineIdentifier":"server-1","version":"1.41.0"}}"""
                else -> error("Unexpected ${request.url}")
            }
        }
        val signIns = PlexProvider(http.client, testIdentity).completeLink(link)!!
        val signIn = signIns.single()
        assertEquals("https://203-0-113-9.abc.plex.direct:32400", signIn.address)
        assertEquals("Living Room", signIn.serverName)
        assertEquals("server-1", signIn.serverId)
        assertEquals("7", signIn.userId)
        assertEquals("sam", signIn.userName)
        assertEquals("server-token", signIn.token)
        assertEquals("account-token", http.requests.first { it.url.encodedPath == "/api/v2/resources" }.header("X-Plex-Token"))
    }

    @Test
    fun unreachablePlexTvIsReported() = runTest {
        val http = TestHttp { throw IOException("gone") }
        try {
            PlexProvider(http.client, testIdentity).completeLink(link)
            fail("Expected a failure")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.UNREACHABLE, error.failure)
        }
    }

    @Test
    fun serversOlderThanTheMinimumAreUnsupported() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/api/v2/pins/42" -> """{"id":42,"code":"ABCD","authToken":"account-token"}"""
                "/api/v2/user" -> """{"id":7,"username":"sam"}"""
                "/api/v2/resources" -> RESOURCES.replace("1.41.0.8992", "1.20.5.3600")
                else -> error("Unexpected ${request.url}")
            }
        }
        try {
            PlexProvider(http.client, testIdentity).completeLink(link)
            fail("Expected a failure")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.UNSUPPORTED, error.failure)
        }
    }

    @Test
    fun listsMovieAndShowLibrariesOnly() = runTest {
        val http = TestHttp {
            """{"MediaContainer":{"Directory":[
                {"key":"1","title":"Movies","type":"movie"},
                {"key":"2","title":"TV Shows","type":"show"},
                {"key":"3","title":"Music","type":"artist"}
            ]}}"""
        }
        val libraries = PlexProvider(http.client, testIdentity).libraries(session())
        assertEquals(
            listOf(
                ServerLibrary("1", "Movies", ServerMediaKind.MOVIE),
                ServerLibrary("2", "TV Shows", ServerMediaKind.SERIES)
            ),
            libraries
        )
        assertEquals("token-1", http.requests.single().header("X-Plex-Token"))
    }

    @Test
    fun pagesLibrariesWithExternalIds() = runTest {
        val http = TestHttp { MOVIE_PAGE }
        val page = PlexProvider(http.client, testIdentity)
            .libraryPage(session(), ServerLibrary("1", "Movies", ServerMediaKind.MOVIE), start = 50, limit = 25)
        val url = http.requests.single().url
        assertEquals("/library/sections/1/all", url.encodedPath)
        assertEquals("1", url.queryParameter("type"))
        assertEquals("50", url.queryParameter("X-Plex-Container-Start"))
        assertEquals("25", url.queryParameter("X-Plex-Container-Size"))
        assertEquals("1", url.queryParameter("includeGuids"))
        assertEquals(120, page.totalCount)
        val title = page.items.single()
        assertEquals(ServerItemRef("c1", "101").encode(), title.preview.id)
        assertEquals("The Matrix", title.preview.name)
        assertEquals("1999", title.preview.releaseInfo)
        assertEquals(TrackingExternalIds(imdb = "tt0133093", tmdb = 603, tvdb = "169"), title.externalIds)
        val poster = title.preview.poster!!.toHttpUrl()
        assertEquals("/photo/:/transcode", poster.encodedPath)
        assertEquals("/library/metadata/101/thumb/1700000000", poster.queryParameter("url"))
        assertEquals("token-1", poster.queryParameter("X-Plex-Token"))
    }

    @Test
    fun looksUpATitleByNameAndTriesTheOriginalTitleOnce() = runTest {
        val http = TestHttp { request ->
            if (request.url.queryParameter("title") == "Intouchables") {
                """{"MediaContainer":{"Metadata":[{"ratingKey":"7","title":"Intouchables","type":"movie","year":2011,
                    "Guid":[{"id":"imdb://tt1675434"}]}]}}"""
            } else {
                """{"MediaContainer":{"size":0}}"""
            }
        }
        val plex = PlexProvider(http.client, testIdentity)
        val library = ServerLibrary("1", "Movies", ServerMediaKind.MOVIE)
        val ids = TrackingExternalIds(imdb = "tt1675434")

        val found = plex.lookupTitle(session(), library, ServerTitleQuery(ids, "The Intouchables", 2011, originalName = "Intouchables"))

        assertEquals(listOf(ServerIndexEntry("7", ids, year = 2011)), found)
        assertEquals(listOf("The Intouchables", "Intouchables"), http.requests.map { it.url.queryParameter("title") })
        val url = http.requests.first().url
        assertEquals("/library/sections/1/all", url.encodedPath)
        assertEquals("1", url.queryParameter("type"))
        assertEquals("1", url.queryParameter("includeGuids"))
        assertEquals("20", url.queryParameter("X-Plex-Container-Size"))

        assertNull(plex.lookupTitle(session(), library, ServerTitleQuery(ids, name = null, year = null)))
        assertEquals(2, http.requests.size)
    }

    @Test
    fun showDetailsCarryEpisodeProgress() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/library/metadata/200" -> SHOW
                "/library/metadata/200/allLeaves" -> EPISODES
                else -> error("Unexpected ${request.url}")
            }
        }
        val details = PlexProvider(http.client, testIdentity).details(session(), "200")
        assertEquals("Severance", details.meta.name)
        assertEquals(TrackingExternalIds(imdb = "tt11280740", tvdb = "371980"), details.externalIds)
        assertEquals(listOf(1 to 1, 1 to 2), details.meta.videos.map { it.season to it.episode })
        val (first, second) = details.userStates
        assertTrue(first.played)
        assertEquals(1_700_000_000_000L, first.lastPlayedEpochMs)
        assertEquals(false, second.played)
        assertEquals(600_000L, second.positionMs)
        assertEquals(3_300_000L, second.durationMs)
        assertEquals(ServerItemRef("c1", "202").encode(), second.videoId)
    }

    @Test
    fun findsAnEpisodeBySeasonAndNumber() = runTest {
        val http = TestHttp { EPISODES }
        val episode = PlexProvider(http.client, testIdentity).findEpisode(session(), "200", season = 1, episode = 2)
        assertEquals("202", episode?.itemId)
        assertEquals("2022-02-18", episode?.premiereDate)
    }

    @Test
    fun marksPlayedThroughScrobble() = runTest {
        val http = TestHttp { "" }
        val provider = PlexProvider(http.client, testIdentity)
        provider.setPlayed(session(), "101", played = true)
        provider.setPlayed(session(), "101", played = false)
        assertEquals(listOf("/:/scrobble", "/:/unscrobble"), http.requests.map { it.url.encodedPath })
        assertEquals("101", http.requests.first().url.queryParameter("key"))
        assertEquals("com.plexapp.plugins.library", http.requests.first().url.queryParameter("identifier"))
    }

    @Test
    fun directPlaysThePartWithTheTokenAndExternalSubtitles() = runTest {
        val http = TestHttp { MOVIE_DETAILS }
        val playback = PlexProvider(http.client, testIdentity).preparePlayback(session(), request(allowDirectPlay = true))
        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
        val url = playback.url.toHttpUrl()
        assertEquals("/library/parts/9001/1700000000/file.mkv", url.encodedPath)
        assertEquals("token-1", url.queryParameter("X-Plex-Token"))
        assertEquals("token-1", playback.headers["X-Plex-Token"])
        val subtitle = playback.subtitles.single()
        assertEquals("/library/streams/77", subtitle.url.toHttpUrl().encodedPath)
        assertEquals("eng", subtitle.lang)
        assertEquals(listOf("English (TRUEHD 7.1)", "English (AC3 5.1)"), playback.audioTracks.map { it.label })
        assertEquals(listOf(true, false), playback.audioTracks.map { it.selected })
    }

    @Test
    fun fallsBackToTheTranscoderAndReportsItsDecision() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/library/metadata/101" -> MOVIE_DETAILS
                "/library/parts/9001" -> ""
                "/video/:/transcode/universal/decision" ->
                    """{"MediaContainer":{"Metadata":[{"ratingKey":"101","Media":[{"id":5001,"Part":[{"id":9001,"Stream":[
                        {"id":1,"streamType":1,"decision":"copy"},{"id":2,"streamType":2,"decision":"transcode"}
                    ]}]}]}]}}"""
                else -> error("Unexpected ${request.url}")
            }
        }
        val playback = PlexProvider(http.client, testIdentity)
            .preparePlayback(session(), request(allowDirectPlay = false, audioStreamIndex = 3))
        assertEquals(ServerPlayMethod.DIRECT_STREAM, playback.playMethod)
        val url = playback.url.toHttpUrl()
        assertEquals("/video/:/transcode/universal/start.m3u8", url.encodedPath)
        assertEquals("/library/metadata/101", url.queryParameter("path"))
        assertEquals("0", url.queryParameter("mediaIndex"))
        assertEquals("device-1", url.queryParameter("X-Plex-Client-Identifier"))
        assertEquals(playback.playSessionId, url.queryParameter("session"))
        val select = http.requests.single { it.url.encodedPath == "/library/parts/9001" }
        assertEquals("PUT", select.method)
        assertEquals("3", select.url.queryParameter("audioStreamID"))
        assertEquals(listOf(false, true), playback.audioTracks.map { it.selected })
    }

    @Test
    fun parsesCurrentAndLegacyAgentGuids() {
        assertEquals(TrackingExternalIds(imdb = "tt0133093"), plexGuidIds("imdb://tt0133093"))
        assertEquals(TrackingExternalIds(tmdb = 603), plexGuidIds("tmdb://603"))
        assertEquals(TrackingExternalIds(tvdb = "81189"), plexGuidIds("tvdb://81189"))
        assertEquals(TrackingExternalIds(imdb = "tt0133093"), plexGuidIds("com.plexapp.agents.imdb://tt0133093?lang=en"))
        assertEquals(TrackingExternalIds(tmdb = 603), plexGuidIds("com.plexapp.agents.themoviedb://603?lang=en"))
        assertEquals(TrackingExternalIds(tvdb = "81189"), plexGuidIds("com.plexapp.agents.thetvdb://81189/1/2?lang=en"))
        assertEquals(TrackingExternalIds(anidb = 123), plexGuidIds("com.plexapp.agents.hama://anidb-123?lang=en"))
        assertEquals(TrackingExternalIds(), plexGuidIds("plex://movie/5d7768ba96b655001fdc0408"))
        assertEquals(TrackingExternalIds(), plexGuidIds("local://12345"))
    }

    @Test
    fun translatesFilterRowsToPlexFilters() {
        val query = PlexProvider.filterQuery(
            FilterRowFilters(
                genre = "Science Fiction",
                yearFrom = 2000,
                yearTo = 2009,
                watched = WatchedFilter.UNWATCHED,
                minRating = 7.0,
                sort = FilterRowSort.RATING,
                descending = true
            ),
            genreKey = "58"
        )
        assertEquals("58", query["genre"])
        assertEquals("1999", query["year>>"])
        assertEquals("2010", query["year<<"])
        assertEquals("1", query["unwatched"])
        assertEquals("audienceRating:desc", query["sort"])
        assertTrue(query.getValue("audienceRating>>")!!.toDouble() in 6.9..7.0)
    }

    @Test
    fun prefersLocalThenRemoteThenRelayedConnections() {
        val ordered = PlexProvider.orderedConnections(
            listOf(
                PlexResourceConnection("https://relay", relay = true),
                PlexResourceConnection("https://remote6", ipv6 = true),
                PlexResourceConnection("https://remote"),
                PlexResourceConnection("https://local", local = true)
            )
        )
        assertEquals(listOf("https://local", "https://remote", "https://remote6", "https://relay"), ordered.map { it.uri })
    }

    private fun session() = ServerSession(
        ServerConnection(
            id = "c1",
            providerId = "plex",
            name = "Living Room",
            address = "https://10-0-0-5.abc.plex.direct:32400",
            remoteServerId = "server-1",
            remoteUserId = "7",
            userName = "sam",
            credentialRef = "k1",
            libraries = listOf(
                ServerLibrary("1", "Movies", ServerMediaKind.MOVIE),
                ServerLibrary("2", "TV Shows", ServerMediaKind.SERIES)
            )
        ),
        token = "token-1"
    )

    private fun request(allowDirectPlay: Boolean, audioStreamIndex: Int? = null) = ServerPlaybackRequest(
        target = ServerPlaybackTarget(ServerItemRef("c1", "101"), mediaSourceId = "5001"),
        capabilities = ServerPlayerCapabilities(allowDirectPlay = allowDirectPlay),
        audioStreamIndex = audioStreamIndex
    )

    private companion object {
        val RESOURCES = """[
            {"name":"Living Room","product":"Plex Media Server","productVersion":"1.41.0.8992","clientIdentifier":"server-1",
             "provides":"server","owned":true,"accessToken":"server-token","connections":[
                {"uri":"https://203-0-113-9.abc.plex.direct:32400","local":false,"relay":false},
                {"uri":"https://10-0-0-5.abc.plex.direct:32400","local":true,"relay":false},
                {"uri":"https://relay.abc.plex.direct:8443","local":false,"relay":true}
             ]},
            {"name":"Phone","product":"Plex for Android","clientIdentifier":"phone-1","provides":"player,pubsub-player"}
        ]"""

        val MOVIE_PAGE = """{"MediaContainer":{"size":1,"totalSize":120,"Metadata":[
            {"ratingKey":"101","type":"movie","title":"The Matrix","year":1999,"summary":"Neo.",
             "thumb":"/library/metadata/101/thumb/1700000000","art":"/library/metadata/101/art/1700000000",
             "audienceRating":8.7,"Genre":[{"tag":"Science Fiction"}],
             "Guid":[{"id":"imdb://tt0133093"},{"id":"tmdb://603"},{"id":"tvdb://169"}]}
        ]}}"""

        val MOVIE_DETAILS = """{"MediaContainer":{"Metadata":[
            {"ratingKey":"101","type":"movie","title":"The Matrix","duration":8160000,
             "Media":[{"id":5001,"videoResolution":"4k","videoCodec":"hevc","container":"mkv","Part":[
                {"id":9001,"key":"/library/parts/9001/1700000000/file.mkv","file":"/movies/The Matrix (1999).mkv","size":60000000000,"Stream":[
                    {"id":1,"streamType":1,"codec":"hevc","displayTitle":"4K DoVi/HDR10 (HEVC Main 10)"},
                    {"id":2,"streamType":2,"codec":"truehd","languageCode":"eng","extendedDisplayTitle":"English (TRUEHD 7.1)","selected":true},
                    {"id":3,"streamType":2,"codec":"ac3","languageCode":"eng","extendedDisplayTitle":"English (AC3 5.1)"},
                    {"id":77,"streamType":3,"codec":"srt","languageCode":"eng","displayTitle":"English (SRT External)","key":"/library/streams/77"},
                    {"id":78,"streamType":3,"codec":"pgs","languageCode":"eng","displayTitle":"English (PGS)"}
                ]}
             ]}]}
        ]}}"""

        val SHOW = """{"MediaContainer":{"Metadata":[
            {"ratingKey":"200","type":"show","title":"Severance","year":2022,
             "Guid":[{"id":"imdb://tt11280740"},{"id":"tvdb://371980"}]}
        ]}}"""

        val EPISODES = """{"MediaContainer":{"Metadata":[
            {"ratingKey":"201","type":"episode","title":"Good News About Hell","parentIndex":1,"index":1,
             "duration":3300000,"viewCount":1,"lastViewedAt":1700000000,"originallyAvailableAt":"2022-02-18",
             "Media":[{"id":6001,"Part":[{"id":9101}]}]},
            {"ratingKey":"202","type":"episode","title":"Half Loop","parentIndex":1,"index":2,
             "duration":3300000,"viewOffset":600000,"lastViewedAt":1700000500,"originallyAvailableAt":"2022-02-18",
             "Media":[{"id":6002,"Part":[{"id":9102}]}]}
        ]}}"""
    }
}
