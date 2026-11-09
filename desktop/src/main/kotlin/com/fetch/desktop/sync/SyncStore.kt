package com.fetch.desktop.sync

import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.PrivateFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the desktop app remembers about the sync server: the server, the signed-in session (null
 * when signed out) and this install's `p_origin_client_id`.
 */
@Serializable
data class SyncState(
    val server: SyncServerConfig? = null,
    val session: SyncSession? = null,
    val originClientId: String? = null,
    /** The server's discovery said `watch_party` at sign-in; null for sign-ins from before it was kept. */
    val watchParty: Boolean? = null,
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
        // Owner-only (600), written then renamed so a crash never leaves half a file.
        PrivateFiles.writeText(file, json.encodeToString(SyncState.serializer(), state))
    }

    /** Applies [change] to the saved state and saves the result. */
    @Synchronized
    fun update(change: (SyncState) -> SyncState): SyncState = change(load()).also(::save)

    /** The saved origin client id, creating and saving one the first time. */
    fun originClientId(): String =
        load().originClientId ?: update { it.copy(originClientId = it.originClientId ?: SyncClient.newOriginClientId()) }.originClientId!!
}
