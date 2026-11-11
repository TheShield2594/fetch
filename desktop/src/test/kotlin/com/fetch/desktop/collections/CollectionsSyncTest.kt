package com.fetch.desktop.collections

import com.fetch.desktop.data.AddonClient
import com.fetch.desktop.tmdb.TmdbClient
import com.fetch.desktop.tmdb.TmdbCollectionSources
import com.fetch.tv.domain.model.AddonCatalogCollectionSource
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CollectionsSyncTest {

    /** A remote holding one profile's collections in memory, recording each push. */
    private class FakeRemote(var stored: JsonArray?) : CollectionsRemote {
        val pushes = mutableListOf<JsonArray>()
        var pulls = 0
        override suspend fun pull(): JsonArray? = stored.also { pulls++ }
        override suspend fun push(collections: JsonArray) {
            pushes += collections
            stored = collections
        }
    }

    private val mine = listOf(
        Collection("c1", "Mine", folders = listOf(CollectionFolder("f1", "Folder", sources = listOf(AddonCatalogCollectionSource("a", "movie", "top")))))
    )
    private val theirs = listOf(
        Collection("c2", "From the TV", pinToTop = true, folders = listOf(CollectionFolder("f2", "TV folder")))
    )

    @Test
    fun `merge follows the TV - the remote wins, but never wipes local ones`() {
        CollectionsSyncMerge.merge(mine, theirs).let {
            assertEquals(theirs, it.local)
            assertTrue(it.changed)
            assertFalse(it.push)
        }
        CollectionsSyncMerge.merge(mine, emptyList()).let {
            assertEquals(mine, it.local)
            assertFalse(it.changed)
            assertTrue(it.push)
        }
        CollectionsSyncMerge.merge(mine, null).let { assertTrue(it.push); assertFalse(it.changed) }
        CollectionsSyncMerge.merge(mine, mine).let { assertFalse(it.push); assertFalse(it.changed) }
        CollectionsSyncMerge.merge(emptyList(), null).let { assertFalse(it.push); assertFalse(it.changed) }
    }

    private fun state(dir: java.io.File, scope: kotlinx.coroutines.CoroutineScope, remote: CollectionsRemote? = null) = CollectionsState(
        scope,
        CollectionsStore.inProfile(dir),
        CollectionSourceResolver(
            addons = { emptyList() },
            addonClient = AddonClient.create(OkHttpClient()),
            tmdb = TmdbCollectionSources(TmdbClient(OkHttpClient(), apiKey = { null })),
            tmdbLanguage = { "en" },
            trakt = { _, _, _, _, _, _ -> null },
            localLists = { emptyList() }
        ),
        addons = { emptyList() },
        pushDelayMs = 0,
        website = { remote }
    )

    @Test
    fun `syncing a profile applies the remote's collections and saves them`() = runBlocking {
        val dir = Files.createTempDirectory("collections-sync").toFile()
        try {
            CollectionsStore.inProfile(dir).save(mine)
            val remote = FakeRemote(CollectionsCodec.encodeElement(theirs) as JsonArray)
            val collections = state(dir, this)
            collections.sync(remote)
            assertEquals(theirs, collections.collections)
            assertEquals(theirs, CollectionsStore.inProfile(dir).load())
            assertEquals(theirs, collections.pinned)
            assertEquals(1, remote.pulls)
            assertTrue(remote.pushes.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `syncing pushes ours when the remote has none, and an edit pushes too`() = runBlocking {
        val dir = Files.createTempDirectory("collections-sync").toFile()
        try {
            CollectionsStore.inProfile(dir).save(mine)
            val remote = FakeRemote(null)
            val collections = state(dir, this, remote)
            collections.sync(remote)
            assertEquals(mine, collections.collections)
            assertEquals(mine, CollectionsCodec.parse(remote.pushes.last()))

            collections.setPinned("c1", true)
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(50)
            assertTrue(CollectionsCodec.parse(remote.pushes.last()).single().pinToTop)
        } finally {
            dir.deleteRecursively()
        }
    }
}
