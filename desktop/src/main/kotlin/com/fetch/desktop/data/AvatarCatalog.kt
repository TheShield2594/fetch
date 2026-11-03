package com.fetch.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.SyncAccountState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The avatars a profile can pick, as the TV's `AvatarRepository` lists them: the bundled ones,
 * then the sync server's catalog (`get_avatar_catalog`) while signed in. The server's is kept in
 * `avatar-catalog.json`, so profiles with one of its avatars show it offline too.
 */
object AvatarCatalog {
    @Serializable
    private data class Stored(val id: String, val name: String, val category: String, val imageUrl: String?, val bgColor: String?)

    private val store by lazy {
        JsonFile(File(AppDirs.config, "avatar-catalog.json"), ListSerializer(Stored.serializer())) { emptyList() }
    }

    var server: List<AvatarItem> by mutableStateOf(emptyList())
        private set
    private var loaded = false
    private var lastRefreshMs = 0L

    val all: List<AvatarItem> get() = BundledAvatars.catalog + serverItems()

    private fun serverItems(): List<AvatarItem> {
        if (!loaded) {
            loaded = true
            server = runCatching { store.load() }.getOrDefault(emptyList()).map { AvatarItem(it.id, it.name, it.category, it.imageUrl, it.bgColor) }
        }
        return server
    }

    /** The picture for [avatarId] from the server's catalog, when it has one. */
    fun serverImageUrl(avatarId: String?): String? = avatarId?.let { id -> serverItems().firstOrNull { it.id == id }?.imageUrl }

    /**
     * The picture other devices can load for [profile] (a watch party member's avatar): its own
     * picture or a server catalog one, on the server's storage. Bundled avatars stay local, as on TV.
     */
    fun shareableUrl(profile: DesktopProfile, serverBaseUrl: String?): String? {
        val base = serverBaseUrl?.trimEnd('/') ?: return null
        val storage = "$base/storage/v1/object/"
        profile.avatarUrl?.takeIf { it.startsWith(storage) }?.let { return it }
        return serverImageUrl(profile.avatarId)?.takeIf { it.startsWith(storage) }
    }

    /** Reloads the server's catalog, at most every 15 minutes as on TV. */
    suspend fun refresh(account: SyncAccountState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs < 15 * 60_000L) return
        val client = account.client() ?: return
        val base = account.state.server?.baseUrl ?: return
        lastRefreshMs = now
        try {
            val body = withContext(Dispatchers.IO) { client.callRpc("get_avatar_catalog", null) }
            val items = parseAvatarCatalog(body, base)
            server = items
            loaded = true
            runCatching { store.save(items.map { Stored(it.id, it.name, it.category, it.imageUrl, it.bgColor) }) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep what was stored.
        }
    }
}
