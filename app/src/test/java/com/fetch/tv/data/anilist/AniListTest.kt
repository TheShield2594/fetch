package com.fetch.tv.data.anilist

import com.fetch.tv.core.tracking.TrackingImportSnapshot
import com.fetch.tv.core.tracking.buildAniListSnapshot
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
            AniListProgressUpdate(1, AniListStatus.CURRENT),
            nextAniListProgress(entry = null, episode = 1)
        )
        assertEquals(
            AniListProgressUpdate(5, AniListStatus.CURRENT),
            nextAniListProgress(entry(AniListStatus.CURRENT, progress = 3, episodes = 12), episode = 5)
        )
        assertEquals(
            AniListProgressUpdate(12, AniListStatus.COMPLETED),
            nextAniListProgress(entry(AniListStatus.CURRENT, progress = 11, episodes = 12), episode = 12)
        )
        // Planned or paused shows start being watched.
        assertEquals(
            AniListProgressUpdate(1, AniListStatus.CURRENT),
            nextAniListProgress(entry(AniListStatus.PLANNING, progress = 0, episodes = 12), episode = 1)
        )
        assertEquals(
            AniListProgressUpdate(4, AniListStatus.REPEATING),
            nextAniListProgress(entry(AniListStatus.REPEATING, progress = 3, episodes = 12), episode = 4)
        )
    }

    @Test
    fun `an earlier episode, a completed entry or a bad number changes nothing`() {
        assertNull(nextAniListProgress(entry(AniListStatus.CURRENT, progress = 6, episodes = 12), episode = 4))
        assertNull(nextAniListProgress(entry(AniListStatus.CURRENT, progress = 6, episodes = 12), episode = 6))
        assertNull(nextAniListProgress(entry(AniListStatus.COMPLETED, progress = 12, episodes = 12), episode = 3))
        assertNull(nextAniListProgress(null, episode = 0))
    }

    @Test
    fun `an episode past the announced count is capped at it`() {
        assertEquals(
            AniListProgressUpdate(12, AniListStatus.COMPLETED),
            nextAniListProgress(entry(AniListStatus.CURRENT, progress = 10, episodes = 12), episode = 13)
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

        assertEquals(listOf(21L, 199L), entries.map { it.mediaId })
        assertEquals(21L, entries[0].malId)
        assertNull(entries[1].malId)
        assertEquals(true, entries[1].isMovie)
        assertEquals(AniListStatus.COMPLETED, entries[1].status)
    }

    @Test
    fun `import marks every episode up to the entry's progress`() {
        val snapshot = buildAniListSnapshot(
            listOf(
                listEntry(21, mal = 21, progress = 3, updatedAt = 1_700_000_000L),
                listEntry(199, mal = null, progress = 1, isMovie = true, status = AniListStatus.COMPLETED),
                listEntry(5, mal = 5, progress = 0, status = AniListStatus.PLANNING),
                listEntry(6, mal = 6, progress = 2, status = AniListStatus.PLANNING)
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
        val snapshot = buildAniListSnapshot(listOf(listEntry(21, mal = 21, progress = 2)))

        val plan = planTrackingImport(snapshot, TrackingImportSnapshot())

        assertEquals(2, plan.watched.size)
        assertEquals(0, plan.watchedSkipped)
    }

    private fun entry(status: AniListStatus?, progress: Int, episodes: Int?) =
        AniListEntry(mediaId = 1, status = status, progress = progress, episodes = episodes)

    private fun listEntry(
        id: Long,
        mal: Long?,
        progress: Int,
        isMovie: Boolean = false,
        status: AniListStatus = AniListStatus.CURRENT,
        updatedAt: Long = 0L
    ) = AniListListEntry(
        mediaId = id,
        malId = mal,
        title = "Title $id",
        isMovie = isMovie,
        status = status,
        progress = progress,
        updatedAt = updatedAt,
        coverUrl = null
    )
}
