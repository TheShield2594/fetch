package com.fetch.desktop.mdblist

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

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
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(MdbListAccountFile.serializer(), value))
        ownerOnly(tmp.toPath())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        ownerOnly(file.toPath())
    }

    private fun ownerOnly(path: Path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
        } catch (_: IOException) {
        }
    }
}
