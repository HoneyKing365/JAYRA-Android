package ai.jayra.companion.service

import ai.jayra.companion.model.ConnectionState
import ai.jayra.companion.runtime.BridgeRuntime
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Keeps the process (and therefore the bridge WebSockets, proximity telemetry and clipboard listener)
 * alive. Runs as `connectedDevice`; the `microphone` type is added only while the mic orb is live,
 * because Android 14+ forbids starting a microphone service from the background.
 */
class JayraForegroundService : Service() {
    private val runtime: BridgeRuntime by inject()
    private val notifier: NotificationHelper by inject()
    private val device: DeviceControlManager by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var micOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promote(false)
        scope.launch {
            combine(runtime.connection, runtime.micActive, runtime.endpoint) { state, mic, ep ->
                Triple(state, mic, ep)
            }.collect { (state, mic, ep) ->
                val status = when (state) {
                    ConnectionState.CONNECTED -> "Connected · ${ep?.display ?: ""}"
                    ConnectionState.CONNECTING -> "Connecting…"
                    ConnectionState.RECONNECTING -> "Reconnecting to PC…"
                    ConnectionState.DISCONNECTED -> "Not connected"
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NotificationHelper.ID_SERVICE, notifier.serviceNotification(status, mic))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote(micOn) // satisfies startForegroundService() on every start
        when (intent?.action) {
            ACTION_MIC -> {
                micOn = intent.getBooleanExtra(EXTRA_MIC, false)
                promote(micOn)
            }
            ACTION_DISCONNECT -> {
                runtime.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_STOP_FINDER -> device.stopFinder()
            else -> runtime.connectSaved()
        }
        return START_STICKY
    }

    private fun promote(mic: Boolean) {
        val notification = notifier.serviceNotification("Starting…", mic)
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            ServiceCompat.startForeground(
                this, NotificationHelper.ID_SERVICE, notification,
                if (mic) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else base
            )
        } catch (e: SecurityException) {
            // microphone type refused (permission missing / started from background) – fall back
            ServiceCompat.startForeground(this, NotificationHelper.ID_SERVICE, notification, base)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ai.jayra.companion.START"
        const val ACTION_MIC = "ai.jayra.companion.MIC"
        const val ACTION_DISCONNECT = "ai.jayra.companion.DISCONNECT"
        const val ACTION_STOP_FINDER = "ai.jayra.companion.STOP_FINDER"
        const val EXTRA_MIC = "mic"

        fun intent(context: Context, action: String) = Intent(context, JayraForegroundService::class.java).setAction(action)

        /** Call only while the app UI is visible (Android 12+ background-start restrictions). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        }

        fun setMic(context: Context, on: Boolean) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_MIC).putExtra(EXTRA_MIC, on))
        }
    }
}
