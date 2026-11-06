package com.fetch.tv.updater

import java.io.File

/**
 * Downloaded update APKs live in `cacheDir/updates`, named after the release asset
 * (`fetch-v1.5.0-arm64-v8a.apk`), so each update adds a new 50–100 MB file. Nothing else
 * references them once the in-memory download path is gone, so they are removed on start and
 * before each new download.
 */
object UpdateCacheCleaner {
    const val DIRECTORY = "updates"

    /** Deletes every file in [dir] except [keep]. Returns how many were deleted. */
    fun clean(dir: File, keep: File? = null): Int {
        val keepPath = keep?.absoluteFile
        val files = dir.listFiles() ?: return 0
        return files.count { file ->
            file.absoluteFile != keepPath && file.deleteRecursively()
        }
    }
}
