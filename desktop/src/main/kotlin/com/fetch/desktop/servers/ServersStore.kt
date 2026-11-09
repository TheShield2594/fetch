package com.fetch.desktop.servers

import com.fetch.desktop.data.AppDirs
import com.fetch.desktop.data.PrivateFiles
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

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
        // Owner-only (600), written then renamed so a crash never leaves half a file.
        PrivateFiles.writeText(file, json.encodeToString(serializer, accounts))
    }
}
