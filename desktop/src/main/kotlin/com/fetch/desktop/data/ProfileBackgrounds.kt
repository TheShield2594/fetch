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

/** One profile background a profile can pick, with its picture once downloaded. */
data class ProfileBackgroundItem(val id: String, val name: String, val assetVersion: Int, val imageFile: File?)

/**
 * The TV's profile backgrounds (`ProfileBackgroundRepository`): the sync server's catalog
 * (`get_member_profile_background_catalog`), with each picture downloaded once from the private
 * `membership-profile-backgrounds` bucket. The list is kept in `profile-backgrounds.json` and the
 * pictures in `profile-backgrounds/`, so the profile picker shows them offline and before sign-in.
 */
object ProfileBackgrounds {
    const val BUCKET = "membership-profile-backgrounds"

    @Serializable
    private data class Stored(val id: String, val name: String, val storagePath: String, val assetVersion: Int)

    private val store by lazy {
        JsonFile(File(AppDirs.config, "profile-backgrounds.json"), ListSerializer(Stored.serializer())) { emptyList() }
    }
    private val imageDir: File get() = File(AppDirs.config, "profile-backgrounds")

    var items: List<ProfileBackgroundItem> by mutableStateOf(emptyList())
        private set
    private var stored: List<Stored> = emptyList()
    private var loaded = false
    private var lastRefreshMs = 0L

    /** The catalog: what the server last listed, with the pictures that are on disk. */
    val all: List<ProfileBackgroundItem>
        get() {
            if (!loaded) {
                loaded = true
                stored = runCatching { store.load() }.getOrDefault(emptyList())
                items = stored.map { it.toItem() }
            }
            return items
        }

    /** The picture to show behind [profile] (for Coil): its own picture's URL, or a catalog file. */
    fun imageFor(profile: DesktopProfile): Any? {
        profile.profileBackgroundUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val id = profile.profileBackgroundId ?: return null
        return all.firstOrNull { it.id == id }?.imageFile
    }

    /** Reloads the server's catalog and downloads missing pictures, at most every 15 minutes. */
    suspend fun refresh(account: SyncAccountState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs < 15 * 60_000L) return
        val client = account.client() ?: return
        lastRefreshMs = now
        all
        try {
            val remote = client.profileBackgroundCatalog()
            stored = remote.map { Stored(it.id, it.displayName, it.storagePath, it.assetVersion) }
            runCatching { store.save(stored) }
            items = stored.map { it.toItem() }
            for (item in stored) {
                if (file(item).isFile) continue
                runCatching {
                    val bytes = client.downloadAuthenticated(BUCKET, item.storagePath)
                    withContext(Dispatchers.IO) {
                        imageDir.mkdirs()
                        val tmp = File(imageDir, ".${file(item).name}.tmp")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(file(item))) {
                            tmp.copyTo(file(item), overwrite = true)
                            tmp.delete()
                        }
                    }
                }.onFailure { if (it is CancellationException) throw it }
                items = stored.map { it.toItem() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep what was stored.
        }
    }

    private fun file(item: Stored) = File(imageDir, "${safeName(item.id)}-v${item.assetVersion}.png")

    private fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun Stored.toItem() = ProfileBackgroundItem(id, name, assetVersion, file(this).takeIf { it.isFile && it.length() > 0 })
}
