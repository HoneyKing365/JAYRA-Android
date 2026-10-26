package ai.jayra.companion.service

import ai.jayra.companion.JayraApp
import ai.jayra.companion.R
import ai.jayra.companion.commands.Notifier
import ai.jayra.companion.net.LinkState
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service (type connectedDevice) that keeps the /mobile-bridge link alive. */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as JayraApp).graph
    private var started = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            userStop()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, Notifier.ID_SERVICE, buildNotification(graph.controller.bridgeState.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {   // e.g. started while Android forbids it
            stopSelf()
            return START_NOT_STICKY
        }
        if (graph.pairingStore.pairing.value == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            graph.settingsStore.update { it.copy(bridgeWanted = true) }
            acquireWifiLock()
            registerNetworkCallback()
            graph.controller.startBridge()
            scope.launch { graph.controller.bridgeState.collect { onState(it) } }
        }
        return START_STICKY
    }

    private fun onState(state: LinkState) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(Notifier.ID_SERVICE, buildNotification(state))
        if (state is LinkState.Rejected) {   // credentials refused: nothing to keep alive
            graph.settingsStore.update { it.copy(bridgeWanted = false) }
            stopSelf()
        }
    }

    private fun userStop() {
        graph.settingsStore.update { it.copy(bridgeWanted = false) }
        graph.controller.stopBridge()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        netCallback = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        // Keep a Rejected reason visible in the UI; otherwise make sure the socket is closed.
        if (graph.controller.bridgeState.value !is LinkState.Rejected) graph.controller.stopBridge()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "jayra:bridge").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                graph.controller.onNetworkAvailable()   // skip the remaining backoff wait
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        netCallback = cb
    }

    private fun buildNotification(state: LinkState): Notification {
        val host = graph.pairingStore.pairing.value?.displayName ?: "your laptop"
        val text = when (state) {
            LinkState.Open -> "Linked to $host"
            LinkState.Idle, LinkState.Connecting -> "Connecting to $host…"
            is LinkState.Waiting -> "Reconnecting to $host…"
            is LinkState.Rejected -> state.reason
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_jayra)
            .setContentTitle("JAYRA Companion")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifier.openAppIntent(this))
            .addAction(0, "Disconnect", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "ai.jayra.companion.action.STOP_BRIDGE"
        fun intent(ctx: Context) = Intent(ctx, BridgeService::class.java)
    }
}
