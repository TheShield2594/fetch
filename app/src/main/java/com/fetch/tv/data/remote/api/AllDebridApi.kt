package com.fetch.tv.data.remote.api

import com.fetch.tv.data.remote.dto.AllDebridEnvelopeDto
import com.fetch.tv.data.remote.dto.AllDebridMagnetFilesDto
import com.fetch.tv.data.remote.dto.AllDebridMagnetUploadDto
import com.fetch.tv.data.remote.dto.AllDebridPinCheckDto
import com.fetch.tv.data.remote.dto.AllDebridPinDto
import com.fetch.tv.data.remote.dto.AllDebridUnlockDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** AllDebrid API v4 (https://docs.alldebrid.com), relative to https://api.alldebrid.com/. */
interface AllDebridApi {
    @GET("v4/user")
    suspend fun getUser(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    @GET("v4.1/pin/get")
    suspend fun getPin(): Response<AllDebridEnvelopeDto<AllDebridPinDto>>

    @FormUrlEncoded
    @POST("v4/pin/check")
    suspend fun checkPin(
        @Field("check") check: String,
        @Field("pin") pin: String
    ): Response<AllDebridEnvelopeDto<AllDebridPinCheckDto>>

    @FormUrlEncoded
    @POST("v4/magnet/upload")
    suspend fun uploadMagnet(
        @Header("Authorization") authorization: String,
        @Field("magnets[]") magnet: String
    ): Response<AllDebridEnvelopeDto<AllDebridMagnetUploadDto>>

    @FormUrlEncoded
    @POST("v4/magnet/files")
    suspend fun getMagnetFiles(
        @Header("Authorization") authorization: String,
        @Field("id[]") id: Long
    ): Response<AllDebridEnvelopeDto<AllDebridMagnetFilesDto>>

    @FormUrlEncoded
    @POST("v4/link/unlock")
    suspend fun unlockLink(
        @Header("Authorization") authorization: String,
        @Field("link") link: String
    ): Response<AllDebridEnvelopeDto<AllDebridUnlockDto>>

    @FormUrlEncoded
    @POST("v4/magnet/delete")
    suspend fun deleteMagnet(
        @Header("Authorization") authorization: String,
        @Field("id") id: Long
    ): Response<ResponseBody>
}
