package com.fetch.tv.core.tracking

import com.fetch.tv.domain.model.Video
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioImportTest {

    // Fixtures from stremio-core's own watched_bitfield tests.
    private val firstFiveOfNine = "tt2934286:1:5:5:eJyTZwAAAEAAIA=="
    private val allTwentyFour = "tt7767422:3:8:24:eJz7//8/AAX9Av4="

    @Test
    fun `decodes Stremio's own bitfield`() {
        val videos = (1..9).map { episode("tt2934286", 1, it) }

        val watched = decodeStremioWatchedVideoIds(firstFiveOfNine, videos)

        assertEquals((1..5).map { "tt2934286:1:$it" }.toSet(), watched)
    }

    @Test
    fun `decodes every episode across seasons`() {
        val videos = (1..3).flatMap { season -> (1..8).map { episode("tt7767422", season, it) } }

        assertEquals(24, decodeStremioWatchedVideoIds(allTwentyFour, videos)?.size)
    }

    @Test
    fun `bits are realigned on the anchor when episodes were added before it`() {
        // A special added since the field was written sorts first and shifts every index by one.
        val videos = listOf(episode("tt2934286", 0, 1)) + (1..9).map { episode("tt2934286", 1, it) }

        val watched = decodeStremioWatchedVideoIds(firstFiveOfNine, videos)

        assertEquals((1..5).map { "tt2934286:1:$it" }.toSet(), watched)
    }

    @Test
    fun `episodes are put in Stremio's order whatever order the addon lists them in`() {
        val videos = (1..9).map { episode("tt2934286", 1, it) }.reversed()

        val watched = decodeStremioWatchedVideoIds(firstFiveOfNine, videos)

        assertEquals((1..5).map { "tt2934286:1:$it" }.toSet(), watched)
    }

    @Test
    fun `an anchor missing from the episode list can't be decoded`() {
        assertNull(decodeStremioWatchedVideoIds(firstFiveOfNine, (6..9).map { episode("tt2934286", 1, it) }))
        assertNull(decodeStremioWatchedVideoIds("undefined:1:eJwDAAAAAAE=", emptyList()))
        assertNull(decodeStremioWatchedVideoIds("not a field", emptyList()))
    }

    @Test
    fun `parses a datastoreGet response`() {
        val items = parseStremioLibrary(
            """
            {"result":[
              {"_id":"tt0111161","type":"movie","name":"A Film","poster":"https://p/1.jpg","removed":false,"temp":false,
               "state":{"lastWatched":"2024-05-01T20:00:00.000Z","timeOffset":0,"duration":0,"timesWatched":1,
                        "flaggedWatched":1,"video_id":"tt0111161","watched":null}},
              {"_id":"","type":"movie","name":"No id","state":{}}
            ]}
            """.trimIndent()
        )

        val film = items.single()
        assertEquals("tt0111161", film.id)
        assertEquals(1_714_593_600_000L, film.lastWatched)
        assertEquals(1, film.timesWatched)
        assertNull(film.watched)
    }

    @Test(expected = StremioApiException::class)
    fun `an error response throws`() {
        parseStremioLibrary("""{"error":{"code":1,"message":"Session does not exist"}}""")
    }

    @Test
    fun `builds watched marks and resume points`() = runTest {
        val items = listOf(
            item("tt1", "movie", timesWatched = 1, lastWatched = 100L),
            item("tt2", "movie", timeOffset = 600_000L, duration = 6_000_000L, lastWatched = 200L, videoId = "tt2"),
            item(
                "tt2934286", "series",
                watched = firstFiveOfNine,
                videoId = "tt2934286:1:6", timeOffset = 300_000L, duration = 2_400_000L, lastWatched = 300L
            ),
            // Removed from the library by hand: Stremio keeps it out of Continue Watching.
            item("tt3", "movie", removed = true, timeOffset = 600_000L, duration = 6_000_000L),
            item("tv1", "tv", timesWatched = 4)
        )

        val snapshot = buildStremioSnapshot(items) { (1..9).map { episode("tt2934286", 1, it) } }

        assertEquals(
            setOf("tt1", "tt2934286:1:1", "tt2934286:1:2", "tt2934286:1:3", "tt2934286:1:4", "tt2934286:1:5"),
            snapshot.watched.map { listOfNotNull(it.contentId, it.season, it.episode).joinToString(":") }.toSet()
        )
        assertEquals(100L, snapshot.watched.single { it.contentId == "tt1" }.watchedAt)
        assertEquals(setOf("tt2", "tt2934286"), snapshot.inProgress.map { it.contentId }.toSet())
        val resume = snapshot.inProgress.single { it.contentId == "tt2934286" }
        assertEquals(1, resume.season)
        assertEquals(6, resume.episode)
        assertEquals(300_000L, resume.position)
        assertTrue(resume.isInProgress())
        assertEquals(0, snapshot.unreadable)
    }

    @Test
    fun `without an episode list only the anchor episode and the resume point come through`() = runTest {
        val items = listOf(
            item(
                "tt2934286", "series",
                watched = firstFiveOfNine,
                videoId = "tt2934286:1:5", lastWatched = 500L
            ),
            item("kitsu:1", "series", watched = "kitsu:1:7:7:eJyTZwAAAEAAIA==")
        )

        val snapshot = buildStremioSnapshot(items) { null }

        val mark = snapshot.watched.single()
        assertEquals("tt2934286", mark.contentId)
        assertEquals(1, mark.season)
        assertEquals(5, mark.episode)
        assertEquals(500L, mark.watchedAt)
        assertEquals(2, snapshot.unreadable)
    }

    @Test
    fun `a film watched before and being played again keeps its resume point`() = runTest {
        val snapshot = buildStremioSnapshot(
            listOf(item("tt5", "movie", timesWatched = 1, lastWatched = 900L, timeOffset = 3_000_000L, duration = 6_000_000L))
        ) { null }

        // When it was first finished is unknown; lastWatched is the current playback.
        assertEquals(0L, snapshot.watched.single().watchedAt)
        val plan = planTrackingImport(snapshot, TrackingImportSnapshot())
        assertEquals(1, plan.watched.size)
        assertEquals(listOf("tt5"), plan.progress.map { it.contentId })
    }

    @Test
    fun `watched episodes without numbers are counted as unreadable`() = runTest {
        // The episode list has the anchor but no season or episode numbers to file the marks under.
        val unnumbered = (1..9).map { episode("tt2934286", 1, it).copy(id = "x$it", season = null, episode = null) }
        val field = "x5:5:eJyTZwAAAEAAIA=="

        val snapshot = buildStremioSnapshot(listOf(item("tt2934286", "series", watched = field))) { unnumbered }

        assertTrue(snapshot.watched.isEmpty())
        assertEquals(1, snapshot.unreadable)
    }

    private fun episode(seriesId: String, season: Int, episode: Int) = Video(
        id = "$seriesId:$season:$episode",
        title = "S$season E$episode",
        released = null,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )

    private fun item(
        id: String,
        type: String,
        removed: Boolean = false,
        temp: Boolean = false,
        lastWatched: Long = 0L,
        timeOffset: Long = 0L,
        duration: Long = 0L,
        timesWatched: Int = 0,
        videoId: String? = null,
        watched: String? = null
    ) = StremioLibraryItem(
        id = id,
        type = type,
        name = id,
        poster = null,
        removed = removed,
        temp = temp,
        lastWatched = lastWatched,
        timeOffset = timeOffset,
        duration = duration,
        timesWatched = timesWatched,
        flaggedWatched = 0,
        videoId = videoId,
        watched = watched
    )
}
