package com.fetch.tv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the default network can reach, as far as Android knows. */
enum class NetworkReach {
    /** No network at all: nothing, LAN media servers included, can be reached. */
    OFFLINE,
    /**
     * Connected, but Android could not confirm internet access (router up, internet down, or a
     * network that fails the captive-portal check yet works). A LAN media server may still work.
     */
    UNCONFIRMED,
    /** Connected and validated. */
    ONLINE
}

/**
 * Watches the default network (#447) so screens can tell "you are offline" apart from a
 * failing addon.
 *
 * [NetworkReach.UNCONFIRMED] is not treated as offline on its own: some TV networks never pass
 * Android's validation yet work fine, and calling them offline would hide Home from someone who
 * is online. Callers combine it with a failed load.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivity: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    /** The default network's reach now; [NetworkReach.ONLINE] when it can't be read. */
    fun reachNow(): NetworkReach {
        val manager = connectivity ?: return NetworkReach.ONLINE
        return runCatching {
            val network = manager.activeNetwork ?: return NetworkReach.OFFLINE
            manager.getNetworkCapabilities(network).reach()
        }.getOrDefault(NetworkReach.ONLINE)
    }

    /** Emits the current reach, then each change. */
    val reach: Flow<NetworkReach> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(NetworkReach.ONLINE)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(manager.getNetworkCapabilities(network).reach())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.reach())
            }

            override fun onLost(network: Network) {
                // The default network went away; another may already be up.
                trySend(reachNow())
            }
        }
        trySend(reachNow())
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "Could not watch connectivity", it) }
            .isSuccess
        awaitClose {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.conflate().distinctUntilChanged()

    private fun NetworkCapabilities?.reach(): NetworkReach = when {
        this == null || !hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkReach.OFFLINE
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> NetworkReach.ONLINE
        else -> NetworkReach.UNCONFIRMED
    }

    private companion object {
        const val TAG = "ConnectivityMonitor"
    }
}
