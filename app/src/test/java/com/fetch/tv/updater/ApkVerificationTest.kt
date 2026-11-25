package com.fetch.tv.updater

import com.fetch.tv.updater.ApkArchiveVerifier.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApkVerificationTest {

    private val sha = "a".repeat(64)

    @Test
    fun `complete download with matching digest passes`() {
        assertNull(downloadVerificationError(100, 100, 100, sha, "sha256:$sha"))
    }

    @Test
    fun `EOF before content length is a failure`() {
        assertNotNull(downloadVerificationError(60, 100, null, sha, null))
    }

    @Test
    fun `size different from the release asset is a failure`() {
        assertNotNull(downloadVerificationError(60, null, 100, sha, null))
    }

    @Test
    fun `digest mismatch is a failure`() {
        assertNotNull(downloadVerificationError(100, 100, 100, sha, "sha256:${"b".repeat(64)}"))
    }

    @Test
    fun `unknown size and digest are not checked`() {
        assertNull(downloadVerificationError(100, null, null, sha, null))
        assertNull(downloadVerificationError(100, null, 0, sha, ""))
    }

    @Test
    fun `digest parsing`() {
        assertEquals(sha, normalizeSha256("sha256:${sha.uppercase()}"))
        assertEquals(sha, normalizeSha256(" $sha "))
        assertNull(normalizeSha256("sha512:$sha"))
        assertNull(normalizeSha256("sha256:abc"))
        assertNull(normalizeSha256(null))
    }

    private fun check(
        pkg: String? = "com.fetch.tv",
        version: Long = 2,
        archiveSigners: Set<String> = setOf("new"),
        archiveHistory: Set<String> = setOf("old", "new"),
        installedSigners: Set<String> = setOf("old")
    ) = ApkArchiveVerifier.check(
        archivePackage = pkg,
        archiveVersionCode = version,
        archiveSigners = archiveSigners,
        archiveSignerHistory = archiveHistory,
        installedPackage = "com.fetch.tv",
        installedVersionCode = 1,
        installedSigners = installedSigners
    )

    @Test
    fun `newer same-package update signed by a known key passes`() {
        assertNull(check())
    }

    @Test
    fun `archive checks`() {
        assertEquals(Failure.WRONG_PACKAGE, check(pkg = "com.other"))
        assertEquals(Failure.WRONG_PACKAGE, check(pkg = null))
        assertEquals(Failure.NOT_NEWER, check(version = 1))
        assertEquals(Failure.SIGNER_MISMATCH, check(archiveSigners = setOf("debug"), archiveHistory = emptySet()))
    }

    @Test
    fun `same single signer passes`() {
        assertNull(check(archiveSigners = setOf("old"), archiveHistory = emptySet()))
    }

    @Test
    fun `multiple signers must match exactly`() {
        assertNull(check(archiveSigners = setOf("a", "b"), archiveHistory = emptySet(), installedSigners = setOf("a", "b")))
        assertEquals(
            Failure.SIGNER_MISMATCH,
            check(archiveSigners = setOf("a", "b"), archiveHistory = emptySet(), installedSigners = setOf("a"))
        )
        assertEquals(
            Failure.SIGNER_MISMATCH,
            check(archiveSigners = setOf("a"), archiveHistory = emptySet(), installedSigners = setOf("a", "b"))
        )
    }

    @Test
    fun `unreadable signers are not checked`() {
        assertNull(check(archiveSigners = emptySet()))
        assertNull(check(installedSigners = emptySet()))
    }
}
