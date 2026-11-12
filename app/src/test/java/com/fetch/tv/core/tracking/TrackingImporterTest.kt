package com.fetch.tv.core.tracking

import com.fetch.tv.data.local.WatchProgressSource
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingImporterTest {
    private val importer = TrackingImporter(batchSize = 2)

    @Test
    fun `watched marks are added, never removed, and existing marks are skipped`() = runTest {
        val source = FakeEndpoint(
            WatchProgressSource.SIMKL,
            TrackingImportSnapshot(
                watched = listOf(
                    movie("tt1", watchedAt = 100L),
                    episode("tt2", 1, 1, watchedAt = 200L),
                    episode("tt2", 1, 2, watchedAt = 300L)
                )
            )
        )
        val target = FakeEndpoint(
            WatchProgressSource.TRAKT,
            TrackingImportSnapshot(
                watched = listOf(
                    // Same episode under the other spelling of the IMDb id.
                    episode("imdb:tt2", 1, 1, watchedAt = 50L),
                    // Only in the target: an import must leave it alone.
                    movie("tt9", watchedAt = 10L)
                )
            )
        )

        val result = importer.import(source, target)

        assertEquals(setOf("tt1", "tt2:1:2"), target.writtenWatched.map(WatchedItem::key).toSet())
        assertEquals(2, result.watchedImported)
        assertEquals(1, result.watchedSkipped)
        assertEquals(0, result.failed)
        assertEquals(1, target.finishCount)
    }

    @Test
    fun `duplicate source marks import once, keeping the timestamped one`() = runTest {
        val source = FakeEndpoint(
            WatchProgressSource.TRAKT,
            TrackingImportSnapshot(
                watched = collectWatched(
                    items = emptyList(),
                    progress = listOf(progress("tt1", lastWatched = 500L, percent = 100f)),
                    movieIds = setOf("tt1"),
                    episodes = mapOf("tt2" to setOf(1 to 1))
                )
            )
        )
        val target = FakeEndpoint(WatchProgressSource.FETCH_SYNC)

        val result = importer.import(source, target)

        assertEquals(2, result.watchedImported)
        assertEquals(500L, target.writtenWatched.single { it.contentId == "tt1" }.watchedAt)
        assertEquals(0L, target.writtenWatched.single { it.contentId == "tt2" }.watchedAt)
    }

    @Test
    fun `whole-series marks are skipped`() = runTest {
        val source = FakeEndpoint(
            WatchProgressSource.MDBLIST,
            TrackingImportSnapshot(
                watched = listOf(
                    WatchedItem(contentId = "tt3", contentType = "series", title = "Show", watchedAt = 10L),
                    WatchedItem(contentId = "tt4", contentType = "anime", title = "Anime", watchedAt = 10L)
                )
            )
        )
        val target = FakeEndpoint(WatchProgressSource.TRAKT)

        val result = importer.import(source, target)

        assertTrue(target.writtenWatched.isEmpty())
        assertEquals(2, result.watchedSkipped)
        assertEquals(0, target.finishCount)
    }

    @Test
    fun `a resume point never overwrites a newer or undated one in the target`() = runTest {
        val source = FakeEndpoint(
            WatchProgressSource.SIMKL,
            TrackingImportSnapshot(
                inProgress = listOf(
                    progress("older", lastWatched = 100L),
                    progress("newer", lastWatched = 300L),
                    progress("same", lastWatched = 200L),
                    progress("undated", lastWatched = 400L),
                    progress("fresh", lastWatched = 0L)
                )
            )
        )
        val target = FakeEndpoint(
            WatchProgressSource.FETCH_WEBSITE,
            TrackingImportSnapshot(
                inProgress = listOf(
                    progress("older", lastWatched = 200L),
                    progress("newer", lastWatched = 200L),
                    progress("same", lastWatched = 200L),
                    progress("undated", lastWatched = 0L)
                )
            )
        )

        val result = importer.import(source, target)

        assertEquals(listOf("newer", "fresh"), target.writtenProgress.map(WatchProgress::contentId))
        assertEquals(2, result.progressImported)
        assertEquals(3, result.progressSkipped)
    }

    @Test
    fun `a resume point is skipped when the target finished the title later, kept for a rewatch`() = runTest {
        val source = FakeEndpoint(
            WatchProgressSource.TRAKT,
            TrackingImportSnapshot(
                inProgress = listOf(
                    progress("tt1", season = 1, episode = 1, lastWatched = 100L),
                    progress("tt5", lastWatched = 900L)
                )
            )
        )
        val target = FakeEndpoint(
            WatchProgressSource.FETCH_SYNC,
            TrackingImportSnapshot(
                watched = listOf(
                    episode("tt1", 1, 1, watchedAt = 200L),
                    movie("tt5", watchedAt = 500L)
                )
            )
        )

        val result = importer.import(source, target)

        assertEquals(listOf("tt5"), target.writtenProgress.map(WatchProgress::contentId))
        assertEquals(1, result.progressSkipped)
    }

    @Test
    fun `a resume point the source itself finished later is not copied`() = runTest {
        val source = FakeEndpoint(
            WatchProgressSource.SIMKL,
            TrackingImportSnapshot(
                watched = listOf(movie("tt1", watchedAt = 300L)),
                inProgress = listOf(progress("tt1", lastWatched = 200L))
            )
        )
        val target = FakeEndpoint(WatchProgressSource.TRAKT)

        val result = importer.import(source, target)

        assertTrue(target.writtenProgress.isEmpty())
        assertEquals(1, result.watchedImported)
        assertEquals(1, result.progressSkipped)
    }

    @Test
    fun `finished entries in the source are not treated as resume points`() = runTest {
        val source = FakeEndpoint(
            WatchProgressSource.SIMKL,
            TrackingImportSnapshot(inProgress = listOf(progress("tt1", lastWatched = 10L, percent = 95f)))
        )
        val target = FakeEndpoint(WatchProgressSource.TRAKT)

        val result = importer.import(source, target)

        assertTrue(target.writtenProgress.isEmpty())
        assertEquals(0, result.progressImported + result.progressSkipped)
    }

    @Test
    fun `a target that can't write progress gets watched marks only and says so`() = runTest {
        val source = FakeEndpoint(
            WatchProgressSource.TRAKT,
            TrackingImportSnapshot(
                watched = listOf(movie("tt1", watchedAt = 10L)),
                inProgress = listOf(progress("tt2", lastWatched = 10L))
            )
        )
        val target = FakeEndpoint(
            WatchProgressSource.MDBLIST,
            capabilities = setOf(TrackingCapability.WATCHED_READ, TrackingCapability.WATCHED_WRITE, TrackingCapability.PROGRESS_READ)
        )

        val result = importer.import(source, target)

        assertEquals(1, result.watchedImported)
        assertEquals(0, result.progressImported)
        assertEquals(1, result.progressSkipped)
        assertTrue(target.writtenProgress.isEmpty())
        assertEquals(setOf(TrackingCapability.PROGRESS_WRITE), result.unsupported)
    }

    @Test
    fun `a source that can't read watched history only contributes resume points`() = runTest {
        val source = FakeEndpoint(
            WatchProgressSource.SIMKL,
            TrackingImportSnapshot(
                watched = listOf(movie("tt1", watchedAt = 10L)),
                inProgress = listOf(progress("tt2", lastWatched = 10L))
            ),
            capabilities = setOf(TrackingCapability.PROGRESS_READ)
        )
        val target = FakeEndpoint(WatchProgressSource.TRAKT)

        val result = importer.import(source, target)

        assertTrue(target.writtenWatched.isEmpty())
        assertEquals(listOf("tt2"), target.writtenProgress.map(WatchProgress::contentId))
        assertEquals(setOf(TrackingCapability.WATCHED_READ), result.unsupported)
    }

    @Test
    fun `nothing is read when neither part of the import is possible`() = runTest {
        val source = mockk<TrackingImportEndpoint> {
            every { this@mockk.source } returns WatchProgressSource.SIMKL
            every { capabilities } returns setOf(TrackingCapability.WATCHED_READ)
        }
        val target = mockk<TrackingImportEndpoint> {
            every { this@mockk.source } returns WatchProgressSource.TRAKT
            every { capabilities } returns setOf(TrackingCapability.PROGRESS_WRITE)
        }

        val result = importer.import(source, target)

        coVerify(exactly = 0) { source.read() }
        coVerify(exactly = 0) { target.read() }
        assertEquals(
            setOf(TrackingCapability.PROGRESS_READ, TrackingCapability.WATCHED_WRITE),
            result.unsupported
        )
        assertEquals(0, result.imported)
    }

    @Test
    fun `rejected and failed batches are reported, the rest still imports`() = runTest {
        val source = FakeEndpoint(
            WatchProgressSource.SIMKL,
            TrackingImportSnapshot(
                watched = listOf(
                    movie("tt1", watchedAt = 40L),
                    movie("tt2", watchedAt = 30L),
                    movie("tt3", watchedAt = 20L),
                    movie("tt4", watchedAt = 10L)
                )
            )
        )
        val target = mockk<TrackingImportEndpoint>(relaxed = true) {
            every { this@mockk.source } returns WatchProgressSource.TRAKT
            every { capabilities } returns ALL
            coEvery { read() } returns TrackingImportSnapshot()
            // First batch: one of two not found. Second batch: the request fails.
            coEvery { writeWatched(match { it.first().contentId == "tt1" }) } returns TrackingImportWriteResult(1, 1)
            coEvery { writeWatched(match { it.first().contentId == "tt3" }) } throws IllegalStateException("offline")
        }

        val result = importer.import(source, target)

        assertEquals(1, result.watchedImported)
        assertEquals(3, result.failed)
        coVerify(exactly = 1) { target.finish() }
    }

    @Test
    fun `ids are matched across spellings`() {
        assertTrue("imdb:tt1" in trackingImportAliases("tt1"))
        assertTrue("tmdb:42" in trackingImportAliases("tmdb:42"))
        assertEquals(setOf("tt1:2:3", "imdb:tt1"), trackingImportAliases("tt1:2:3"))
    }

    private class FakeEndpoint(
        override val source: WatchProgressSource,
        private val snapshot: TrackingImportSnapshot = TrackingImportSnapshot(),
        override val capabilities: Set<TrackingCapability> = ALL
    ) : TrackingImportEndpoint {
        val writtenWatched = mutableListOf<WatchedItem>()
        val writtenProgress = mutableListOf<WatchProgress>()
        var finishCount = 0

        override suspend fun read(): TrackingImportSnapshot = snapshot

        override suspend fun writeWatched(items: List<WatchedItem>): TrackingImportWriteResult {
            writtenWatched += items
            return TrackingImportWriteResult(items.size)
        }

        override suspend fun writeProgress(items: List<WatchProgress>): TrackingImportWriteResult {
            writtenProgress += items
            return TrackingImportWriteResult(items.size)
        }

        override suspend fun finish() {
            finishCount++
        }
    }

    private companion object {
        val ALL = setOf(
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE
        )

        fun movie(id: String, watchedAt: Long) =
            WatchedItem(contentId = id, contentType = "movie", title = id, watchedAt = watchedAt)

        fun episode(id: String, season: Int, episode: Int, watchedAt: Long) = WatchedItem(
            contentId = id,
            contentType = "series",
            title = id,
            season = season,
            episode = episode,
            watchedAt = watchedAt
        )

        fun progress(
            id: String,
            lastWatched: Long,
            season: Int? = null,
            episode: Int? = null,
            percent: Float = 40f
        ) = WatchProgress(
            contentId = id,
            contentType = if (season != null) "series" else "movie",
            name = id,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = id,
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 0L,
            duration = 0L,
            lastWatched = lastWatched,
            progressPercent = percent
        )
    }
}

private val WatchedItem.key: String
    get() = if (season != null) "$contentId:$season:$episode" else contentId
