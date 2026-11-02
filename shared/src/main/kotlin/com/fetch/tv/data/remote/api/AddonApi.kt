package com.fetch.tv.data.remote.api

import com.fetch.tv.data.remote.dto.AddonManifestDto
import com.fetch.tv.data.remote.dto.CatalogResponseDto
import com.fetch.tv.data.remote.dto.MetaResponseDto
import com.fetch.tv.data.remote.dto.StreamResponseDto
import com.fetch.tv.data.remote.dto.SubtitleResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

interface AddonApi {

    @GET
    suspend fun getManifest(@Url manifestUrl: String): Response<AddonManifestDto>

    @GET
    suspend fun getCatalog(@Url catalogUrl: String): Response<CatalogResponseDto>

    /** Reads only the HTTP cache; a 504 means nothing is cached for [catalogUrl]. */
    @GET
    suspend fun getCachedCatalog(
        @Url catalogUrl: String,
        @Header("Cache-Control") cacheControl: String
    ): Response<CatalogResponseDto>

    @GET
    suspend fun getMeta(@Url metaUrl: String): Response<MetaResponseDto>

    @GET
    suspend fun getStreams(@Url streamUrl: String): Response<StreamResponseDto>

    @GET
    suspend fun getSubtitles(@Url subtitleUrl: String): Response<SubtitleResponseDto>
}
