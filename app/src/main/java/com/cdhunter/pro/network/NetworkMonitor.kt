package com.cdhunter.pro.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Detects connectivity changes (spec: adaptive scanning must be able to pause
 * on network switch and honour Wi-Fi-only mode).
 */
class NetworkMonitor(context: Context, private val scope: CoroutineScope) {

    data class NetState(
        val online: Boolean,
        val metered: Boolean,
        /** Stable per-network token used to detect *changes* of the active network. */
        val networkToken: String,
        val changedAtMs: Long,
    ) {
        val isWifiOrEthernet: Boolean get() = online && !metered
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(currentState())

    val state: StateFlow<NetState> = _state

    /** Emits on every connectivity change. */
    val changes: Flow<NetState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentState())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(currentState())
            }

            override fun onLost(network: Network) {
                trySend(currentState())
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }

    fun currentState(): NetState {
        val active: Network? = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = caps != null &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetState(
            online = online,
            metered = metered,
            networkToken = active?.networkHandle?.toString() ?: "none",
            changedAtMs = System.currentTimeMillis(),
        )
    }

    /** Starts keeping [state] fresh; called once from the Application. */
    fun start() {
        scope.launch {
            changes.collect { _state.value = it }
        }
    }
}
