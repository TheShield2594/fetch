package com.fetch.tv.core.image

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

private const val BROWSER_IMAGE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
private const val BROWSER_IMAGE_ACCEPT =
    "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

/**
 * Gives image requests the headers a browser would send, so hosts that reject hotlinked images
 * (no Referer, a library User-Agent, no image Accept) still serve posters. Headers a caller
 * already set are kept.
 */
internal class BrowserImageRequestHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().withBrowserImageRequestHeaders())
}

internal fun Request.withBrowserImageRequestHeaders(): Request {
    if (url.scheme != "http" && url.scheme != "https") return this
    val builder = newBuilder()
    if (header("User-Agent").isNullOrBlank()) builder.header("User-Agent", BROWSER_IMAGE_USER_AGENT)
    if (header("Accept").isNullOrBlank()) builder.header("Accept", BROWSER_IMAGE_ACCEPT)
    if (header("Referer").isNullOrBlank()) {
        // Origin only: the image path itself is never sent as a referrer.
        val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        builder.header("Referer", origin.toString())
    }
    return builder.build()
}
