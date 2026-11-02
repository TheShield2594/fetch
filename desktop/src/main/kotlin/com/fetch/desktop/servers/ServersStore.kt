package com.fetch.desktop.servers

import com.fetch.desktop.data.AppDirs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Keeps the signed-in [ServerAccount]s in `servers.json`. The file holds access tokens, so it is
 * written atomically and made readable by its owner only (where POSIX permissions exist).
 */
class ServersStore(private val file: File = File(AppDirs.config, "servers.json")) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true; encodeDefaults = true }
    private val serializer = ListSerializer(ServerAccount.serializer())

    /** The saved accounts; empty when the file is missing or unreadable. */
    fun load(): List<ServerAccount> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(accounts: List<ServerAccount>) {
        file.parentFile?.mkdirs()
        // Write then rename, so a crash mid-write never leaves a half-written file.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText("")
        ownerOnly(tmp.toPath())
        tmp.writeText(json.encodeToString(serializer, accounts))
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
