package com.fetch.desktop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where the desktop app keeps its files: `$XDG_CONFIG_HOME/fetch`, or `~/.config/fetch`. */
object AppDirs {
    val config: File by lazy {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        val base = xdg?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        File(base, "fetch")
    }
}

@Serializable
private data class AddonStoreFile(
    val addons: List<String> = emptyList(),
    /** Installed addons switched off (the TV's per-addon enable switch), by URL as in [addons]. */
    val disabled: List<String> = emptyList()
)

/**
 * The installed addons, as an ordered list of base URLs in `addons.json`, and the ones switched
 * off. A first run starts with the same defaults as the TV app.
 */
class AddonStore(private val file: File = File(AppDirs.config, "addons.json")) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun read(): AddonStoreFile? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<AddonStoreFile>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun load(): List<String> = read()?.addons ?: DEFAULT_ADDONS

    /** The installed addons that are switched off; always a subset of [load]. */
    fun loadDisabled(): Set<String> {
        val stored = read() ?: return emptySet()
        return stored.disabled.filter { it in stored.addons }.toSet()
    }

    /** Saves the list; [disabled] keeps its entries that are in [urls]. */
    fun save(urls: List<String>, disabled: Set<String> = loadDisabled()) {
        val list = urls.distinct()
        // Owner-only, written then renamed: addon URLs can carry API keys.
        PrivateFiles.writeText(file, json.encodeToString(AddonStoreFile.serializer(), AddonStoreFile(list, list.filter { it in disabled })))
    }

    companion object {
        val DEFAULT_ADDONS = listOf(
            "https://v3-cinemeta.strem.io",
            "https://opensubtitles-v3.strem.io"
        )
    }
}
