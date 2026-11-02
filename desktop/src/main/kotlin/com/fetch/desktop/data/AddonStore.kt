package com.fetch.desktop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where the desktop app keeps its files: `$XDG_CONFIG_HOME/fetch`, or `~/.config/fetch`. */
object AppDirs {
    val config: File by lazy {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        val base = xdg?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        File(base, "fetch")
    }
}

@Serializable
private data class AddonStoreFile(val addons: List<String> = emptyList())

/**
 * The installed addons, as an ordered list of base URLs in `addons.json`. A first run starts
 * with the same defaults as the TV app.
 */
class AddonStore(private val file: File = File(AppDirs.config, "addons.json")) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): List<String> {
        if (!file.exists()) return DEFAULT_ADDONS
        return try {
            json.decodeFromString<AddonStoreFile>(file.readText()).addons
        } catch (_: Exception) {
            DEFAULT_ADDONS
        }
    }

    fun save(urls: List<String>) {
        file.parentFile?.mkdirs()
        // Write then rename, so a crash mid-write never leaves a half-written list.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(AddonStoreFile.serializer(), AddonStoreFile(urls.distinct())))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        val DEFAULT_ADDONS = listOf(
            "https://v3-cinemeta.strem.io",
            "https://opensubtitles-v3.strem.io"
        )
    }
}
