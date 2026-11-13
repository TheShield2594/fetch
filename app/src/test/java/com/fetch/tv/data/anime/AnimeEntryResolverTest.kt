package com.fetch.tv.data.anime

import com.fetch.tv.core.tracking.buildTrackingMediaReference
import com.fetch.tv.data.anilist.AniListApi
import com.fetch.tv.data.repository.SimklIdResolver
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeEntryResolverTest {
    private val simkl = mockk<SimklIdResolver>()
    private val resolver = AnimeEntryResolver(mockk<AniListApi>(), simkl)

    private fun episode(season: Int, number: Int) =
        buildTrackingMediaReference("series", "tt0388629", videoId = "tt0388629:$season:$number", seasonNumber = season, episodeNumber = number)

    @Test
    fun `an IMDb episode the mapping places goes to its entry and number`() = runTest {
        val season2 = SimklIdResolver.ResolvedIds(simklId = 2, type = "anime", anilist = "200", mal = "20", tvdbSeason = 2)
        coEvery { simkl.resolveIdsForImdbEpisode("tt0388629", 2, 3) } returns season2
        coEvery { simkl.getEpisodeMapping(2, "anime") } returns listOf(SimklIdResolver.EpisodeMapping(3, 2, 3))

        assertEquals(AnimeTarget(200, 20, 3), resolver.forAniList(episode(2, 3), token = null))
    }

    @Test
    fun `an entry known to own the season keeps the episode number without a mapping`() = runTest {
        val season2 = SimklIdResolver.ResolvedIds(simklId = 2, type = "anime", anilist = "200", tvdbSeason = 2)
        coEvery { simkl.resolveIdsForImdbEpisode("tt0388629", 2, 3) } returns season2
        coEvery { simkl.getEpisodeMapping(2, "anime") } returns emptyList()

        assertEquals(3, resolver.forAniList(episode(2, 3), token = null)?.episode)
    }

    @Test
    fun `Simkl's fallback to the first entry is not trusted for a later season`() = runTest {
        // Simkl couldn't find season 2's entry and returned season 1's, with no mapping for S2E3.
        val season1 = SimklIdResolver.ResolvedIds(simklId = 1, type = "anime", anilist = "100", tvdbSeason = 1)
        coEvery { simkl.resolveIdsForImdbEpisode("tt0388629", 2, 3) } returns season1
        coEvery { simkl.getEpisodeMapping(1, "anime") } returns emptyList()

        assertNull(resolver.forAniList(episode(2, 3), token = null))
    }
}
