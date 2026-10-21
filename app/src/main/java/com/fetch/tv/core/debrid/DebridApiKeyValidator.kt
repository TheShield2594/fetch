package com.fetch.tv.core.debrid

import com.fetch.tv.data.remote.api.AllDebridApi
import com.fetch.tv.data.remote.api.DebridLinkApi
import com.fetch.tv.data.remote.api.PremiumizeApi
import com.fetch.tv.data.remote.api.RealDebridApi
import com.fetch.tv.data.remote.api.TorboxApi
import okhttp3.ResponseBody
import retrofit2.Response
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Checks a debrid API key against the provider before it is saved. */
class DebridApiKeyValidator @Inject constructor(
    private val torboxApi: TorboxApi,
    private val premiumizeApi: PremiumizeApi,
    private val realDebridApi: RealDebridApi,
    private val allDebridApi: AllDebridApi,
    private val debridLinkApi: DebridLinkApi
) {
    suspend fun isValid(providerId: String, apiKey: String): Boolean {
        return try {
            when (DebridProviders.byId(providerId)?.id) {
                DebridProviders.TORBOX_ID -> torboxApi.getUser("Bearer $apiKey").succeeded()
                DebridProviders.REAL_DEBRID_ID -> realDebridApi.getUser("Bearer $apiKey").succeeded()
                // AllDebrid and Debrid-Link can answer 200 with an error status for a bad key.
                DebridProviders.ALL_DEBRID_ID -> allDebridApi.getUser("Bearer $apiKey").succeeded { body ->
                    body.contains("\"status\":\"success\"")
                }
                DebridProviders.DEBRID_LINK_ID -> debridLinkApi.getAccount("Bearer $apiKey").succeeded { body ->
                    body.contains("\"success\":true")
                }
                DebridProviders.PREMIUMIZE_ID -> {
                    val response = premiumizeApi.accountInfo("Bearer $apiKey")
                    response.errorBody()?.close()
                    response.isSuccessful && !response.body()?.status.equals("error", ignoreCase = true)
                }
                else -> false
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
    }

    private fun Response<ResponseBody>.succeeded(bodyCheck: ((String) -> Boolean)? = null): Boolean {
        errorBody()?.close()
        val body = body()
        if (bodyCheck == null) {
            body?.close()
            return isSuccessful
        }
        val text = body?.use { it.string() }.orEmpty().filterNot { it.isWhitespace() }
        return isSuccessful && bodyCheck(text)
    }
}
