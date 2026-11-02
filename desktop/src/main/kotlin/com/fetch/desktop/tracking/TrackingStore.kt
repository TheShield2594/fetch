package com.fetch.desktop.tracking

import com.fetch.desktop.data.AppDirs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

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
        file.parentFile?.mkdirs()
        // Write then rename, so a crash mid-write never leaves a half-written file.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(TrackingAccounts.serializer(), accounts))
        ownerOnly(tmp.toPath())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        ownerOnly(file.toPath())
    }

    private fun ownerOnly(path: Path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system (Windows): rely on the user profile's ACLs.
        } catch (_: IOException) {
        }
    }
}
