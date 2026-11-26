package com.fetch.tv.ui.screens.player.autosync

import androidx.annotation.VisibleForTesting
import com.fetch.tv.core.player.SubtitleCharsetDetector
import com.fetch.tv.ui.screens.player.buildSubtitleRequest
import com.fetch.tv.ui.screens.player.subtitleHttpClient
import com.fetch.tv.ui.screens.player.subtitleUnvalidatedTlsHttpClient
import com.fetch.tv.ui.screens.player.withoutCredentialHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * AutoSync-only subtitle HTTP path.
 *
 * Reuses Fetch's existing OkHttp client and connection pool, but uses OkHttp's
 * asynchronous API so cancelling an AutoSync coroutine immediately cancels the
 * underlying network call. This deliberately does not modify Fetch's shared
 * httpRequestRaw() behavior used by other features.
 */
internal object AutoSyncSubtitleHttp {
    private const val READ_BUFFER_BYTES = 32 * 1024
    private const val INITIAL_BUFFER_BYTES = 64 * 1024
    private const val TRUNCATION_SUFFIX = "\n...[truncated]"

    suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxResponseBodyBytes: Int,
        languageHint: String? = null,
    ): AutoSyncRawHttpResponse {
        // Built and sent like the other subtitle downloads, so the subtitle's own credential
        // headers stay on its host across redirects (TheShield2594/fetch#701). Accept-Encoding is
        // left to OkHttp, which negotiates transparent compression itself.
        val request = buildSubtitleRequest(
            subtitleUrl = url.toHttpUrl(),
            streamUrl = null,
            streamHeaders = emptyMap(),
            explicitHeaders = headers.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) },
        )
        return try {
            execute(subtitleHttpClient.newCall(request), maxResponseBodyBytes, languageHint)
        } catch (e: SSLException) {
            // Self-signed hosts, as executeSubtitleRequest does: rerun without the scoped headers.
            execute(
                subtitleUnvalidatedTlsHttpClient.newCall(request.withoutCredentialHeaders()),
                maxResponseBodyBytes,
                languageHint,
            )
        }
    }

    private suspend fun execute(
        call: Call,
        maxResponseBodyBytes: Int,
        languageHint: String?,
    ): AutoSyncRawHttpResponse {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (!continuation.isActive) return

                        val failure =
                            if (call.isCanceled()) {
                                CancellationException(
                                    "Cancelled AutoSync subtitle HTTP request",
                                ).also { it.initCause(error) }
                            } else {
                                error
                            }
                        continuation.resumeWith(Result.failure(failure))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }

                        try {
                            val result = response.use { current ->
                                AutoSyncRawHttpResponse(
                                    status = current.code,
                                    statusText = current.message,
                                    url = current.request.url.toString(),
                                    body = readResponseBodyLimited(
                                        body = current.body,
                                        maxBytes = maxResponseBodyBytes,
                                        languageHint = languageHint,
                                    ),
                                    headers = current.headers
                                        .toMultimap()
                                        .mapValues { (_, values) -> values.joinToString(",") }
                                        .mapKeys { (name, _) -> name.lowercase() },
                                )
                            }

                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(result))
                            }
                        } catch (error: Throwable) {
                            if (!continuation.isActive) return

                            val failure =
                                if (call.isCanceled() && error !is CancellationException) {
                                    CancellationException(
                                        "Cancelled AutoSync subtitle HTTP request",
                                    ).also { it.initCause(error) }
                                } else {
                                    error
                                }
                            continuation.resumeWith(Result.failure(failure))
                        }
                    }
                },
            )
        }
    }

    @VisibleForTesting
    internal fun readResponseBodyLimited(
        body: ResponseBody?,
        maxBytes: Int,
        languageHint: String?,
    ): String {
        if (body == null) return ""

        val limit = maxBytes.coerceAtLeast(0)
        val contentLength = body.contentLength()
        val initialCapacity =
            when {
                limit == 0 -> 0
                contentLength in 1..limit.toLong() -> contentLength.toInt()
                else -> minOf(limit, INITIAL_BUFFER_BYTES)
            }

        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var remaining = limit
        var truncated = false

        body.byteStream().use { stream ->
            while (remaining > 0) {
                val read = stream.read(
                    buffer,
                    0,
                    minOf(buffer.size, remaining),
                )
                if (read < 0) break
                if (read == 0) continue

                output.write(buffer, 0, read)
                remaining -= read
            }

            if (remaining == 0) {
                truncated = stream.read() != -1
            }
        }

        // Same decoding as the regular subtitle download: addon files (e.g. Subscene) are often
        // Windows-1252 without a charset header, and a blind UTF-8 decode turns accents into U+FFFD.
        val decoded = SubtitleCharsetDetector.decode(output.toByteArray(), languageHint = languageHint)

        return if (truncated) "$decoded$TRUNCATION_SUFFIX" else decoded
    }
}

internal data class AutoSyncRawHttpResponse(
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
    val headers: Map<String, String>,
)

