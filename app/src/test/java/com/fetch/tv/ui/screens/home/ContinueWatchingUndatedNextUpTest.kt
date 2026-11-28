package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A missing release date counts as unaired, and never lets a later episode stand in for it. */
class ContinueWatchingUndatedNextUpTest {
    private val aired = "2020-01-01T00:00:00Z"

    @Test
    fun `an undated episode is not skipped for the dated one after it`() {
        val meta = meta(ep(1, 1, aired), ep(1, 2, null), ep(1, 3, aired))
        assertNull(resolveNextUpVideoFromMeta(seed(1, 1), meta, showUnairedNextUp = false))
    }

    @Test
    fun `with show unaired on the undated episode itself is offered`() {
        val meta = meta(ep(1, 1, aired), ep(1, 2, null), ep(1, 3, aired))
        val next = resolveNextUpVideoFromMeta(seed(1, 1), meta, showUnairedNextUp = true)
        assertEquals(1 to 2, next?.season to next?.episode)
    }

    @Test
    fun `an undated season premiere is offered when show unaired is on`() {
        val meta = meta(ep(1, 1, aired), ep(2, 1, null), ep(2, 2, aired))
        val next = resolveNextUpVideoFromMeta(seed(1, 1), meta, showUnairedNextUp = true)
        assertEquals(2 to 1, next?.season to next?.episode)
    }

    @Test
    fun `an undated season premiere does not hand Next Up to its second episode`() {
        val meta = meta(ep(1, 1, aired), ep(2, 1, null), ep(2, 2, aired))
        assertNull(resolveNextUpVideoFromMeta(seed(1, 1), meta, showUnairedNextUp = false))
    }

    @Test
    fun `dated aired episodes are unaffected`() {
        val meta = meta(ep(1, 1, aired), ep(1, 2, aired))
        val next = resolveNextUpVideoFromMeta(seed(1, 1), meta, showUnairedNextUp = false)
        assertEquals(1 to 2, next?.season to next?.episode)
    }

    private fun seed(season: Int, episode: Int) = WatchProgress(
        "tt1", "series", "Series", null, null, null, "tt1:$season:$episode", season, episode, null,
        100, 100, 1, progressPercent = 100f
    )

    private fun ep(season: Int, episode: Int, released: String?) =
        CwVideoSummary("tt1:$season:$episode", "Episode", released, null, season, episode, null)

    private fun meta(vararg videos: CwVideoSummary) = CwMetaSummary(
        "tt1", "Series", null, null, null, null, emptyList(), null, null, null, null, videos.toList()
    )
}
