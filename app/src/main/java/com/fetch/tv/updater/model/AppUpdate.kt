package com.fetch.tv.updater.model

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
    /** Lowercase hex SHA-256 of the asset, when the release publishes one. */
    val assetSha256: String? = null
)
