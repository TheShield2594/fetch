package com.fetch.tv.data.anilist

import com.fetch.tv.core.tracking.TrackingImportSnapshot
import com.fetch.tv.data.anime.AnimeEntry
import com.fetch.tv.data.anime.AnimeListEntry
import com.fetch.tv.data.anime.AnimeListStatus
import com.fetch.tv.data.anime.AnimeProgressUpdate
import com.fetch.tv.data.anime.FlatAnimeEpisode
import com.fetch.tv.data.anime.flatAnimeEpisode
import com.fetch.tv.data.anime.nextAnimeProgress
import com.fetch.tv.core.tracking.buildAnimeListSnapshot
import com.fetch.tv.core.tracking.buildTrackingMediaReference
import com.fetch.tv.core.tracking.planTrackingImport
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniListTest {

    @Test
    fun `progress moves forward and completes on the last episode`() {
        assertEquals(
            AnimeProgressUpdate(1, AnimeListStatus.CURRENT),
            nextAnimeProgress(entry = null, episode = 1)
        )
        assertEquals(
            AnimeProgressUpdate(5, AnimeListStatus.CURRENT),
            nextAnimeProgress(entry(AnimeListStatus.CURRENT, progress = 3, episodes = 12), episode = 5)
        )
        assertEquals(
            AnimeProgressUpdate(12, AnimeListStatus.COMPLETED),
            nextAnimeProgress(entry(AnimeListStatus.CURRENT, progress = 11, episodes = 12), episode = 12)
        )
        // Planned or paused shows start being watched.
        assertEquals(
            AnimeProgressUpdate(1, AnimeListStatus.CURRENT),
            nextAnimeProgress(entry(AnimeListStatus.PLANNING, progress = 0, episodes = 12), episode = 1)
        )
        assertEquals(
            AnimeProgressUpdate(4, AnimeListStatus.REPEATING),
            nextAnimeProgress(entry(AnimeListStatus.REPEATING, progress = 3, episodes = 12), episode = 4)
        )
    }

    @Test
    fun `an earlier episode, a completed entry or a bad number changes nothing`() {
        assertNull(nextAnimeProgress(entry(AnimeListStatus.CURRENT, progress = 6, episodes = 12), episode = 4))
        assertNull(nextAnimeProgress(entry(AnimeListStatus.CURRENT, progress = 6, episodes = 12), episode = 6))
        assertNull(nextAnimeProgress(entry(AnimeListStatus.COMPLETED, progress = 12, episodes = 12), episode = 3))
        assertNull(nextAnimeProgress(null, episode = 0))
    }

    @Test
    fun `an episode past the announced count is capped at it`() {
        assertEquals(
            AnimeProgressUpdate(12, AnimeListStatus.COMPLETED),
            nextAnimeProgress(entry(AnimeListStatus.CURRENT, progress = 10, episodes = 12), episode = 13)
        )
    }

    @Test
    fun `anime catalog video ids name the entry and its own episode`() {
        val kitsu = buildTrackingMediaReference("series", "kitsu:1376", videoId = "kitsu:1376:7", seasonNumber = 1, episodeNumber = 7)
        assertEquals(FlatAnimeEpisode("kitsu", 1376, 7), flatAnimeEpisode(kitsu))

        val imdb = buildTrackingMediaReference("series", "tt0388629", videoId = "tt0388629:1:7", seasonNumber = 1, episodeNumber = 7)
        assertNull(flatAnimeEpisode(imdb))

        val movie = buildTrackingMediaReference("movie", "mal:199", videoId = "mal:199")
        assertNull(flatAnimeEpisode(movie))
    }

    @Test
    fun `parses list entries once each`() {
        val lists = JSONArray(
            """
            [
              {"entries":[
                {"status":"CURRENT","progress":3,"updatedAt":1700000000,
                 "media":{"id":21,"idMal":21,"format":"TV","title":{"userPreferred":"One Piece"},"coverImage":{"large":"https://c/21.jpg"}}},
                {"status":"COMPLETED","progress":1,"updatedAt":1600000000,
                 "media":{"id":199,"idMal":null,"format":"MOVIE","title":{"userPreferred":"A Film"}}}
              ]},
              {"entries":[
                {"status":"CURRENT","progress":3,"updatedAt":1700000000,
                 "media":{"id":21,"idMal":21,"format":"TV","title":{"userPreferred":"One Piece"}}}
              ]}
            ]
            """.trimIndent()
        )

        val entries = parseAniListEntries(lists)

        assertEquals(listOf(21L, 199L), entries.map { it.aniListId })
        assertEquals(21L, entries[0].malId)
        assertNull(entries[1].malId)
        assertEquals(true, entries[1].isMovie)
        assertEquals(AnimeListStatus.COMPLETED, entries[1].status)
    }

    @Test
    fun `import marks every episode up to the entry's progress`() {
        val snapshot = buildAnimeListSnapshot(
            listOf(
                listEntry(21, mal = 21, progress = 3, updatedAt = 1_700_000_000L),
                listEntry(199, mal = null, progress = 1, isMovie = true, status = AnimeListStatus.COMPLETED),
                listEntry(5, mal = 5, progress = 0, status = AnimeListStatus.PLANNING),
                listEntry(6, mal = 6, progress = 2, status = AnimeListStatus.PLANNING)
            )
        )

        val keys = snapshot.watched.map { listOfNotNull(it.contentId, it.episode).joinToString(":") }
        assertEquals(listOf("mal:21:1", "mal:21:2", "mal:21:3", "anilist:199"), keys)
        assertEquals(1_700_000_000_000L, snapshot.watched.single { it.episode == 3 }.watchedAt)
        assertEquals(0L, snapshot.watched.single { it.episode == 1 }.watchedAt)
        assertEquals("movie", snapshot.watched.single { it.contentId == "anilist:199" }.contentType)
        assertEquals(emptyList<Any>(), snapshot.inProgress)
    }

    @Test
    fun `imported episodes are not whole-series marks, so the plan keeps them`() {
        val snapshot = buildAnimeListSnapshot(listOf(listEntry(21, mal = 21, progress = 2)))

        val plan = planTrackingImport(snapshot, TrackingImportSnapshot())

        assertEquals(2, plan.watched.size)
        assertEquals(0, plan.watchedSkipped)
    }

    private fun entry(status: AnimeListStatus?, progress: Int, episodes: Int?) =
        AnimeEntry(status = status, progress = progress, episodes = episodes)

    private fun listEntry(
        id: Long,
        mal: Long?,
        progress: Int,
        isMovie: Boolean = false,
        status: AnimeListStatus = AnimeListStatus.CURRENT,
        updatedAt: Long = 0L
    ) = AnimeListEntry(
        aniListId = id,
        malId = mal,
        title = "Title $id",
        isMovie = isMovie,
        status = status,
        progress = progress,
        updatedAt = updatedAt,
        coverUrl = null
    )
}
