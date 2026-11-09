package com.fetch.desktop.mdblist

import com.fetch.desktop.data.PrivateFiles
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps [MdbListAccountFile] in the profile's `mdblist.json`. It holds an OAuth token or API key,
 * so, like `tracking.json`, it is written atomically and made readable by its owner only (where
 * the file system has POSIX permissions).
 */
class MdbListAccountStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    fun load(): MdbListAccountFile {
        if (!file.exists()) return MdbListAccountFile()
        return runCatching { json.decodeFromString(MdbListAccountFile.serializer(), file.readText()) }.getOrDefault(MdbListAccountFile())
    }

    fun save(value: MdbListAccountFile) {
        // Owner-only (600), written then renamed so a crash never leaves half a file.
        PrivateFiles.writeText(file, json.encodeToString(MdbListAccountFile.serializer(), value))
    }
}
