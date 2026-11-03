package com.fetch.desktop.sync

import com.fetch.desktop.collections.CollectionSourceResolver
import com.fetch.desktop.collections.CollectionsCodec
import com.fetch.desktop.collections.CollectionsState
import com.fetch.desktop.collections.CollectionsStore
import com.fetch.desktop.collections.CollectionsSyncMerge
import com.fetch.desktop.data.AddonClient
import com.fetch.desktop.tmdb.TmdbClient
import com.fetch.desktop.tmdb.TmdbCollectionSources
import com.fetch.tv.domain.model.AddonCatalogCollectionSource
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.CollectionFolder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CollectionsSyncTest {

    private fun client(http: SyncFakeHttp) = SyncClient(
        http = http.client,
        auth = SyncAuthClient(http.client, testServer, nowEpochSec = { 1_000_000_000L }),
        session = testSession,
        originClientId = TEST_ORIGIN,
        nowEpochSec = { 1_000_000_000L },
    )

    private fun rpcs(vararg routes: Pair<String, String>): (Request) -> Pair<Int, String> {
        val map = routes.toMap()
        return { request ->
            val name = request.url.encodedPath.removePrefix("/rest/v1/rpc/")
            map[name]?.let { 200 to it } ?: (404 to "{}")
        }
    }

    private val mine = listOf(
        Collection("c1", "Mine", folders = listOf(CollectionFolder("f1", "Folder", sources = listOf(AddonCatalogCollectionSource("a", "movie", "top")))))
    )
    private val theirs = listOf(
        Collection("c2", "From the TV", pinToTop = true, folders = listOf(CollectionFolder("f2", "TV folder")))
    )

    private val tvRow = """[{"profile_id":2,"collections_json":${CollectionsCodec.encode(theirs)},"updated_at":"2026-01-01T00:00:00Z"}]"""

    @Test
    fun `pull reads the profile's row and push sends the whole list with the origin`() = runBlocking {
        val http = SyncFakeHttp(rpcs("sync_pull_collections" to tvRow, "sync_push_collections" to ""))
        val remote = client(http)
        assertEquals(theirs, CollectionsCodec.parse(remote.pullCollections(2)))
        val pull = Json.parseToJsonElement(http.sent[0].body).jsonObject
        assertEquals(2, pull["p_profile_id"]!!.jsonPrimitive.int)

        remote.pushCollections(3, CollectionsCodec.encodeElement(mine) as JsonArray)
        val push = Json.parseToJsonElement(http.sent[1].body).jsonObject
        assertEquals("/rest/v1/rpc/sync_push_collections", http.sent[1].path)
        assertEquals(3, push["p_profile_id"]!!.jsonPrimitive.int)
        assertEquals(TEST_ORIGIN, push["p_origin_client_id"]!!.jsonPrimitive.content)
        assertEquals(mine, CollectionsCodec.parse(push["p_collections_json"]!!.jsonArray))
    }

    @Test
    fun `no row is null, and a json column sent as a string still reads`() = runBlocking {
        assertNull(client(SyncFakeHttp(rpcs("sync_pull_collections" to "[]"))).pullCollections(1))
        val asString = """[{"profile_id":1,"collections_json":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(CollectionsCodec.encode(theirs)))}}]"""
        assertEquals(theirs, CollectionsCodec.parse(client(SyncFakeHttp(rpcs("sync_pull_collections" to asString))).pullCollections(1)))
    }

    @Test
    fun `merge follows the TV - the server wins, but never wipes local ones`() {
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

    private fun state(dir: java.io.File, scope: kotlinx.coroutines.CoroutineScope, remote: SyncClient? = null) = CollectionsState(
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
        remote = { remote },
        profileId = 2,
        pushDelayMs = 0
    )

    @Test
    fun `syncing a profile applies the server's collections and saves them`() = runBlocking {
        val dir = Files.createTempDirectory("collections-sync").toFile()
        try {
            CollectionsStore.inProfile(dir).save(mine)
            val http = SyncFakeHttp(rpcs("sync_pull_collections" to tvRow))
            val collections = state(dir, this)
            collections.sync(client(http))
            assertEquals(theirs, collections.collections)
            assertEquals(theirs, CollectionsStore.inProfile(dir).load())
            assertEquals(theirs, collections.pinned)
            assertEquals(1, http.sent.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `syncing pushes ours when the server has none, and an edit pushes too`() = runBlocking {
        val dir = Files.createTempDirectory("collections-sync").toFile()
        try {
            CollectionsStore.inProfile(dir).save(mine)
            val http = SyncFakeHttp(rpcs("sync_pull_collections" to "[]", "sync_push_collections" to ""))
            val remote = client(http)
            val collections = state(dir, this, remote)
            collections.sync(remote)
            assertEquals(mine, collections.collections)
            assertEquals("/rest/v1/rpc/sync_push_collections", http.sent.last().path)

            collections.setPinned("c1", true)
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(50)
            val pushed = Json.parseToJsonElement(http.sent.last().body).jsonObject["p_collections_json"]!!.jsonArray
            assertTrue(CollectionsCodec.parse(pushed).single().pinToTop)
        } finally {
            dir.deleteRecursively()
        }
    }
}
