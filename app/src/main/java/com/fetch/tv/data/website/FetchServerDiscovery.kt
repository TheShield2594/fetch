package com.fetch.tv.data.website

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet6Address
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "FetchServerDiscovery"

/** A Fetch website announced on the LAN. [apiUrl] is what the TV pairs against. */
data class DiscoveredFetchServer(
    val name: String,
    val apiUrl: String,
    val webUrl: String? = null
)

/**
 * Finds Fetch websites on the LAN: the `mdns` service in the website's docker-compose.yml
 * announces `_fetch._tcp` with its public API and web URLs in the TXT record. A server the TV
 * can't reach at the announced API URL (an address meant for another network, or one left at
 * the example value) is offered at the address it answered from instead.
 */
@Singleton
class FetchServerDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: FetchWebsiteClient
) {
    /** The servers found so far, updated as they come and go; browsing stops when collection does. */
    fun servers(): Flow<List<DiscoveredFetchServer>> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            trySend(emptyList())
            awaitClose()
            return@callbackFlow
        }
        val found = LinkedHashMap<String, DiscoveredFetchServer>()
        // Before Android 14 NsdManager resolves one service at a time.
        val resolving = Mutex()
        fun publish() = trySend(synchronized(found) { found.values.toList() })

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery didn't start ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                launch {
                    val resolved = resolving.withLock { resolve(nsd, service) } ?: return@launch
                    val server = reachable(resolved) ?: return@launch
                    synchronized(found) { found[service.serviceName] = server }
                    publish()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                synchronized(found) { found.remove(service.serviceName) }
                publish()
            }
        }

        publish()
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            Log.w(TAG, "Discovery failed to start", error)
        }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    @Suppress("DEPRECATION") // resolveService and host still work on 14+, and are all there is below it.
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): Candidates? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val candidates = discoveredServerCandidates(
                            serviceName = info.serviceName,
                            attributes = info.attributes.orEmpty(),
                            host = info.host?.let { address ->
                                address.hostAddress?.let { if (address is Inet6Address) "[${it.substringBefore('%')}]" else it }
                            },
                            port = info.port
                        )
                        if (continuation.isActive) continuation.resume(candidates)
                    }
                }
                try {
                    nsd.resolveService(service, listener)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    /** The first of a server's addresses that answers; the announced one when none do. */
    private suspend fun reachable(candidates: Candidates): DiscoveredFetchServer? {
        val urls = candidates.apiUrls
        if (urls.isEmpty()) return null
        val apiUrl = urls.firstOrNull { client.answers(it) } ?: urls.first()
        return DiscoveredFetchServer(candidates.name, apiUrl, candidates.webUrl)
    }

    internal data class Candidates(val name: String, val apiUrls: List<String>, val webUrl: String?)

    companion object {
        const val SERVICE_TYPE = "_fetch._tcp."
        private const val RESOLVE_TIMEOUT_MS = 5_000L

        private fun txtUrl(attributes: Map<String, ByteArray?>, key: String): String? =
            attributes[key]
                ?.toString(StandardCharsets.UTF_8)
                ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                ?.let(FetchWebsiteClient::normalizeServerUrl)

        /**
         * Where a resolved `_fetch._tcp` service might be reached: the TXT `api` URL first (it
         * may be a reverse proxy's single address), then the address and port it answered from.
         */
        internal fun discoveredServerCandidates(
            serviceName: String,
            attributes: Map<String, ByteArray?>,
            host: String?,
            port: Int
        ): Candidates {
            val announced = txtUrl(attributes, "api")
            val direct = host?.takeIf { it.isNotBlank() && port in 1..65535 }
                ?.let { FetchWebsiteClient.normalizeServerUrl("http://$it:$port") }
            return Candidates(
                name = serviceName.ifBlank { "Fetch" },
                apiUrls = listOfNotNull(announced, direct).distinct(),
                webUrl = txtUrl(attributes, "web")
            )
        }
    }
}
