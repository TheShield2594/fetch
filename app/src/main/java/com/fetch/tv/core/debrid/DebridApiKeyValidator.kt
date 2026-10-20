package com.fetch.tv.core.debrid

import com.fetch.tv.data.remote.api.PremiumizeApi
import com.fetch.tv.data.remote.api.TorboxApi
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Checks a debrid API key against the provider before it is saved. */
class DebridApiKeyValidator @Inject constructor(
    private val torboxApi: TorboxApi,
    private val premiumizeApi: PremiumizeApi
) {
    suspend fun isValid(providerId: String, apiKey: String): Boolean {
        return try {
            when (DebridProviders.byId(providerId)?.id) {
                DebridProviders.TORBOX_ID -> {
                    val response = torboxApi.getUser("Bearer $apiKey")
                    response.body()?.close()
                    response.errorBody()?.close()
                    response.isSuccessful
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
}
