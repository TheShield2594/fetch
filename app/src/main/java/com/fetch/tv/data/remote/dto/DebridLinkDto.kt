package com.fetch.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Every Debrid-Link v2 response: `success` with `value`, or an `error` code such as `badToken`. */
@JsonClass(generateAdapter = true)
data class DebridLinkEnvelopeDto<T>(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "value") val value: T? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class DebridLinkTorrentDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "hashString") val hashString: String? = null,
    @Json(name = "totalSize") val totalSize: Long? = null,
    @Json(name = "downloadPercent") val downloadPercent: Double? = null,
    @Json(name = "isZip") val isZip: Boolean? = null,
    @Json(name = "files") val files: List<DebridLinkTorrentFileDto>? = null
)

@JsonClass(generateAdapter = true)
data class DebridLinkTorrentFileDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "downloadUrl") val downloadUrl: String? = null,
    @Json(name = "downloadPercent") val downloadPercent: Double? = null
) {
    fun displayName(): String = name.orEmpty().substringAfterLast('/')

    val isReady: Boolean
        get() = (downloadPercent ?: 0.0) >= 100.0 && !downloadUrl.isNullOrBlank()
}
