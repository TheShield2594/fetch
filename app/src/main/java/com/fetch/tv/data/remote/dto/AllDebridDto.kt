package com.fetch.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Every AllDebrid v4 response: `status` is "success" with `data`, or "error" with `error`. */
@JsonClass(generateAdapter = true)
data class AllDebridEnvelopeDto<T>(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: T? = null,
    @Json(name = "error") val error: AllDebridErrorDto? = null
) {
    val isSuccess: Boolean
        get() = status.equals("success", ignoreCase = true)
}

@JsonClass(generateAdapter = true)
data class AllDebridErrorDto(
    @Json(name = "code") val code: String? = null,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridPinDto(
    @Json(name = "pin") val pin: String? = null,
    @Json(name = "check") val check: String? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    @Json(name = "user_url") val userUrl: String? = null,
    @Json(name = "base_url") val baseUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridPinCheckDto(
    @Json(name = "activated") val activated: Boolean? = null,
    @Json(name = "apikey") val apiKey: String? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridMagnetUploadDto(
    @Json(name = "magnets") val magnets: List<AllDebridUploadedMagnetDto>? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridUploadedMagnetDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "ready") val ready: Boolean? = null,
    @Json(name = "error") val error: AllDebridErrorDto? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridMagnetFilesDto(
    @Json(name = "magnets") val magnets: List<AllDebridMagnetFilesEntryDto>? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridMagnetFilesEntryDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "files") val files: List<AllDebridFileNodeDto>? = null,
    @Json(name = "error") val error: AllDebridErrorDto? = null
)

/** A folder (`e` holds its children) or a file (`s` size, `l` link) in a magnet's file tree. */
@JsonClass(generateAdapter = true)
data class AllDebridFileNodeDto(
    @Json(name = "n") val name: String? = null,
    @Json(name = "s") val size: Long? = null,
    @Json(name = "l") val link: String? = null,
    @Json(name = "e") val entries: List<AllDebridFileNodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridUnlockDto(
    @Json(name = "link") val link: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "filesize") val filesize: Long? = null,
    @Json(name = "delayed") val delayed: Long? = null
)

/** A file from a magnet's tree with its folder path, e.g. `Show S01/Show.S01E02.mkv`. */
data class AllDebridMagnetFile(
    val path: String,
    val size: Long?,
    val link: String
) {
    fun displayName(): String = path.substringAfterLast('/')
}

fun List<AllDebridFileNodeDto>.flattenAllDebridFiles(parent: String = ""): List<AllDebridMagnetFile> =
    flatMap { node ->
        val name = node.name.orEmpty()
        val path = if (parent.isEmpty()) name else "$parent/$name"
        val children = node.entries
        val link = node.link
        when {
            children != null -> children.flattenAllDebridFiles(path)
            !link.isNullOrBlank() -> listOf(AllDebridMagnetFile(path = path, size = node.size, link = link))
            else -> emptyList()
        }
    }
