package com.fetch.tv.updater

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/**
 * Checks a downloaded APK before it goes to the system installer, so a bad file gets a
 * Retry in the banner instead of the installer's dead-end "problem parsing the package"
 * (TheShield2594/fetch#664).
 */
object ApkArchiveVerifier {

    enum class Failure {
        /** Android couldn't read the file as a package (corrupt or truncated). */
        UNPARSEABLE,
        WRONG_PACKAGE,
        NOT_NEWER,
        SIGNER_MISMATCH
    }

    /** Returns why [apkFile] can't be installed over this app, or null when it can. */
    fun verify(context: Context, apkFile: File): Failure? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apkFile) ?: return Failure.UNPARSEABLE
        val installed = runCatching { installedInfo(pm, context.packageName) }.getOrNull()
            ?: return null // Can't read ourselves; leave the decision to the installer.

        return check(
            archivePackage = archive.packageName,
            archiveVersionCode = PackageInfoCompat.getLongVersionCode(archive),
            archiveSigners = currentSignerDigests(archive),
            archiveSignerHistory = signerHistoryDigests(archive),
            installedPackage = installed.packageName,
            installedVersionCode = PackageInfoCompat.getLongVersionCode(installed),
            installedSigners = currentSignerDigests(installed)
        )
    }

    /**
     * The decision on its own, for tests. [archiveSigners] and [installedSigners] are the
     * current signers; [archiveSignerHistory] the archive's past certificates when it has a
     * single signer that rotated. Signers that couldn't be read (empty) are not checked.
     */
    internal fun check(
        archivePackage: String?,
        archiveVersionCode: Long,
        archiveSigners: Set<String>,
        archiveSignerHistory: Set<String> = emptySet(),
        installedPackage: String,
        installedVersionCode: Long,
        installedSigners: Set<String>
    ): Failure? {
        if (archivePackage != installedPackage) return Failure.WRONG_PACKAGE
        if (archiveVersionCode <= installedVersionCode) return Failure.NOT_NEWER
        if (archiveSigners.isEmpty() || installedSigners.isEmpty()) return null
        return if (signersCompatible(archiveSigners, archiveSignerHistory, installedSigners)) {
            null
        } else {
            Failure.SIGNER_MISMATCH
        }
    }

    private fun signersCompatible(
        archiveSigners: Set<String>,
        archiveSignerHistory: Set<String>,
        installedSigners: Set<String>
    ): Boolean {
        // Several simultaneous signers are one identity to Android: the sets must match.
        if (archiveSigners.size > 1 || installedSigners.size > 1) return archiveSigners == installedSigners
        // One signer: the installed one, or one the archive's key rotated away from.
        return installedSigners.single() in archiveSigners + archiveSignerHistory
    }

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun archiveInfo(pm: PackageManager, apkFile: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return runCatching { pm.getPackageArchiveInfo(apkFile.absolutePath, flags) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return pm.getPackageInfo(packageName, flags)
    }

    /**
     * Past certificates of a single-signer package whose key rotated, so an update signed
     * with the new key is still accepted. Empty for multiple signers and before API 28.
     */
    private fun signerHistoryDigests(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val signing = info.signingInfo ?: return emptySet()
        if (signing.hasMultipleSigners()) return emptySet()
        return signing.signingCertificateHistory.orEmpty().digests()
    }

    @Suppress("DEPRECATION")
    private fun currentSignerDigests(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.signingInfo?.apkContentsSigners.orEmpty().digests()
        }
        return info.signatures.orEmpty().digests()
    }

    private fun Array<out Signature?>.digests(): Set<String> =
        mapNotNull { it?.toCharsString() }.toSet()
}
