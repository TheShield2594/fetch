package com.fetch.desktop.sync

import com.fetch.desktop.data.AppDirs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * What the desktop app remembers about the sync server: the server, the signed-in session (null
 * when signed out) and this install's `p_origin_client_id`.
 */
@Serializable
data class SyncState(
    val server: SyncServerConfig? = null,
    val session: SyncSession? = null,
    val originClientId: String? = null,
)

/**
 * Keeps [SyncState] in `sync.json`. The file holds tokens, so it is written atomically and made
 * readable by its owner only (where the file system supports POSIX permissions).
 */
class SyncStore(private val file: File = File(AppDirs.config, "sync.json")) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    /** The saved state, or an empty one when the file is missing or unreadable. */
    fun load(): SyncState {
        if (!file.exists()) return SyncState()
        return try {
            json.decodeFromString<SyncState>(file.readText())
        } catch (_: Exception) {
            SyncState()
        }
    }

    fun save(state: SyncState) {
        file.parentFile?.mkdirs()
        // Write then rename, so a crash mid-write never leaves a half-written file.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(SyncState.serializer(), state))
        ownerOnly(tmp.toPath())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        ownerOnly(file.toPath())
    }

    /** Applies [change] to the saved state and saves the result. */
    @Synchronized
    fun update(change: (SyncState) -> SyncState): SyncState = change(load()).also(::save)

    /** The saved origin client id, creating and saving one the first time. */
    fun originClientId(): String =
        load().originClientId ?: update { it.copy(originClientId = it.originClientId ?: SyncClient.newOriginClientId()) }.originClientId!!

    private fun ownerOnly(path: Path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system (Windows): rely on the user profile's ACLs.
        } catch (_: IOException) {
        }
    }
}
