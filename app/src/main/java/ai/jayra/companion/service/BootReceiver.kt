package ai.jayra.companion.service

import ai.jayra.companion.JayraApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restores the link after a reboot, but only if the user had it switched on and is still paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val graph = (context.applicationContext as JayraApp).graph
        if (graph.settingsStore.settings.value.bridgeWanted && graph.pairingStore.pairing.value != null) {
            runCatching { ContextCompat.startForegroundService(context, BridgeService.intent(context)) }
        }
    }
}
