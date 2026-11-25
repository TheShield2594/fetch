package com.fetch.tv.updater

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class ApkDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    /**
     * Downloads [url] to [destinationFile]. [onProgress] is called when the whole percentage
     * changes (only once when the size is unknown), not for every buffer read.
     * Cancelling the calling coroutine stops the download and deletes the partial file.
     *
     * The download fails, and the file is deleted, when fewer or more bytes arrive than the
     * server's Content-Length or [expectedSizeBytes], or when the file's SHA-256 doesn't match
     * [expectedSha256] (TheShield2594/fetch#664). Either check is skipped when its value is null.
     */
    suspend fun download(
        url: String,
        destinationFile: File,
        expectedSizeBytes: Long? = null,
        expectedSha256: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Result<File> {
        return runCatching {
            destinationFile.parentFile?.mkdirs()
            if (destinationFile.exists()) destinationFile.delete()

            val request = Request.Builder()
                .url(url)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed: HTTP ${response.code}")
                }

                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var lastPercent = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            val percent = downloadPercent(downloaded, total)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(downloaded, total)
                            }
                        }
                        output.flush()
                    }
                }

                downloadVerificationError(
                    downloadedBytes = downloaded,
                    contentLength = total,
                    expectedSizeBytes = expectedSizeBytes,
                    actualSha256 = digest.digest().toHexString(),
                    expectedSha256 = expectedSha256
                )?.let { reason -> throw ApkVerificationException(reason) }
            }

            destinationFile
        }.onFailure { error ->
            destinationFile.delete()
            if (error is CancellationException) throw error
        }
    }
}

/** Whole percent of [total] in [downloaded], or 0 when the size is unknown. */
internal fun downloadPercent(downloaded: Long, total: Long?): Int =
    if (total == null || total <= 0) 0 else ((downloaded * 100) / total).toInt().coerceIn(0, 100)

/** A downloaded APK that is incomplete, corrupt, or not an update to this app. */
class ApkVerificationException(message: String) : IllegalStateException(message)

/**
 * Why a finished download can't be trusted, or null when it can. An EOF before
 * [contentLength] is a truncated transfer, not success. Sizes and digests that are null
 * (unknown) are not checked.
 */
internal fun downloadVerificationError(
    downloadedBytes: Long,
    contentLength: Long?,
    expectedSizeBytes: Long?,
    actualSha256: String,
    expectedSha256: String?
): String? {
    if (contentLength != null && contentLength > 0 && downloadedBytes != contentLength) {
        return "Received $downloadedBytes of $contentLength bytes"
    }
    if (expectedSizeBytes != null && expectedSizeBytes > 0 && downloadedBytes != expectedSizeBytes) {
        return "Received $downloadedBytes bytes, release asset is $expectedSizeBytes"
    }
    val expected = normalizeSha256(expectedSha256)
    if (expected != null && !expected.equals(actualSha256, ignoreCase = true)) {
        return "SHA-256 mismatch"
    }
    return null
}

/**
 * The hex digest from a GitHub asset `digest` ("sha256:<hex>") or a bare hex string, or null
 * when it is missing or not a SHA-256.
 */
internal fun normalizeSha256(digest: String?): String? {
    val value = digest?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val hex = if (value.contains(':')) {
        val (algorithm, rest) = value.split(':', limit = 2)
        if (!algorithm.equals("sha256", ignoreCase = true)) return null
        rest
    } else {
        value
    }
    return hex.lowercase().takeIf { SHA256_HEX.matches(it) }
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
