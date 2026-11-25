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
            archiveSigners = archiveSignerDigests(archive),
            installedPackage = installed.packageName,
            installedVersionCode = PackageInfoCompat.getLongVersionCode(installed),
            installedSigners = currentSignerDigests(installed)
        )
    }

    /**
     * The decision on its own, for tests. [archiveSigners] holds the archive's current
     * signers plus any past certificates in its rotation history; every one of the
     * installed app's current signers must be among them. Signers that couldn't be read
     * (empty) are not checked.
     */
    internal fun check(
        archivePackage: String?,
        archiveVersionCode: Long,
        archiveSigners: Set<String>,
        installedPackage: String,
        installedVersionCode: Long,
        installedSigners: Set<String>
    ): Failure? {
        if (archivePackage != installedPackage) return Failure.WRONG_PACKAGE
        if (archiveVersionCode <= installedVersionCode) return Failure.NOT_NEWER
        if (archiveSigners.isNotEmpty() && installedSigners.isNotEmpty() &&
            !archiveSigners.containsAll(installedSigners)
        ) {
            return Failure.SIGNER_MISMATCH
        }
        return null
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

    @Suppress("DEPRECATION")
    private fun archiveSignerDigests(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            val signers = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                // Current certificate plus its rotation history, so a key rotated by an
                // update is still accepted.
                signing.signingCertificateHistory
            }
            return signers.orEmpty().digests()
        }
        return info.signatures.orEmpty().digests()
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
