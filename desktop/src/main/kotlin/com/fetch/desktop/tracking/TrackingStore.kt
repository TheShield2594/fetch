package com.fetch.desktop.tracking

import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.PrivateFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One signed-in tracking account. */
@Serializable
data class TrackingAccount(val tokens: TrackingTokens, val userName: String? = null)

/** The signed-in Trakt and Simkl accounts; null means signed out. */
@Serializable
data class TrackingAccounts(val trakt: TrackingAccount? = null, val simkl: TrackingAccount? = null)

/**
 * Keeps [TrackingAccounts] in `tracking.json`. The file holds OAuth tokens, so it is written
 * atomically and made readable by its owner only (where the file system supports POSIX permissions).
 */
class TrackingStore(private val file: File = File(AppDirs.config, "tracking.json")) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    /** The saved accounts, or none when the file is missing or unreadable. */
    fun load(): TrackingAccounts {
        if (!file.exists()) return TrackingAccounts()
        return try {
            json.decodeFromString<TrackingAccounts>(file.readText())
        } catch (_: Exception) {
            TrackingAccounts()
        }
    }

    fun save(accounts: TrackingAccounts) {
        // Owner-only (600), written then renamed so a crash never leaves half a file.
        PrivateFiles.writeText(file, json.encodeToString(TrackingAccounts.serializer(), accounts))
    }
}
