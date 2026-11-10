package com.fetch.tv.updater

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
     */
    suspend fun download(
        url: String,
        destinationFile: File,
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

                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastPercent = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
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
