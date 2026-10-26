package ai.jayra.companion.net

import ai.jayra.companion.accessibility.A11yOutcome
import ai.jayra.companion.accessibility.AccessibilityBridge
import ai.jayra.companion.commands.CommandOutcome
import ai.jayra.companion.commands.CommandRouter
import ai.jayra.companion.commands.PhoneFinder
import ai.jayra.companion.data.PairingStore
import ai.jayra.companion.data.SettingsStore
import ai.jayra.companion.protocol.CommandParser
import ai.jayra.companion.protocol.Protocol
import ai.jayra.companion.protocol.ProtocolException
import ai.jayra.companion.telemetry.StatusCollector
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Owns the authenticated /mobile-bridge link: handshake, command -> exactly one command_result,
 * accessibility actions, periodic status_update. Credentials are never logged.
 */
class BridgeController(
    context: Context,
    private val scope: CoroutineScope,
    private val pairingStore: PairingStore,
    private val settingsStore: SettingsStore,
    private val statusCollector: StatusCollector,
    private val router: CommandRouter,
    private val finder: PhoneFinder,
) {
    enum class LogKind { SYSTEM, INBOUND, OUTBOUND, ERROR, LOCAL }
    data class LogEntry(val atMs: Long, val kind: LogKind, val text: String)

    private val appContext = context.applicationContext

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // long-lived socket; liveness comes from pings
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** Short, non-sensitive activity lines only (no notification bodies, SSIDs or tokens). */
    fun addLog(text: String, kind: LogKind = LogKind.SYSTEM) {
        _entries.update { (listOf(LogEntry(System.currentTimeMillis(), kind, text)) + it).take(120) }
    }

    /** When the phone last pushed a status_update to the host (null until the first one). */
    private val _lastSyncMs = MutableStateFlow<Long?>(null)
    val lastSyncMs: StateFlow<Long?> = _lastSyncMs.asStateFlow()

    private val commandScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
    private val seenIds = LinkedHashSet<String>()
    private var statusJob: Job? = null

    val live = LiveSession(scope, http, pairingStore) { addLog(it) }

    private val bridge = ReconnectingSocket(
        http, scope, { bridgeRequest() },
        object : ReconnectingSocket.Listener {
            override fun onOpen() = addLog("Bridge socket open")
            override fun onText(text: String) = onBridgeText(text)
            override fun onClosed() {
                statusJob?.cancel()
                commandScope.coroutineContext[Job]?.cancelChildren()
                finder.stop("link lost")                      // finder must never outlive the link
                AccessibilityBridge.textSharing.value = false // text sharing is per-session
                addLog("Bridge disconnected")
            }
        },
    )

    val bridgeState: StateFlow<LinkState> get() = bridge.state

    init {
        AccessibilityBridge.eventSink = { json ->
            if (bridge.state.value is LinkState.Open) bridge.send(json.toString())
        }
        // Follow pairing changes: unpair stops everything; re-pair restarts what was running.
        scope.launch {
            var previous = pairingStore.pairing.value
            pairingStore.pairing.collect { now ->
                if (now == previous) return@collect
                val bridgeWasOn = bridge.isWanted
                val liveWasOn = live.isWanted
                previous = now
                bridge.stop()
                live.stop()
                if (now != null) {
                    if (bridgeWasOn) bridge.start()
                    if (liveWasOn) live.start()
                }
            }
        }
    }

    fun startBridge() = bridge.start()

    fun stopBridge() {
        bridge.stop()
        finder.stop("bridge stopped")
    }

    fun onNetworkAvailable() {
        bridge.nudge()
        live.nudge()
    }

    private fun bridgeRequest(): Request? {
        val p = pairingStore.pairing.value ?: return null
        return Request.Builder()
            .url(p.wsUrl("/mobile-bridge"))
            .header("Origin", p.origin)
            .header("X-JAYRA-Token", p.bridgeToken)   // header only - never in the URL or logs
            .build()
    }

    // ---- Inbound frames ------------------------------------------------------------------------

    private fun onBridgeText(text: String) {
        if (text.length > Protocol.MAX_FRAME_CHARS) return
        val obj = try { JSONObject(text) } catch (e: JSONException) { return }
        when (obj.optString("type")) {
            "bridge_ready" -> {
                val v = obj.optInt("protocolVersion", -1)
                if (v != Protocol.VERSION) {
                    bridge.fail("Host speaks bridge protocol v$v; this app supports v${Protocol.VERSION}. Update JAYRA or the app.")
                    return
                }
                addLog("Paired and ready")
                sendIdentify()
                sendStatus()
                startStatusLoop()
            }
            "command" -> handleCommand(obj)
            "accessibility_action" -> handleAccessibilityAction(obj)
        }
    }

    private fun markFresh(id: String): Boolean = synchronized(seenIds) {
        if (!seenIds.add(id)) return false      // duplicate request id: answer only once
        if (seenIds.size > 128) seenIds.remove(seenIds.first())
        true
    }

    private fun handleCommand(obj: JSONObject) {
        val id = obj.optString("id", "")
        if (id.isEmpty() || id.length > 128 || !markFresh(id)) return
        val action = obj.optJSONObject("command")?.optString("action", "").orEmpty().take(32)
        addLog(action, LogKind.INBOUND)

        commandScope.launch {
            val outcome: CommandOutcome = withTimeoutOrNull(Protocol.COMMAND_TIMEOUT_MS) {
                try {
                    router.execute(CommandParser.parse(obj.getJSONObject("command")))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProtocolException) {
                    CommandOutcome.Failure(e.message ?: "Invalid command.")
                } catch (e: JSONException) {
                    CommandOutcome.Failure("Malformed command envelope.")
                } catch (e: Exception) {
                    CommandOutcome.Failure("The command failed on the phone.")
                }
            } ?: CommandOutcome.Failure("The command timed out on the phone.")

            when (outcome) {
                is CommandOutcome.Success -> addLog("$action ok", LogKind.OUTBOUND)
                is CommandOutcome.Failure -> addLog("$action failed: ${outcome.error.take(90)}", LogKind.ERROR)
            }
            val reply = JSONObject().put("type", "command_result").put("id", id)
            when (outcome) {
                is CommandOutcome.Success -> reply.put("success", true).put("result", outcome.result)
                is CommandOutcome.Failure -> reply.put("success", false).put("error", outcome.error.take(300))
            }
            bridge.send(reply.toString())
        }
    }

    private fun handleAccessibilityAction(obj: JSONObject) {
        val id = obj.optString("id", "")
        if (id.isEmpty() || id.length > 128 || !markFresh(id)) return
        val action = obj.optString("action", "").take(32)
        val viewId = obj.optJSONObject("target")?.optString("viewId", "")?.takeIf { it.isNotEmpty() }
        addLog("accessibility $action", LogKind.INBOUND)

        commandScope.launch {
            val outcome: A11yOutcome = withTimeoutOrNull(Protocol.COMMAND_TIMEOUT_MS) {
                val svc = AccessibilityBridge.service
                when {
                    svc == null -> A11yOutcome(false, "The accessibility service is not enabled on the phone.")
                    AccessibilityBridge.paused.value -> A11yOutcome(false, "Accessibility control is paused by the phone owner.")
                    else -> svc.perform(action, viewId)
                }
            } ?: A11yOutcome(false, "The action timed out on the phone.")

            if (outcome.success) addLog("accessibility $action ok", LogKind.OUTBOUND)
            else addLog("accessibility $action failed: ${outcome.error.orEmpty().take(90)}", LogKind.ERROR)
            val reply = JSONObject()
                .put("type", "accessibility_action_result")
                .put("id", id)
                .put("success", outcome.success)
            outcome.error?.let { reply.put("error", it.take(300)) }
            outcome.result?.let { reply.put("result", it) }
            bridge.send(reply.toString())
        }
    }

    // ---- Outbound frames -----------------------------------------------------------------------

    private fun sendIdentify() {
        val name = runCatching { Settings.Global.getString(appContext.contentResolver, "device_name") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
        bridge.send(
            JSONObject()
                .put("type", "identify")
                .put("name", name.take(60))
                .put("platform", "Android ${Build.VERSION.RELEASE}")
                .toString(),
        )
    }

    private fun sendStatus() {
        val status = statusCollector.collect(settingsStore.settings.value.shareWifiSsid)
        if (bridge.send(JSONObject().put("type", "status_update").put("status", status).toString())) {
            _lastSyncMs.value = System.currentTimeMillis()
        }
    }

    private fun startStatusLoop() {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (isActive) {
                delay(Protocol.STATUS_INTERVAL_MS)
                sendStatus()
            }
        }
    }
}
