package ai.jayra.companion.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class ProximityState { NEAR, TRANSITIONING_AWAY, AWAY, TRANSITIONING_NEAR }

data class ProximityConfig(
    val nearRssi: Int = -65,
    val awayRssi: Int = -75,
    val nearLatencyMs: Double = 40.0,
    val awayLatencyMs: Double = 90.0,
    val alpha: Double = 0.35,
    val awayHoldMs: Long = 4_000,
    val nearHoldMs: Long = 3_000
)

data class ProximitySnapshot(
    val state: ProximityState = ProximityState.NEAR,
    val score: Int = 100,
    val smoothedRssi: Double? = null,
    val smoothedLatencyMs: Double? = null
)

/**
 * Pure (Android-free) implementation of the spec §2.7 fusion + hysteresis state machine.
 * The phone runs it to show a live proximity score; the host's `spatial_handoff_event` is authoritative
 * and re-syncs it through [forceState].
 */
class ProximityEngine(private val config: ProximityConfig = ProximityConfig()) {
    private val _snapshot = MutableStateFlow(ProximitySnapshot())
    val snapshot: StateFlow<ProximitySnapshot> = _snapshot.asStateFlow()

    private var rssiEma: Double? = null
    private var latEma: Double? = null
    private var state = ProximityState.NEAR
    private var since = 0L

    @Synchronized
    fun onSample(rssi: Int?, latencyMs: Double?, nowMs: Long): ProximitySnapshot {
        if (rssi == null && latencyMs == null) return _snapshot.value
        rssi?.let { rssiEma = rssiEma?.let { prev -> config.alpha * it + (1 - config.alpha) * prev } ?: it.toDouble() }
        latencyMs?.let { latEma = latEma?.let { prev -> config.alpha * it + (1 - config.alpha) * prev } ?: it }

        val r = rssiEma
        val l = latEma
        val away = (r != null && r <= config.awayRssi) || (l != null && l >= config.awayLatencyMs)
        val near = (r == null || r >= config.nearRssi) && (l == null || l <= config.nearLatencyMs)

        when (state) {
            ProximityState.NEAR -> if (away) {
                state = ProximityState.TRANSITIONING_AWAY
                since = nowMs
            }
            ProximityState.TRANSITIONING_AWAY -> when {
                !away -> state = ProximityState.NEAR
                nowMs - since >= config.awayHoldMs -> state = ProximityState.AWAY
            }
            ProximityState.AWAY -> if (near) {
                state = ProximityState.TRANSITIONING_NEAR
                since = nowMs
            }
            ProximityState.TRANSITIONING_NEAR -> when {
                !near -> state = ProximityState.AWAY
                nowMs - since >= config.nearHoldMs -> state = ProximityState.NEAR
            }
        }
        return publish()
    }

    @Synchronized
    fun forceState(newState: ProximityState, score: Int? = null) {
        state = newState
        publish(score)
    }

    @Synchronized
    fun reset() {
        rssiEma = null
        latEma = null
        state = ProximityState.NEAR
        publish(100)
    }

    private fun score(): Int {
        val r = rssiEma?.let { ((it + 85.0) / 30.0).coerceIn(0.0, 1.0) }
        val l = latEma?.let { ((120.0 - it) / 105.0).coerceIn(0.0, 1.0) }
        val parts = listOfNotNull(r?.let { it to 0.6 }, l?.let { it to 0.4 })
        if (parts.isEmpty()) return _snapshot.value.score
        return (parts.sumOf { it.first * it.second } / parts.sumOf { it.second } * 100).roundToInt()
    }

    private fun publish(scoreOverride: Int? = null): ProximitySnapshot {
        val s = ProximitySnapshot(state, scoreOverride ?: score(), rssiEma, latEma)
        _snapshot.value = s
        return s
    }
}

/** Reads the current Wi-Fi RSSI (dBm). Uses a NetworkCallback on API 31+, the legacy call below. */
class WifiSignalMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private val wm = appContext.getSystemService(WifiManager::class.java)

    @Volatile private var cachedRssi: Int? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            (caps.transportInfo as? WifiInfo)?.rssi?.takeIf { it in -126..-1 }?.let { cachedRssi = it }
        }

        override fun onLost(network: Network) {
            cachedRssi = null
        }
    }

    fun start() {
        if (registered) return
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback
            )
            registered = true
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        cachedRssi = null
    }

    @Suppress("DEPRECATION")
    fun currentRssi(): Int? {
        cachedRssi?.let { return it }
        return runCatching { wm.connectionInfo.rssi }.getOrNull()?.takeIf { it in -126..-1 }
    }
}
