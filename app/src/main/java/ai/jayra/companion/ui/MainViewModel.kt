package ai.jayra.companion.ui

import ai.jayra.companion.JayraApp
import ai.jayra.companion.accessibility.AccessibilityBridge
import ai.jayra.companion.commands.CommandOutcome
import ai.jayra.companion.commands.Notifier
import ai.jayra.companion.data.PairingException
import ai.jayra.companion.data.PairingParser
import ai.jayra.companion.data.Settings
import ai.jayra.companion.net.BridgeController.LogKind
import ai.jayra.companion.net.HostDiscovery
import ai.jayra.companion.net.LinkState
import ai.jayra.companion.protocol.PhoneCommand
import ai.jayra.companion.security.NetworkPolicy
import ai.jayra.companion.service.BridgeService
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class PermState(
    val notifications: Boolean = false,
    val microphone: Boolean = false,
    val accessibility: Boolean = false,
    val batteryExempt: Boolean = false,
) {
    /** The three wizard permissions. Battery exemption is advice, not a requirement. */
    val grantedCount get() = listOf(notifications, accessibility, microphone).count { it }
}

data class DeviceSnapshot(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val freeBytes: Long?,
    val totalBytes: Long?,
    val wifi: String,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as JayraApp).graph
    private val appCtx get() = getApplication<Application>()

    val pairing = graph.pairingStore.pairing
    val settings = graph.settingsStore.settings
    val bridgeState = graph.controller.bridgeState
    val lastSync = graph.controller.lastSyncMs
    val entries = graph.controller.entries
    val finder = graph.finder.active
    val live = graph.controller.live
    val liveState = graph.controller.live.state

    val a11yPaused = AccessibilityBridge.paused
    val a11yText = AccessibilityBridge.textSharing

    val discovered = MutableStateFlow<List<HostDiscovery.Host>>(emptyList())
    val discovering = MutableStateFlow(false)
    val pairBusy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    val permissions = MutableStateFlow(readPermissions())
    val device = MutableStateFlow<DeviceSnapshot?>(null)

    // ---- Permissions & device ------------------------------------------------------------------

    fun refreshPermissions() { permissions.value = readPermissions() }

    private fun readPermissions(): PermState {
        val pm = appCtx.getSystemService(PowerManager::class.java)
        return PermState(
            notifications = Notifier.canPost(appCtx),
            microphone = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            accessibility = AccessibilityBridge.connected.value,
            batteryExempt = pm?.isIgnoringBatteryOptimizations(appCtx.packageName) == true,
        )
    }

    fun refreshDevice() {
        viewModelScope.launch(Dispatchers.Default) {
            val j = graph.statusCollector.collect(false)
            device.value = DeviceSnapshot(
                batteryPercent = j.optInt("batteryPercent", -1).takeIf { it >= 0 },
                charging = if (j.has("batteryCharging")) j.optBoolean("batteryCharging") else null,
                freeBytes = if (j.has("storageFreeBytes")) j.optLong("storageFreeBytes") else null,
                totalBytes = if (j.has("storageTotalBytes")) j.optLong("storageTotalBytes") else null,
                wifi = j.optString("wifiState", "unknown"),
            )
        }
    }

    fun completeOnboarding() = graph.settingsStore.update { it.copy(onboardingDone = true) }
    fun replayOnboarding() = graph.settingsStore.update { it.copy(onboardingDone = false) }

    // ---- Pairing & link -------------------------------------------------------------------------

    /** QR or pasted link. Returns true if valid and stored. */
    fun pairFromText(raw: String): Boolean = try {
        graph.pairingStore.save(PairingParser.parse(raw))
        true
    } catch (e: PairingException) {
        message.value = e.message
        false
    }

    /** Manual 8-digit code exchange. [onSuccess] runs after the token has been stored. */
    fun pairWithCode(host: String, portText: String, code: String, onSuccess: () -> Unit) {
        val port = portText.trim().toIntOrNull()
        if (port == null) {
            message.value = "Enter a valid port (JAYRA uses 3000)."
            return
        }
        viewModelScope.launch {
            pairBusy.value = true
            try {
                graph.pairingStore.save(graph.pairingClient.exchange(host, port, code.trim()))
                onSuccess()
            } catch (e: PairingException) {
                message.value = e.message
            } finally {
                pairBusy.value = false
            }
        }
    }

    fun startBridge() {
        ContextCompat.startForegroundService(appCtx, BridgeService.intent(appCtx))
    }

    /** Pause: keeps the pairing so Resume works. */
    fun pauseLink() {
        graph.settingsStore.update { it.copy(bridgeWanted = false) }
        graph.controller.stopBridge()
        appCtx.stopService(BridgeService.intent(appCtx))
    }

    /** Explicit disconnect: stops everything and erases the stored credentials. */
    fun forgetPc() {
        pauseLink()
        graph.pairingStore.clear()   // controller also stops /live on this change
    }

    fun updateEndpoint(host: String, portText: String): Boolean {
        val cur = pairing.value ?: return false
        val h = host.trim().removePrefix("[").removeSuffix("]")
        val port = portText.trim().toIntOrNull()
        if (h.isEmpty() || port == null || port !in 1..65535) {
            message.value = "Enter a valid address and port."
            return false
        }
        if (!cur.secure && !NetworkPolicy.isPrivateHost(h)) {
            message.value = "Unencrypted links are only allowed to private LAN addresses."
            return false
        }
        graph.pairingStore.save(cur.copy(host = h, port = port))
        message.value = "Endpoint saved."
        return true
    }

    fun discover() {
        if (discovering.value) return
        viewModelScope.launch {
            discovering.value = true
            discovered.value = try { graph.discovery.discover() } catch (e: Exception) { emptyList() }
            discovering.value = false
            if (discovered.value.isEmpty()) message.value = "No JAYRA host answered. Is Network Access on?"
        }
    }

    // ---- Settings -------------------------------------------------------------------------------

    fun updateSettings(transform: (Settings) -> Settings) = graph.settingsStore.update(transform)
    fun setShareSsid(on: Boolean) = graph.settingsStore.update { it.copy(shareWifiSsid = on) }
    fun setA11yPaused(on: Boolean) { AccessibilityBridge.paused.value = on }
    fun setA11yText(on: Boolean) { AccessibilityBridge.textSharing.value = on }
    fun stopFinder() = graph.finder.stop("stopped by you")

    // ---- Local (owner-initiated) phone actions -----------------------------------------------------

    fun runLocal(label: String, cmd: PhoneCommand) {
        viewModelScope.launch {
            val outcome = try {
                graph.router.execute(cmd, respectToggles = false)
            } catch (e: Exception) {
                CommandOutcome.Failure("That didn't work.")
            }
            when (outcome) {
                is CommandOutcome.Success -> {
                    graph.controller.addLog("$label ok", LogKind.LOCAL)
                    message.value = "$label: done"
                }
                is CommandOutcome.Failure -> {
                    graph.controller.addLog("$label failed: ${outcome.error.take(90)}", LogKind.LOCAL)
                    message.value = outcome.error
                }
            }
        }
    }

    // ---- Remote deck (/live) ---------------------------------------------------------------------

    fun ensureLive() {
        if (pairing.value != null && !live.isWanted) live.start()
    }

    fun toggleLive() {
        if (live.isWanted) live.stop() else live.start()
    }

    fun sendText(text: String): Boolean {
        val ok = live.sendText(text)
        if (!ok) message.value = "JAYRA's deck isn't connected."
        return ok
    }

    fun quickAction(action: String) {
        val args = if (action == "volumeUp" || action == "volumeDown") JSONObject().put("steps", 2) else JSONObject()
        if (!live.sendRemoteAction(action, args)) message.value = "JAYRA's deck isn't connected."
    }

    fun startTalking(): Boolean {
        if (liveState.value !is LinkState.Open) {
            ensureLive()
            message.value = "Connecting to JAYRA - hold the mic again in a moment."
            return false
        }
        val ok = live.startTalking()
        if (!ok) message.value = "Couldn't start the microphone."
        return ok
    }

    fun stopTalking() = live.stopTalking()

    fun killSwitch() {
        message.value = if (live.emergencyStop()) "Emergency stop sent." else "Link was down - stop queued, reconnecting."
    }
}
