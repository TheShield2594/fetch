package com.fetch.tv.data.mediaserver.mediabrowser

import com.fetch.tv.core.tracking.TrackingExternalIds
import com.fetch.tv.data.mediaserver.ServerConnection
import com.fetch.tv.data.mediaserver.ServerEpisode
import com.fetch.tv.data.mediaserver.ServerException
import com.fetch.tv.data.mediaserver.ServerFailure
import com.fetch.tv.data.mediaserver.ServerIndexEntry
import com.fetch.tv.data.mediaserver.ServerLibrary
import com.fetch.tv.data.mediaserver.ServerMediaKind
import com.fetch.tv.data.mediaserver.ServerSession
import com.fetch.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.fetch.tv.domain.model.FilterRowFilters
import com.fetch.tv.domain.model.FilterRowSort
import com.fetch.tv.domain.model.WatchedFilter
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of [MediaBrowserProvider] the request and playback tests don't reach (#580): sign-in
 * replies that leave something out, sign-out, filtered and collection pages, genres, the
 * external-id index and finding one episode of a show.
 */
class MediaBrowserProviderTest {
    private val movies = ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)
    private val shows = ServerLibrary("lib2", "Shows", ServerMediaKind.SERIES)
    private val boxSets = ServerLibrary("lib3", "Collections", ServerMediaKind.COLLECTION)

    private val session = ServerSession(
        connection = ServerConnection(
            id = "cabc",
            providerId = "jellyfin",
            name = "Home",
            address = "https://media.example.com/jellyfin",
            remoteServerId = "s1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1",
            libraries = listOf(movies)
        ),
        token = "secret"
    )

    // ─── Sign-in ───

    private suspend fun signIn(auth: String, info: String = """{"ServerName": "Den", "Version": "10.10.7", "Id": "info-id"}""") =
        runCatching {
            val http = TestHttp { request -> if (request.url.encodedPath.endsWith("/System/Info/Public")) info else auth }
            JellyfinProvider(http.client, testIdentity).signIn("http://den.local:8096", "viewer", "pw")
        }

    @Test
    fun aSignInReplyWithoutAUserOrTokenNeedsCredentials() = runTest {
        listOf(
            """{"AccessToken": "tok", "ServerId": "srv"}""",
            """{"User": {"Id": "u1", "Name": "Viewer"}, "ServerId": "srv"}""",
            """{"User": {"Id": "u1", "Name": "Viewer"}, "AccessToken": " ", "ServerId": "srv"}"""
        ).forEach { auth ->
            assertEquals(auth, ServerFailure.AUTH_REQUIRED, (signIn(auth).exceptionOrNull() as ServerException).failure)
        }
    }

    @Test
    fun aSignInFallsBackToThePublicInfoForWhatTheReplyLeavesOut() = runTest {
        val auth = """{"User": {"Id": "u1"}, "AccessToken": "tok"}"""
        val signIn = signIn(auth, info = """{"Version": "10.10.7", "Id": "info-id"}""").getOrThrow()
        assertEquals("info-id", signIn.serverId)
        // No server name: the host stands in; no user name: the one typed.
        assertEquals("den.local", signIn.serverName)
        assertEquals("viewer", signIn.userName)

        val noId = signIn(auth, info = """{"ServerName": "Den", "Version": "10.10.7"}""")
        assertEquals(ServerFailure.FAILED, (noId.exceptionOrNull() as ServerException).failure)
    }

    @Test
    fun anAddressThatIsNotAServerFailsBeforeAnyRequest() = runTest {
        val http = TestHttp()
        val error = runCatching { JellyfinProvider(http.client, testIdentity).signIn("not a url", "viewer", "pw") }
        assertEquals(ServerFailure.NOT_FOUND, (error.exceptionOrNull() as ServerException).failure)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun signingOutEndsTheSessionOnTheServer() = runTest {
        val http = TestHttp { "" }
        JellyfinProvider(http.client, testIdentity).signOut(session)
        val request = http.requests.single()
        assertEquals("POST https://media.example.com/jellyfin/Sessions/Logout", "${request.method} ${request.url}")
        assertTrue(request.header("Authorization").orEmpty().endsWith("Token=\"secret\""))
    }

    // ─── Library pages ───

    @Test
    fun filterRowsBecomeServerQueries() = runTest {
        val http = TestHttp { """{"Items": [{"Id": "m1", "Name": "Heat", "Type": "Movie"}], "TotalRecordCount": 42}""" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val filters = FilterRowFilters(
            genre = "Crime, Drama",
            yearFrom = 1990,
            yearTo = 1999,
            watched = WatchedFilter.UNWATCHED,
            minRating = 7.5,
            sort = FilterRowSort.RATING,
            descending = false
        )

        val page = jellyfin.filteredLibraryPage(session, movies, filters, start = 20, limit = 10)

        assertEquals(listOf("Heat"), page.items.map { it.preview.name })
        assertEquals(42, page.totalCount)
        val query = http.requests.single().query()
        assertEquals("lib1", query["parentId"])
        assertEquals("u1", query["userId"])
        assertEquals("true", query["recursive"])
        assertEquals("Movie", query["includeItemTypes"])
        assertEquals("Crime, Drama", query["genres"])
        assertEquals("1990-01-01T00:00:00Z", query["minPremiereDate"])
        assertEquals("1999-12-31T23:59:59Z", query["maxPremiereDate"])
        assertEquals("false", query["isPlayed"])
        assertEquals("7.5", query["minCommunityRating"])
        assertEquals("CommunityRating,SortName", query["sortBy"])
        assertEquals("Ascending", query["sortOrder"])
        assertEquals("20", query["startIndex"])
        assertEquals("10", query["limit"])
        assertEquals("true", query["enableTotalRecordCount"])
    }

    @Test
    fun everySortAndWatchedChoiceHasAQuery() = runTest {
        val http = TestHttp { """{"Items": []}""" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val sorts = mapOf(
            FilterRowSort.DATE_ADDED to "DateCreated,SortName",
            FilterRowSort.TITLE to "SortName",
            FilterRowSort.RELEASE_DATE to "PremiereDate,ProductionYear,SortName",
            FilterRowSort.RATING to "CommunityRating,SortName",
            FilterRowSort.RANDOM to "Random"
        )
        assertEquals(FilterRowSort.entries.toSet(), sorts.keys)
        sorts.forEach { (sort, sortBy) ->
            jellyfin.filteredLibraryPage(session, movies, FilterRowFilters(sort = sort), 0, 10)
            assertEquals(sortBy, http.requests.last().query()["sortBy"])
        }
        val watched = mapOf(WatchedFilter.ANY to null, WatchedFilter.WATCHED to "true", WatchedFilter.UNWATCHED to "false")
        assertEquals(WatchedFilter.entries.toSet(), watched.keys)
        watched.forEach { (filter, isPlayed) ->
            jellyfin.filteredLibraryPage(session, movies, FilterRowFilters(watched = filter), 0, 10)
            val query = http.requests.last().query()
            assertEquals(isPlayed, query["isPlayed"])
            // Defaults: newest first, no other narrowing.
            assertEquals("Descending", query["sortOrder"])
            listOf("genres", "minPremiereDate", "maxPremiereDate", "minCommunityRating").forEach { assertNull(it, query[it]) }
        }
    }

    @Test
    fun aCollectionLibraryListsItsBoxSetsWithoutDescending() = runTest {
        val http = TestHttp { """{"Items": []}""" }
        JellyfinProvider(http.client, testIdentity).filteredLibraryPage(session, boxSets, FilterRowFilters(), 0, 10)
        val query = http.requests.single().query()
        assertEquals("false", query["recursive"])
        assertNull(query["includeItemTypes"])
    }

    @Test
    fun aCollectionPageListsTheCollectionsOwnItems() = runTest {
        val http = TestHttp {
            """{"Items": [{"Id": "m1", "Name": "Alien", "Type": "Movie"}, {"Id": "m2", "Name": "Aliens", "Type": "Movie"}], "TotalRecordCount": 4}"""
        }
        val page = JellyfinProvider(http.client, testIdentity).collectionPage(session, "box/1", start = 2, limit = 2)
        assertEquals(listOf("Alien", "Aliens"), page.items.map { it.preview.name })
        assertEquals(4, page.totalCount)
        val query = http.requests.single().query()
        assertEquals("box/1", query["parentId"])
        assertEquals("2", query["startIndex"])
        assertEquals("2", query["limit"])
        assertNull(query["recursive"])
    }

    @Test
    fun genresAreTheLibrarysNamedOnesOnce() = runTest {
        val http = TestHttp {
            """{"Items": [{"Id": "g1", "Name": "Action"}, {"Id": "g2", "Name": " "}, {"Id": "g3"}, {"Id": "g4", "Name": "Action"}, {"Id": "g5", "Name": "Drama"}]}"""
        }
        val genres = JellyfinProvider(http.client, testIdentity).genres(session, shows)
        assertEquals(listOf("Action", "Drama"), genres)
        val request = http.requests.single()
        assertEquals("/jellyfin/Genres", request.url.encodedPath)
        assertEquals("lib2", request.query()["parentId"])
        assertEquals("u1", request.query()["userId"])
        assertEquals("SortName", request.query()["sortBy"])
    }

    // ─── Matching ───

    @Test
    fun theExternalIdIndexMapsEachItemsProviderIds() = runTest {
        val http = TestHttp {
            """{"Items": [
                {"Id": "s1", "ProviderIds": {"Imdb": "tt0903747", "Tmdb": "1396", "Tvdb": "81189"}},
                {"Id": "s2", "ProviderIds": {"Imdb": "not-an-imdb-id", "Tmdb": " "}},
                {"Id": "s3"}
            ], "TotalRecordCount": 3}"""
        }
        val page = JellyfinProvider(http.client, testIdentity).externalIdIndex(session, shows, start = 0, limit = 500)
        assertEquals(
            listOf(
                ServerIndexEntry("s1", TrackingExternalIds(imdb = "tt0903747", tmdb = 1396, tvdb = "81189")),
                ServerIndexEntry("s2", TrackingExternalIds()),
                ServerIndexEntry("s3", TrackingExternalIds())
            ),
            page.items
        )
        assertEquals(3, page.totalCount)
        val query = http.requests.single().query()
        assertEquals("Series", query["includeItemTypes"])
        assertEquals("ProviderIds", query["fields"])
        assertEquals("false", query["enableImages"])
        assertEquals("500", query["limit"])
    }

    @Test
    fun findsTheOneEpisodeAtASeasonAndNumber() = runTest {
        val http = TestHttp {
            """{"Items": [
                {"Id": "e1", "ParentIndexNumber": 2, "IndexNumber": 4, "PremiereDate": "2010-04-11T00:00:00Z"},
                {"Id": "e2", "ParentIndexNumber": 2, "IndexNumber": 5, "IndexNumberEnd": 6},
                {"Id": "e3", "ParentIndexNumber": 2, "IndexNumber": 7, "LocationType": "Virtual"},
                {"Id": "e4", "ParentIndexNumber": 0, "IndexNumber": 4},
                {"Id": "e5", "ParentIndexNumber": 2, "IndexNumber": 8},
                {"Id": "e6", "ParentIndexNumber": 2, "IndexNumber": 8}
            ]}"""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)

        assertEquals(ServerEpisode("e1", "2010-04-11T00:00:00Z"), jellyfin.findEpisode(session, "show/1", 2, 4))
        // A two-part file doesn't count as either part, a missing episode has no file, a special
        // isn't in the season, and two files for one episode are ambiguous.
        assertNull(jellyfin.findEpisode(session, "show/1", 2, 5))
        assertNull(jellyfin.findEpisode(session, "show/1", 2, 6))
        assertNull(jellyfin.findEpisode(session, "show/1", 2, 7))
        assertNull(jellyfin.findEpisode(session, "show/1", 2, 8))
        assertNull(jellyfin.findEpisode(session, "show/1", 2, 9))

        val request = http.requests.first()
        assertEquals("/jellyfin/Shows/show%2F1/Episodes", request.url.encodedPath)
        assertEquals("2", request.query()["season"])
        assertEquals("u1", request.query()["userId"])
    }

    private fun Request.query(): Map<String, String?> =
        url.queryParameterNames.associateWith { url.queryParameter(it) }
}
