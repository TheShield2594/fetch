package com.fetch.tv.data.simkl

import com.fetch.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SimklSyncRepositoryProfileTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val media = SimklMedia(
        title = "The Walking Dead",
        year = 2010,
        ids = mapOf("simkl" to JsonPrimitive(2090L), "imdb" to JsonPrimitive("tt1520211"))
    )

    private class MemoryStorage : SimklSyncStorage {
        val payloads = mutableMapOf<Int, String>()
        override suspend fun load(profileId: Int): String? = payloads[profileId]
        override suspend fun save(profileId: Int, payload: String) { payloads[profileId] = payload }
        override suspend fun remove(profileId: Int) { payloads.remove(profileId) }
    }

    @Test
    fun `scrobble commit builds on the originating profile's saved snapshot, not the shown state`() = runBlocking {
        val storage = MemoryStorage()
        val active = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager> { every { activeProfileId } returns active }
        storage.payloads[1] = json.encodeToString(snapshotWithEpisodes(1))
        storage.payloads[2] = json.encodeToString(snapshotWithEpisodes(1, 2, 3))
        val repository = SimklSyncRepository(
            engine = mockk(relaxed = true),
            storage = storage,
            authRepository = mockk(relaxed = true),
            authStorage = mockk(relaxed = true),
            profileManager = profileManager
        )
        repository.ensureLoaded()

        // What a profile switch does to the shown state without taking the snapshot lock: the
        // in-memory snapshot is no longer profile 1's. A commit for profile 1 must not save it.
        storage.payloads[1] = json.encodeToString(snapshotWithEpisodes(1, 4))

        repository.commitScrobble(stopOf(episode = 5), expectedProfileId = 1)

        val saved = json.decodeFromString<SimklSyncSnapshot>(storage.payloads.getValue(1))
        assertEquals(
            listOf(1, 4, 5),
            saved.entries.single().seasons.single().episodes.mapNotNull(SimklEpisode::number).sorted()
        )
        val other = json.decodeFromString<SimklSyncSnapshot>(storage.payloads.getValue(2))
        assertEquals(listOf(1, 2, 3), other.entries.single().seasons.single().episodes.mapNotNull(SimklEpisode::number))
    }

    @Test
    fun `scrobble commit for another profile saves nothing`() = runBlocking {
        val storage = MemoryStorage()
        val active = MutableStateFlow(2)
        val profileManager = mockk<ProfileManager> { every { activeProfileId } returns active }
        storage.payloads[1] = json.encodeToString(snapshotWithEpisodes(1))
        storage.payloads[2] = json.encodeToString(snapshotWithEpisodes(1))
        val before = storage.payloads.toMap()
        val repository = SimklSyncRepository(
            engine = mockk(relaxed = true),
            storage = storage,
            authRepository = mockk(relaxed = true),
            authStorage = mockk(relaxed = true),
            profileManager = profileManager
        )

        repository.commitScrobble(stopOf(episode = 2), expectedProfileId = 1)

        assertEquals(before, storage.payloads.toMap())
    }

    private fun snapshotWithEpisodes(vararg numbers: Int) = SimklSyncSnapshot(
        isInitialized = true,
        watermark = "v1",
        entries = listOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                status = SimklListStatus.WATCHING,
                show = media,
                seasons = listOf(SimklSeason(number = 1, episodes = numbers.map { SimklEpisode(number = it) }))
            )
        )
    )

    private fun stopOf(episode: Int) = SimklScrobbleResult(
        outcome = SimklScrobbleOutcome.SCROBBLE,
        playbackId = 9L,
        progress = 90.0,
        mediaType = SimklMediaType.SHOWS,
        media = media,
        episode = SimklPlaybackEpisode(season = 1, number = episode)
    )
}
