package com.fetch.tv.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.fetch.tv.BuildConfig
import java.io.File

object ApkInstaller {

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Opens the screen where the user lets Fetch install packages. Several Android TV and OEM
     * builds don't ship the per-app "unknown sources" screen, so this falls back to the
     * security settings and then to Fetch's app details. Returns false if none could open.
     */
    fun openUnknownSourcesSettings(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
        return candidates.any { intent ->
            tryStart(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Hands [apkFile] to the system installer. Returns false if no installer could open it. */
    fun launchInstall(context: Context, apkFile: File): Boolean {
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri = runCatching { FileProvider.getUriForFile(context, authority, apkFile) }
            .getOrElse { return false }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return tryStart(context, intent)
    }

    // ActivityNotFoundException, or a SecurityException from an OEM settings activity that
    // isn't exported to third-party apps.
    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess
}
