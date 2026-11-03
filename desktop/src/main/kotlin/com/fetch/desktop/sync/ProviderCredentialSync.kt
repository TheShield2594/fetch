package com.fetch.desktop.sync

import com.fetch.desktop.data.JsonFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Provider credentials as the TV syncs them (`ProviderCredentialSyncService`): per profile, one
 * value per provider. The desktop uses MDBList's API key; AnimeSkip's client id has no use here
 * yet, so it is kept as the account has it and sent back unchanged.
 */
@Serializable
data class ProviderCredentials(
    val mdblistApiKey: String = "",
    /** The account's AnimeSkip client id, carried so a push doesn't clear the TV's. */
    val animeskipClientId: String = ""
)

/** What the last sync agreed on, per profile: a change from it here is pushed. */
@Serializable
data class ProviderCredentialBase(val values: ProviderCredentials? = null)

fun providerCredentialBaseFile(dir: File) =
    JsonFile(File(dir, "provider-credentials-base.json"), ProviderCredentialBase.serializer()) { ProviderCredentialBase() }

@Serializable
internal data class RemoteProviderCredential(
    val provider: String,
    @SerialName("credential_json") val credentialJson: JsonObject = JsonObject(emptyMap())
)

/**
 * The TV's provider credential sync on the sync server's RPCs (`sync_pull_provider_credentials`,
 * `sync_push_provider_credentials`, `sync_seed_provider_credentials`), for one profile.
 */
object ProviderCredentialSync {
    const val MDBLIST = "mdblist"
    const val ANIMESKIP = "animeskip"
    private const val API_KEY = "api_key"
    private const val CLIENT_ID = "client_id"
    private val json = Json { ignoreUnknownKeys = true }

    internal fun params(profileId: Int, values: ProviderCredentials, originClientId: String?): JsonObject = buildJsonObject {
        put("p_profile_id", profileId)
        putJsonArray("p_credentials") {
            addJsonObject {
                put("provider", MDBLIST)
                putJsonObject("credential_json") { put(API_KEY, values.mdblistApiKey.trim()) }
            }
            addJsonObject {
                put("provider", ANIMESKIP)
                putJsonObject("credential_json") { put(CLIENT_ID, values.animeskipClientId.trim()) }
            }
        }
        originClientId?.let { put("p_origin_client_id", it) }
    }

    internal fun decodeRows(body: String): List<RemoteProviderCredential> =
        runCatching { json.decodeFromString<List<RemoteProviderCredential>>(body) }.getOrDefault(emptyList())

    /** [local] with the account's values over it, as the TV's `mergeRemote`. */
    internal fun merge(local: ProviderCredentials, rows: List<RemoteProviderCredential>): ProviderCredentials {
        val byProvider = rows.associateBy { it.provider.lowercase() }
        fun value(provider: String, field: String): String? =
            (byProvider[provider]?.credentialJson?.get(field) as? JsonPrimitive)?.contentOrNull?.trim()
        return ProviderCredentials(
            mdblistApiKey = value(MDBLIST, API_KEY) ?: local.mdblistApiKey,
            animeskipClientId = value(ANIMESKIP, CLIENT_ID) ?: local.animeskipClientId
        )
    }

    /**
     * One sync for [profileId], as the TV's `syncFromRemote`: a key changed here since the last
     * sync goes up first; then the account's values come down (seeding the ones it lacks).
     * Returns the MDBList key to keep here.
     */
    suspend fun sync(
        rpc: suspend (String, JsonObject) -> String,
        profileId: Int,
        localMdbListKey: String,
        base: JsonFile<ProviderCredentialBase>,
        originClientId: String?
    ): String {
        val known = runCatching { base.load() }.getOrDefault(ProviderCredentialBase()).values
        val local = ProviderCredentials(localMdbListKey.trim(), known?.animeskipClientId.orEmpty())
        if (known != null && known.mdblistApiKey != local.mdblistApiKey) {
            rpc("sync_push_provider_credentials", params(profileId, local, originClientId))
        }
        val rows = decodeRows(rpc("sync_pull_provider_credentials", buildJsonObject { put("p_profile_id", profileId) }))
        val providers = rows.map { it.provider.lowercase() }.toSet()
        if (MDBLIST !in providers || ANIMESKIP !in providers) {
            rpc("sync_seed_provider_credentials", params(profileId, local, originClientId))
        }
        val merged = merge(local, rows)
        runCatching { base.save(ProviderCredentialBase(merged)) }
        return merged.mdblistApiKey
    }
}
