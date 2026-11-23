package com.fetch.tv.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.backend.SyncBackend
import com.fetch.tv.core.sync.backend.SyncBackends
import com.fetch.tv.data.local.ProfileDataStoreFactory
import com.fetch.tv.domain.model.UserProfile
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile settings blob (#580): every synced DataStore feature survives export and import
 * unchanged, values that belong to one device or carry credentials never leave it and survive an
 * import, and keys this version doesn't know are ignored rather than breaking the import.
 */
class ProfileSettingsSyncServiceTest {

    /** The features the blob carries. A feature dropped from the service's list fails here. */
    private val syncedFeatures = listOf(
        "theme_settings",
        "layout_settings",
        "experience_settings",
        "player_settings",
        "stream_badge_settings",
        "trailer_settings",
        "tmdb_settings",
        "mdblist_settings",
        "trakt_settings",
        "animeskip_settings",
        "track_preference",
        "episode_queue"
    )

    /** One value of every supported type in every synced feature. */
    private fun MutablePreferencesScope.syncedValues(feature: String) {
        this[stringPreferencesKey("${feature}_string")] = "value of $feature"
        this[booleanPreferencesKey("${feature}_boolean")] = true
        this[intPreferencesKey("${feature}_int")] = 42
        this[longPreferencesKey("${feature}_long")] = 9_000_000_000L
        this[floatPreferencesKey("${feature}_float")] = 1.5f
        this[doublePreferencesKey("${feature}_double")] = 2.25
        this[stringSetPreferencesKey("${feature}_set")] = setOf("b", "a")
    }

    @Test
    fun everySyncedFeatureAndValueTypeRoundTrips() = runBlocking {
        val harness = Harness()
        syncedFeatures.forEach { feature -> harness.edit(1, feature) { syncedValues(feature) } }
        harness.edit(1, "layout_settings") { this[stringPreferencesKey("discover_location")] = "IN_SIDEBAR" }

        val exported = harness.service.exportLocalSettings(1)
        assertEquals(syncedFeatures.toSet(), exported.keys)

        harness.service.importLocalSettings(2, exported)

        syncedFeatures.forEach { feature ->
            assertEquals(feature, harness.read(1, feature).asMap(), harness.read(2, feature).asMap().filterKeys {
                it.name != "last_non_off_discover_location"
            })
        }
        assertEquals("IN_SIDEBAR", harness.read(2, "layout_settings")[stringPreferencesKey("last_non_off_discover_location")])
    }

    @Test
    fun deviceOnlyAndCredentialKeysStayOnTheDevice() = runBlocking {
        val harness = Harness()
        val kept = listOf(
            "player_settings" to "decoder_priority",
            "player_settings" to "frame_rate_matching",
            "trailer_settings" to "trailer_allow_4k",
            "mdblist_settings" to "mdblist_api_key",
            "animeskip_settings" to "animeskip_client_id",
            "layout_settings" to "home_catalog_order_keys",
            "layout_settings" to "disabled_home_catalog_keys",
            "layout_settings" to "custom_catalog_titles",
            "layout_settings" to "last_non_off_discover_location"
        )
        kept.forEach { (feature, key) ->
            harness.edit(1, feature) { this[stringPreferencesKey(key)] = "source $key" }
            harness.edit(2, feature) { this[stringPreferencesKey(key)] = "target $key" }
        }
        harness.edit(1, "player_settings") { this[stringPreferencesKey("subtitle_size")] = "LARGE" }
        harness.edit(2, "player_settings") { this[stringPreferencesKey("stale_synced_value")] = "gone" }

        val exported = harness.service.exportLocalSettings(1)
        kept.forEach { (feature, key) ->
            assertFalse("$feature/$key was exported", key in exported.getValue(feature).jsonObject)
        }

        harness.service.importLocalSettings(2, exported)

        val player = harness.read(2, "player_settings")
        assertEquals("LARGE", player[stringPreferencesKey("subtitle_size")])
        // An import replaces the synced values, so one the source no longer has goes.
        assertNull(player[stringPreferencesKey("stale_synced_value")])
        kept.forEach { (feature, key) ->
            assertEquals("$feature/$key", "target $key", harness.read(2, feature)[stringPreferencesKey(key)])
        }
    }

    @Test
    fun unknownFeaturesTypesAndMalformedValuesAreIgnored() = runBlocking {
        val harness = Harness()
        harness.edit(2, "theme_settings") { this[stringPreferencesKey("accent")] = "BLUE" }
        val blob = buildJsonObject {
            put("feature_from_a_newer_version", buildJsonObject { put("x", typed("string", "y")) })
            put("theme_settings", buildJsonObject {
                put("accent", typed("string", "RED"))
                put("future_type", buildJsonObject { put("type", "color"); put("value", "#fff") })
                put("bad_boolean", typed("boolean", "yes"))
                put("bad_int", typed("int", "many"))
                put("no_type", buildJsonObject { put("value", "1") })
                put("not_an_object", "plain")
                put("good_int", typed("int", "7"))
            })
        }

        harness.service.importLocalSettings(2, blob)

        val theme = harness.read(2, "theme_settings").asMap().mapKeys { it.key.name }
        assertEquals(mapOf("accent" to "RED", "good_int" to 7), theme)
        assertFalse(harness.opened.any { it.second == "feature_from_a_newer_version" })
        // A feature the blob leaves out keeps what the device has.
        harness.edit(2, "tmdb_settings") { this[stringPreferencesKey("language")] = "fr" }
        harness.service.importLocalSettings(2, blob)
        assertEquals("fr", harness.read(2, "tmdb_settings")[stringPreferencesKey("language")])
    }

    @Test
    fun legacyDiscoverSwitchBecomesALocation() = runBlocking {
        val harness = Harness()
        fun legacy(enabled: Boolean) = buildJsonObject {
            put("layout_settings", buildJsonObject { put("search_discover_enabled", typed("boolean", "$enabled")) })
        }
        val discover = stringPreferencesKey("discover_location")

        // Off on the other device turns it off here.
        harness.edit(2, "layout_settings") {
            this[discover] = "IN_SIDEBAR"
            this[stringPreferencesKey("last_non_off_discover_location")] = "IN_SIDEBAR"
        }
        harness.service.importLocalSettings(2, legacy(false))
        assertEquals("OFF", harness.read(2, "layout_settings")[discover])

        // On keeps the place this device already shows it in, and otherwise restores the last one.
        harness.edit(3, "layout_settings") { this[discover] = "IN_SIDEBAR" }
        harness.service.importLocalSettings(3, legacy(true))
        assertEquals("IN_SIDEBAR", harness.read(3, "layout_settings")[discover])

        harness.service.importLocalSettings(2, legacy(true))
        assertEquals("IN_SIDEBAR", harness.read(2, "layout_settings")[discover])

        harness.service.importLocalSettings(4, legacy(true))
        assertEquals("IN_SEARCH", harness.read(4, "layout_settings")[discover])
        assertNull(harness.read(4, "layout_settings")[booleanPreferencesKey("search_discover_enabled")])

        // A well-formed new key wins over the legacy switch.
        harness.service.importLocalSettings(4, buildJsonObject {
            put("layout_settings", buildJsonObject {
                put("search_discover_enabled", typed("boolean", "false"))
                put("discover_location", typed("string", "IN_SIDEBAR"))
            })
        })
        assertEquals("IN_SIDEBAR", harness.read(4, "layout_settings")[discover])
    }

    @Test
    fun pullAppliesARemoteBlobOnlyWhenItDiffers() = runBlocking {
        val backend = FakeBackend()
        val harness = Harness(backend)
        harness.edit(1, "theme_settings") { this[stringPreferencesKey("accent")] = "BLUE" }
        harness.edit(1, "player_settings") { this[stringPreferencesKey("decoder_priority")] = "PREFER_DEVICE" }

        backend.remote = blobOf("theme_settings" to buildJsonObject { put("accent", typed("string", "BLUE")) })
        assertFalse(harness.service.pullCurrentProfileFromRemote().getOrThrow())

        backend.remote = blobOf(
            "theme_settings" to buildJsonObject { put("accent", typed("string", "GREEN")) },
            "player_settings" to buildJsonObject { put("decoder_priority", typed("string", "PREFER_APP")) }
        )
        assertTrue(harness.service.pullCurrentProfileFromRemote().getOrThrow())
        assertEquals("GREEN", harness.read(1, "theme_settings")[stringPreferencesKey("accent")])
        assertEquals("PREFER_DEVICE", harness.read(1, "player_settings")[stringPreferencesKey("decoder_priority")])

        backend.remote = null
        assertFalse(harness.service.pullCurrentProfileFromRemote().getOrThrow())
        assertEquals("GREEN", harness.read(1, "theme_settings")[stringPreferencesKey("accent")])
    }

    @Test
    fun pushSendsSyncedKeysAndAppliesWhatTheServerMerged() = runBlocking {
        val backend = FakeBackend()
        val harness = Harness(backend)
        harness.edit(1, "theme_settings") { this[stringPreferencesKey("accent")] = "BLUE" }
        harness.edit(1, "mdblist_settings") { this[stringPreferencesKey("mdblist_api_key")] = "secret" }
        backend.merged = blobOf(
            "theme_settings" to buildJsonObject {
                put("accent", typed("string", "BLUE"))
                put("clock", typed("boolean", "true"))
            }
        )

        harness.service.pushCurrentProfileToRemote().getOrThrow()

        val pushed = backend.pushed.single().getValue("features").jsonObject
        assertEquals(syncedFeatures.toSet(), pushed.keys)
        assertFalse("mdblist_api_key" in pushed.getValue("mdblist_settings").jsonObject)
        assertEquals(true, harness.read(1, "theme_settings")[booleanPreferencesKey("clock")])
        assertEquals("secret", harness.read(1, "mdblist_settings")[stringPreferencesKey("mdblist_api_key")])
    }

    private fun typed(type: String, value: String) = buildJsonObject {
        put("type", type)
        put("value", value)
    }

    private fun blobOf(vararg features: Pair<String, JsonObject>) = buildJsonObject {
        put("version", 1)
        put("features", JsonObject(features.toMap()))
    }

    private class FakeBackend : SyncBackend by mockk(relaxed = true) {
        var remote: JsonObject? = null
        var merged: JsonObject? = null
        val pushed = mutableListOf<JsonObject>()

        override suspend fun pullSettings(profileId: Int, local: suspend () -> JsonObject): Result<JsonObject?> =
            Result.success(remote)

        override suspend fun pushSettings(profileId: Int, blob: JsonObject): Result<JsonObject?> {
            pushed += blob
            return Result.success(merged)
        }
    }

    /** In-memory DataStores per profile and feature, behind a mocked [ProfileDataStoreFactory]. */
    private class Harness(backend: SyncBackend? = null) {
        private val stores = ConcurrentHashMap<Pair<Int, String>, MemoryStore>()
        val opened: Set<Pair<Int, String>> get() = stores.keys

        private val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), any()) } answers { store(firstArg(), secondArg()) }
            every { corruptedFileNames } returns ConcurrentHashMap.newKeySet()
        }
        private val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(1)
            every { profiles } returns MutableStateFlow((1..4).map { UserProfile(it, "Profile $it", "#ffffff") })
        }
        // canSync stays false: it only gates the debounced auto-push and importLocalSettings'
        // push, so a slow setup edit can't race an explicit push or pull, which use `active`.
        private val backends = mockk<SyncBackends> {
            every { active } returns backend
            every { canSync } returns false
        }

        val service = ProfileSettingsSyncService(
            profileManager, factory, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), backends
        )

        private fun store(profileId: Int, feature: String) = stores.getOrPut(profileId to feature) { MemoryStore() }

        suspend fun edit(profileId: Int, feature: String, block: MutablePreferencesScope.() -> Unit) {
            store(profileId, feature).edit { MutablePreferencesScope(it).block() }
        }

        suspend fun read(profileId: Int, feature: String): Preferences = store(profileId, feature).data.first()
    }

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    /** Lets the value table above read as assignments. */
    private class MutablePreferencesScope(private val prefs: androidx.datastore.preferences.core.MutablePreferences) {
        operator fun <T> set(key: Preferences.Key<T>, value: T) {
            prefs[key] = value
        }
    }
}
