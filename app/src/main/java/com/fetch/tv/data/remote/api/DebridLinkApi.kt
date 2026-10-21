package com.fetch.tv.data.remote.api

import com.fetch.tv.data.remote.dto.DebridLinkEnvelopeDto
import com.fetch.tv.data.remote.dto.DebridLinkTorrentDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Debrid-Link API v2 (https://debrid-link.com/api_doc), relative to https://debrid-link.com/api/v2/. */
interface DebridLinkApi {
    @GET("account/infos")
    suspend fun getAccount(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    /** Given a bare info hash, Debrid-Link only adds the torrent when it already has it cached. */
    @FormUrlEncoded
    @POST("seedbox/add")
    suspend fun addTorrent(
        @Header("Authorization") authorization: String,
        @Field("url") url: String
    ): Response<DebridLinkEnvelopeDto<DebridLinkTorrentDto>>

    @GET("seedbox/list")
    suspend fun listTorrents(
        @Header("Authorization") authorization: String,
        @Query("ids") ids: String
    ): Response<DebridLinkEnvelopeDto<List<DebridLinkTorrentDto>>>

    @DELETE("seedbox/{ids}/remove")
    suspend fun removeTorrents(
        @Header("Authorization") authorization: String,
        @Path("ids") ids: String
    ): Response<ResponseBody>
}
