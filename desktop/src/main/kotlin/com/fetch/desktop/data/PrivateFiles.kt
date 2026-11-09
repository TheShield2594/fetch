package com.fetch.desktop.data

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes the app's own files so only this user can read them: the folders 700, the files 600
 * (sign-ins, addon URLs with keys in them, sync tokens). On a file system without POSIX
 * permissions (Windows) the user profile's ACLs are left to do that.
 *
 * Every write goes to a temporary file that is created 600 from the start, then is renamed over
 * the old one, so a crash mid-write never leaves a half-written file and the contents are never
 * readable by others, not even for a moment.
 */
object PrivateFiles {
    private val posix: Boolean by lazy { "posix" in FileSystems.getDefault().supportedFileAttributeViews() }
    private val dirPermissions = PosixFilePermissions.fromString("rwx------")
    private val filePermissions = PosixFilePermissions.fromString("rw-------")

    /**
     * Makes [dir] if missing; it and any parents made for it are 700 (a parent outside
     * [AppDirs.config], such as `~/.config` itself, keeps the umask's permissions). An existing
     * folder is made 700 too when [restrictExisting], by default when it is [AppDirs.config] or
     * inside it, which tightens one made earlier with the umask.
     */
    fun ensureDir(dir: File, restrictExisting: Boolean = isAppDir(dir)) {
        val target = dir.absoluteFile
        if (target.isDirectory) {
            if (restrictExisting) restrict(target.toPath(), dirPermissions)
            return
        }
        val missing = generateSequence(target) { it.parentFile }.takeWhile { !it.exists() }.toList()
        target.mkdirs()
        missing.forEach { created -> if (created == target || isAppDir(created)) restrict(created.toPath(), dirPermissions) }
    }

    fun writeText(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(file: File, bytes: ByteArray) {
        val target = file.absoluteFile
        val dir = target.parentFile ?: error("No folder for $file")
        ensureDir(dir)
        val tmp = if (posix) {
            Files.createTempFile(dir.toPath(), "${target.name}.", ".tmp", PosixFilePermissions.asFileAttribute(filePermissions))
        } else {
            Files.createTempFile(dir.toPath(), "${target.name}.", ".tmp")
        }
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /** Makes an existing file 600 (for files written some other way). */
    fun restrictFile(file: File) = restrict(file.toPath(), filePermissions)

    private fun isAppDir(dir: File): Boolean = runCatching {
        dir.canonicalFile.toPath().startsWith(AppDirs.config.canonicalFile.toPath())
    }.getOrDefault(false)

    private fun restrict(path: Path, permissions: Set<java.nio.file.attribute.PosixFilePermission>) {
        if (!posix) return
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
        } catch (_: IOException) {
        }
    }
}
