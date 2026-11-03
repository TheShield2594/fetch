package com.fetch.desktop.collections

import com.fetch.tv.domain.model.Collection
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A profile's collections, kept in `collections.json` in the TV's JSON format. */
class CollectionsStore(private val file: File) {

    fun load(): List<Collection> =
        if (!file.exists()) emptyList() else runCatching { CollectionsCodec.parse(file.readText()) }.getOrDefault(emptyList())

    fun save(collections: List<Collection>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(CollectionsCodec.encode(collections))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun inProfile(dir: File) = CollectionsStore(File(dir, "collections.json"))
    }
}

/**
 * How collections sync, as the TV's `CollectionSyncService` does it: the server keeps one
 * `collections_json` per profile, a pull replaces the local collections with it, and a local
 * change pushes the whole list. An empty server copy never wipes collections made here.
 */
object CollectionsSyncMerge {

    data class Result(
        /** What this profile should hold now. */
        val local: List<Collection>,
        /** Whether [local] replaced what was here. */
        val changed: Boolean,
        /** Whether [local] should go up to the server. */
        val push: Boolean
    )

    /**
     * [remote] is null when the server has no row for the profile yet. When the server has none
     * (or an empty list) and this profile has some, they're kept and pushed, so the TV gets them.
     */
    fun merge(local: List<Collection>, remote: List<Collection>?): Result = when {
        remote == null || remote.isEmpty() ->
            Result(local, changed = false, push = local.isNotEmpty())
        remote == local -> Result(local, changed = false, push = false)
        else -> Result(remote, changed = true, push = false)
    }
}
