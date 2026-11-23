package com.fetch.tv.core.tracking

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** A play a tracker didn't take is kept and sent again, not dropped (#619). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class ScrobbleOutboxTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("scrobble_outbox_test", Context.MODE_PRIVATE)

    private val episode = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "A show",
        year = 2024,
        ids = TrackingExternalIds(imdb = "tt0000001", tmdb = 42L, trakt = 7L),
        episode = TrackingEpisode(season = 2, number = 5, title = "The one", tmdbId = 99L),
        catalog = TrackingCatalogReference("tt0000001", "series", "tt0000001:2:5"),
        posterUrl = "https://img.example/p.jpg"
    )
    private val watched = TrackingScrobbleEvent(episode, progressPercent = 95.0, profileId = 2)

    private var now = 1_700_000_000_000L

    private class Provider(
        id: TrackingProviderId,
        signedIn: Boolean,
        override val scrobbler: TrackingScrobbler? = null
    ) : TrackingProvider {
        override val descriptor = TrackingProviderDescriptor(id, id.name, setOf(TrackingCapability.SCROBBLE))
        val auth = MutableStateFlow(signedIn)
        override val isAuthenticated: StateFlow<Boolean> = auth
    }

    private class Writer(override val providerId: TrackingProviderId) : TrackingHistoryWriter {
        var fail = true
        val added = mutableListOf<Pair<Int, TrackingHistoryItem>>()

        override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
            if (fail) throw IOException("offline")
            items.forEach { added += profileId to it }
            return TrackingMutationResult(items.size)
        }

        override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>) =
            TrackingMutationResult(0)
    }

    private class Scrobbler(override val providerId: TrackingProviderId) : TrackingScrobbler {
        val sent = mutableListOf<Pair<TrackingScrobbleAction, TrackingScrobbleEvent>>()
        override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
            sent += action to event
        }
    }

    private val trakt = Provider(TrackingProviderId.TRAKT, signedIn = true)
    private val aniListScrobbler = Scrobbler(TrackingProviderId.ANILIST)
    private val aniList = Provider(TrackingProviderId.ANILIST, signedIn = true, scrobbler = aniListScrobbler)
    private val traktWriter = Writer(TrackingProviderId.TRAKT)

    private fun outbox() = ScrobbleOutbox(
        prefsProvider = { prefs },
        providers = TrackingProviderRegistry(listOf(trakt, aniList)),
        historyWriters = TrackingHistoryWriterRegistry(setOf(traktWriter)),
        clock = { now }
    )

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `a failed play survives a restart and goes through history with its own time`() = runBlocking {
        outbox().enqueue(TrackingProviderId.TRAKT, watched)
        val watchedAt = now
        now += 60_000

        val afterRestart = outbox()
        assertEquals(listOf(watched), afterRestart.entries().map { it.event })

        afterRestart.drain()
        assertEquals(1, afterRestart.entries().single().attempts)

        traktWriter.fail = false
        afterRestart.drain()

        assertTrue(afterRestart.entries().isEmpty())
        val (profileId, item) = traktWriter.added.single()
        assertEquals(2, profileId)
        assertEquals(episode, item.media)
        assertEquals(watchedAt, item.watchedAtEpochMs)
    }

    @Test
    fun `a provider with no history writer gets the stop again`() = runBlocking {
        val outbox = outbox()
        outbox.enqueue(TrackingProviderId.ANILIST, watched)

        outbox.drain()

        assertEquals(listOf(TrackingScrobbleAction.STOP to watched), aniListScrobbler.sent)
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun `marks for a signed-out provider, old marks and repeats of the same play are dropped`() = runBlocking {
        val outbox = outbox()
        outbox.enqueue(TrackingProviderId.TRAKT, watched)
        outbox.enqueue(TrackingProviderId.TRAKT, watched)
        assertEquals(1, outbox.entries().size)

        trakt.auth.value = false
        outbox.drain()
        assertTrue(outbox.entries().isEmpty())

        trakt.auth.value = true
        outbox.enqueue(TrackingProviderId.TRAKT, watched)
        now += ScrobbleOutbox.MAX_AGE_MS + 1
        outbox.drain()
        assertTrue(outbox.entries().isEmpty())
        assertTrue(traktWriter.added.isEmpty())
    }

    @Test
    fun `the coordinator keeps failed plays only`() = runBlocking {
        val failing = object : TrackingScrobbler {
            override val providerId = TrackingProviderId.TRAKT
            override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
                throw IOException("Trakt is down")
            }
        }
        val provider = Provider(TrackingProviderId.TRAKT, signedIn = true, scrobbler = failing)
        val outbox = ScrobbleOutbox({ prefs }, TrackingProviderRegistry(listOf(provider)), TrackingHistoryWriterRegistry(emptySet()), { now })
        val coordinator = TrackingScrobbleCoordinator(TrackingProviderRegistry(listOf(provider)), outbox)

        coordinator.scrobble(TrackingScrobbleAction.STOP, watched.copy(progressPercent = 40.0))
        coordinator.scrobble(TrackingScrobbleAction.PAUSE, watched.copy(progressPercent = 85.0))
        assertTrue(outbox.entries().isEmpty())

        coordinator.scrobble(TrackingScrobbleAction.STOP, watched)
        assertEquals(listOf(TrackingProviderId.TRAKT), outbox.entries().map { it.provider })
    }

    @Test
    fun `entries round-trip through storage`() {
        val entry = ScrobbleOutbox.Entry(TrackingProviderId.ANILIST, watched, watchedAtMs = 123L, attempts = 4)
        val decoded = ScrobbleOutbox.decodeEntry(ScrobbleOutbox.encode(listOf(entry)).single() as kotlinx.serialization.json.JsonObject)
        assertEquals(entry, decoded)
    }
}
