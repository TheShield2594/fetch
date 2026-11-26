package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWatchHistoryTest {

    @Test
    fun `shuffle grouping merges watched marks and completed progress per series`() {
        val watched = listOf(
            watchedItem("show", 1, 1),
            watchedItem("show", 1, 2),
            watchedItem("movie", null, null),
            watchedItem("other", 2, 3)
        )
        val progress = listOf(
            progress("show", 1, 3, completed = true),
            progress("show", 1, 4, completed = false),
            progress("show", 1, 1, completed = true)
        )

        val grouped = groupWatchedEpisodesForShuffle(watched, progress)

        assertEquals(setOf(1 to 1, 1 to 2, 1 to 3), grouped["show"])
        assertEquals(setOf(2 to 3), grouped["other"])
        assertNull(grouped["movie"])
    }

    @Test
    fun `latest watched episode is the highest season then episode of each requested series`() {
        val watched = listOf(
            watchedItem("a", 1, 9, watchedAt = 1),
            watchedItem("a", 2, 1, watchedAt = 2),
            watchedItem("a", 2, 1, watchedAt = 3),
            watchedItem("a", 1, 10, watchedAt = 4),
            watchedItem("b", 3, 3),
            watchedItem("c", null, null)
        )

        val latest = latestWatchedEpisodeByContentId(watched, setOf("a", "c", "missing"))

        val a = latest.getValue("a")
        assertEquals(2, a.season)
        assertEquals(1, a.episode)
        // The first of two equal episodes wins, as maxWithOrNull did.
        assertEquals(2L, a.watchedAt)
        assertFalse("b" in latest)
        assertFalse("c" in latest)
        assertTrue(latestWatchedEpisodeByContentId(watched, emptySet()).isEmpty())
    }

    @Test
    fun `series status keys cover series and tv items once, ignoring movies`() {
        val items = sequenceOf(
            meta("s1", ContentType.SERIES),
            meta("m1", ContentType.MOVIE),
            meta("s1", ContentType.SERIES),
            meta("t1", ContentType.TV)
        )

        val keys = homeSeriesStatusKeys(items)

        assertEquals(
            mapOf(
                homeItemStatusKey("s1", "series") to "s1",
                homeItemStatusKey("t1", "tv") to "t1"
            ),
            keys
        )
    }

    @Test
    fun `series status keys are equal for the same series in a different order`() {
        val first = homeSeriesStatusKeys(sequenceOf(meta("s1", ContentType.SERIES), meta("s2", ContentType.SERIES)))
        val reordered = homeSeriesStatusKeys(
            sequenceOf(meta("s2", ContentType.SERIES), meta("m", ContentType.MOVIE), meta("s1", ContentType.SERIES))
        )
        val changed = homeSeriesStatusKeys(sequenceOf(meta("s1", ContentType.SERIES), meta("s3", ContentType.SERIES)))

        assertEquals(first, reordered)
        assertFalse(first == changed)
    }

    @Test
    fun `bounded LRU map evicts the least recently used entry`() {
        val map = boundedLruMap<String, Int?>(maxSize = 2)
        map["a"] = 1
        map["b"] = null
        map["a"] // touch: b is now the eldest
        map["c"] = 3

        assertEquals(2, map.size)
        assertTrue("a" in map)
        assertFalse("b" in map)
        assertTrue("c" in map)
    }

    @Test
    fun `bounded LRU map removes negative entries through entries while iterating`() {
        val map = boundedLruMap<String, Int?>(maxSize = 10)
        map["x|1|1"] = null
        map["x|1|2"] = 5
        map["y|1|1"] = null

        synchronized(map) {
            map.entries.removeAll { (key, value) -> value == null && key.substringBefore('|') == "x" }
        }

        assertEquals(setOf("x|1|2", "y|1|1"), map.keys)
    }

    @Test
    fun `a paginated row stops growing at the Home cap`() {
        val below = row(items = 5)
        assertTrue(below.cappedForHome(maxItems = 6) === below)

        val over = row(items = 8).cappedForHome(maxItems = 6)
        assertEquals(6, over.items.size)
        assertFalse(over.hasMore)

        val exact = row(items = 6).cappedForHome(maxItems = 6)
        assertEquals(6, exact.items.size)
        assertFalse(exact.hasMore)
    }

    @Test
    fun `meta for a movie is asked for as a movie only, a series also as series and tv`() {
        assertEquals(listOf("movie"), cwMetaTypeCandidates("movie"))
        assertEquals(listOf("series", "tv"), cwMetaTypeCandidates("series"))
        assertEquals(listOf("tv", "series"), cwMetaTypeCandidates("tv"))
        assertEquals(listOf("anime", "series", "tv"), cwMetaTypeCandidates("anime"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `nothing is collected while the player is open and the latest value arrives on close`() = runTest {
        val source = MutableStateFlow(1)
        val playerOpen = MutableStateFlow(false)
        val seen = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            source.pausedWhilePlayerOpen(playerOpen).toList(seen)
        }

        playerOpen.value = true
        source.value = 2
        source.value = 3
        assertEquals(listOf(1), seen)

        playerOpen.value = false
        assertEquals(listOf(1, 3), seen)

        source.value = 4
        assertEquals(listOf(1, 3, 4), seen)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the first value gets through even with the player already open`() = runTest {
        val source = MutableStateFlow(1)
        val playerOpen = MutableStateFlow(true)
        val seen = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            source.pausedWhilePlayerOpen(playerOpen).toList(seen)
        }

        source.value = 2
        assertEquals(listOf(1), seen)

        playerOpen.value = false
        assertEquals(listOf(1, 2), seen)
        job.cancel()
    }

    private fun watchedItem(contentId: String, season: Int?, episode: Int?, watchedAt: Long = 0L) = WatchedItem(
        contentId = contentId,
        contentType = if (season == null) "movie" else "series",
        title = contentId,
        season = season,
        episode = episode,
        watchedAt = watchedAt
    )

    private fun progress(contentId: String, season: Int, episode: Int, completed: Boolean) = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = if (completed) 2_400_000L else 60_000L,
        duration = 2_400_000L,
        lastWatched = 1L
    )

    private fun meta(id: String, type: ContentType) = MetaPreview(
        id = id,
        type = type,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun row(items: Int) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://example.test",
        catalogId = "top",
        catalogName = "Top",
        type = ContentType.MOVIE,
        items = (0 until items).map { meta("m$it", ContentType.MOVIE) },
        hasMore = true
    )

    @Test
    fun `bounded lru map reports each evicted entry`() {
        val evicted = mutableListOf<Pair<String, Int?>>()
        val map = boundedLruMap<String, Int?>(maxSize = 2) { key, value -> evicted += key to value }
        map["a"] = null
        map["b"] = 2
        map["c"] = 3

        assertEquals(listOf("a" to null), evicted)
        assertEquals(setOf("b", "c"), map.keys)
    }
}
