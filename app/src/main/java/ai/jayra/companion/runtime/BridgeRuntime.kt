package ai.jayra.companion.runtime

import ai.jayra.companion.data.SecureStore
import ai.jayra.companion.model.*
import ai.jayra.companion.net.DiscoveredHost
import ai.jayra.companion.net.FileTransferManager
import ai.jayra.companion.net.FluidTextStreamingBuffer
import ai.jayra.companion.net.HostApi
import ai.jayra.companion.net.JayraBridgeClient
import ai.jayra.companion.net.LanDiscoveryManager
import ai.jayra.companion.net.PairingParser
import ai.jayra.companion.service.*
import ai.jayra.companion.util.NetUtil
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide coordinator: owns the bridge connection, routes every host frame to the right subsystem
 * (character, ReAct, chat, audio, clipboard, auth, proximity, vibe, files) and exposes the resulting
 * state as flows. UI talks to it through [ai.jayra.companion.viewmodel.CompanionViewModel].
 */
class BridgeRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: SecureStore,
    private val client: JayraBridgeClient,
    private val api: HostApi,
    val files: FileTransferManager,
    private val discovery: LanDiscoveryManager,
    private val apps: AppLaunchManager,
    private val device: DeviceControlManager,
    private val notifier: NotificationHelper,
    private val clipboard: JayraClipboardListener,
    private val mic: AudioRecordStreamer,
    private val player: PcmAudioPlayer,
    private val ambient: AmbientAudioPlayer,
    val proximityEngine: ProximityEngine,
    private val wifi: WifiSignalMonitor,
    private val vibeSensors: VibeTelemetryManager,
    private val biometric: BiometricAuthManager
) {
    // ───────────────────────── connection / pairing ─────────────────────────
    val connection: StateFlow<ConnectionState> = client.bridgeState
    val liveConnection: StateFlow<ConnectionState> = client.liveState

    private val _endpoint = MutableStateFlow(store.host?.let { HostEndpoint(it, store.port) })
    val endpoint: StateFlow<HostEndpoint?> = _endpoint.asStateFlow()

    private val _paired = MutableStateFlow(!store.token.isNullOrBlank())
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    private val _latency = MutableStateFlow<Int?>(null)
    val latencyMs: StateFlow<Int?> = _latency.asStateFlow()

    // ───────────────────────── audio / mic ─────────────────────────
    private val _routing = MutableStateFlow(store.audioRouting)
    val audioRouting: StateFlow<AudioRoutingMode> = _routing.asStateFlow()
    val micActive: StateFlow<Boolean> = mic.active
    val micLevel: StateFlow<Float> = mic.level
    val finderActive: StateFlow<Boolean> = device.finderActive

    // ───────────────────────── character / tasks ─────────────────────────
    private val _character = MutableStateFlow(CharacterVisualState())
    val character: StateFlow<CharacterVisualState> = _character.asStateFlow()

    private val _stage = MutableStateFlow(ReActStage.IDLE)
    val taskStage: StateFlow<ReActStage> = _stage.asStateFlow()

    private val _activeTask = MutableStateFlow<ActiveTask?>(null)
    val activeTask: StateFlow<ActiveTask?> = _activeTask.asStateFlow()

    private val _taskLog = MutableStateFlow<List<TaskLogLine>>(emptyList())
    val taskLog: StateFlow<List<TaskLogLine>> = _taskLog.asStateFlow()

    private var lastGoal: String = ""

    // ───────────────────────── chat ─────────────────────────
    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val reasoning = StringBuilder()
    private val reasoningLock = Any()

    val streamBuffer = FluidTextStreamingBuffer(scope) { done -> onAssistantTurnEnd(done) }
    val streamingText: StateFlow<String> = streamBuffer.text

    // ───────────────────────── session / security ─────────────────────────
    private val _handover = MutableStateFlow<SessionHandover?>(null)
    val handover: StateFlow<SessionHandover?> = _handover.asStateFlow()

    private val _pendingAuth = MutableStateFlow<AuthRequest?>(null)
    val pendingAuth: StateFlow<AuthRequest?> = _pendingAuth.asStateFlow()
    private var authTimeoutJob: Job? = null

    private val _clipboardSync = MutableStateFlow(store.clipboardSync)
    val clipboardSync: StateFlow<Boolean> = _clipboardSync.asStateFlow()

    private val _biometricGuard = MutableStateFlow(store.biometricGuard)
    val biometricGuard: StateFlow<Boolean> = _biometricGuard.asStateFlow()

    private val _sassyLanguage = MutableStateFlow(store.sassyLanguage)
    val sassyLanguage: StateFlow<SassyLanguage> = _sassyLanguage.asStateFlow()

    // ───────────────────────── proximity / vibe / knowledge ─────────────────────────
    val proximity: StateFlow<ProximitySnapshot> = proximityEngine.snapshot

    private val _handoff = MutableStateFlow<HandoffEvent?>(null)
    val lastHandoff: StateFlow<HandoffEvent?> = _handoff.asStateFlow()

    private val _vibe = MutableStateFlow(VibeUiState())
    val vibe: StateFlow<VibeUiState> = _vibe.asStateFlow()

    private val _flashcard = MutableStateFlow<DailyFlashcard?>(null)
    val flashcard: StateFlow<DailyFlashcard?> = _flashcard.asStateFlow()

    private val _proximityEnabled = MutableStateFlow<Boolean?>(null)
    val proximityEnabled: StateFlow<Boolean?> = _proximityEnabled.asStateFlow()

    private val _vibeEnabled = MutableStateFlow<Boolean?>(null)
    val vibeEnabled: StateFlow<Boolean?> = _vibeEnabled.asStateFlow()

    // ───────────────────────── one-shot events ─────────────────────────
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var sessionJob: Job? = null

    init {
        clipboard.onLocalCopy = { text ->
            client.send(
                ChannelKind.BRIDGE,
                JSONObject().put("type", "clipboard_sync").put("text", text)
                    .put("timestamp", System.currentTimeMillis()).put("source", "phone")
            )
        }
        scope.launch { client.frames.collect { handleFrame(it) } }
        scope.launch {
            client.bridgeState.collect { st ->
                if (st == ConnectionState.CONNECTED) startSession() else if (st == ConnectionState.DISCONNECTED) stopSession()
            }
        }
    }

    private fun emit(e: UiEvent) {
        _events.tryEmit(e)
    }

    private fun toast(text: String) = emit(UiEvent.Message(text))

    // ═════════════════════════ connection API ═════════════════════════

    suspend fun discover(): List<DiscoveredHost> = discovery.discover()

    fun updateEndpoint(host: String, port: Int) {
        store.host = host.trim()
        store.port = port
        _endpoint.value = HostEndpoint(host.trim(), port)
    }

    /** Pairs with an 8-digit code (or token from a QR) and connects. */
    suspend fun pairAndConnect(host: String, port: Int, code: String): Result<Unit> {
        val h = host.trim()
        if (!NetUtil.isPrivateLan(h)) return Result.failure(IllegalArgumentException("Host must be on your local network"))
        val r = api.pair(h, port, code.trim())
        return r.map { token ->
            store.token = token
            _paired.value = true
            updateEndpoint(h, port)
            connect()
        }
    }

    fun handleScan(raw: String): String {
        val p = PairingParser.parse(raw) ?: return "Unrecognized QR code"
        val host = p.host ?: store.host ?: return "QR code has no host address"
        val port = p.port ?: store.port
        if (!NetUtil.isPrivateLan(host)) return "QR points to a non-LAN host – ignored"
        updateEndpoint(host, port)
        when {
            p.token != null -> {
                store.token = p.token
                _paired.value = true
                connect()
            }
            p.code != null -> scope.launch {
                pairAndConnect(host, port, p.code).onFailure { toast("Pairing failed: ${it.message}") }
                    .onSuccess { toast("Paired with ${host}") }
            }
            else -> return "QR code has no pairing code"
        }
        return "Pairing…"
    }

    fun connect() {
        val ep = _endpoint.value ?: return toast("Enter the PC address first")
        val token = store.token ?: return toast("Pair with your PC first")
        if (!NetUtil.isPrivateLan(ep.host)) return toast("Refusing to connect to a non-LAN host")
        client.connect(ep, token)
    }

    fun connectSaved() {
        if (_paired.value && _endpoint.value != null && client.bridgeState.value == ConnectionState.DISCONNECTED) connect()
    }

    fun disconnect() {
        client.disconnect()
        stopSession()
    }

    fun forgetHost() {
        disconnect()
        store.clearCredentials()
        _paired.value = false
    }

    // ═════════════════════════ session loops ═════════════════════════

    private fun startSession() {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            wifi.start()
            vibeSensors.start()
            if (_clipboardSync.value) clipboard.start()
            launch { refreshFeatureStatus() }
            launch {
                while (isActive) {
                    telemetryTick()
                    delay(5_000)
                }
            }
            launch {
                while (isActive) {
                    delay(60_000)
                    client.send(ChannelKind.BRIDGE, vibeSensors.drain())
                }
            }
            try {
                awaitCancellation()
            } finally {
                wifi.stop()
                vibeSensors.stop()
                clipboard.stop()
            }
        }
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        _latency.value = null
        if (mic.active.value) stopMic()
        player.stop()
        ambient.stop()
        proximityEngine.reset()
    }

    private suspend fun telemetryTick() {
        val rssi = wifi.currentRssi()
        val lat = api.probeLatencyMs()
        _latency.value = lat
        proximityEngine.onSample(rssi, lat?.toDouble(), SystemClock.elapsedRealtime())
        val j = JSONObject().put("type", "proximity_telemetry")
        rssi?.let { j.put("rssi", it) }
        lat?.let { j.put("latencyMs", it) }
        client.send(ChannelKind.BRIDGE, j)
    }

    // ═════════════════════════ inbound frames ═════════════════════════

    private fun handleFrame(frame: JayraBridgeClient.Frame) {
        val j = frame.json
        when (val type = j.optString("type")) {
            "character_state" -> _character.value = CharacterVisualState.fromJson(j)
            "react_step" -> onReactStep(ReActStep.fromJson(j))
            "command" -> {
                val cmd = j.optJSONObject("command") ?: return
                scope.launch(Dispatchers.Default) { runCommand(frame.channel, j.optString("id"), cmd) }
            }
            "audio_routing_changed" -> setRoutingLocal(AudioRoutingMode.parse(j.optString("mode")), persist = true)
            "session_handover" -> j.optJSONObject("session")?.let { applyHandover(it) }
            "clipboard_sync" -> if (j.optString("source") != "phone") applyRemoteClipboard(j.optString("text"))
            "spatial_handoff_event" -> onHandoff(HandoffEvent.fromJson(j))
            "proximity_ping" -> client.send(
                frame.channel,
                JSONObject().put("type", "proximity_ping_ack").put("pingId", j.optString("pingId"))
                    .put("sentTimestamp", j.optLong("timestamp")).apply { wifi.currentRssi()?.let { put("rssi", it) } }
            )
            "ambient_vibe_shift" -> onVibeShift(VibeShift.fromJson(j))
            "daily_summary_flashcard" -> j.optJSONObject("flashcard")?.let { onFlashcard(DailyFlashcard.fromJson(it)) }
            "file_transfer_complete" -> files.onUploadAck(j)
            "audio_chunk", "audio" -> onAudioChunk(j)
            "audio_interrupt", "interrupted" -> player.stop()
            "assistant_text", "assistant_message", "model_text" -> onAssistantText(j)
            "transcript" -> onTranscript(j)
            "screenshot", "screenshot_result" -> onScreenshot(j)
            "pc_telemetry", "pc_status", "bridge_status" -> emit(UiEvent.Info("PC telemetry", pretty(j)))
            "error" -> toast(j.optString("message", j.optString("error", "Host reported an error")))
            "pong", "ack", "welcome", "hello", "command_result" -> Unit
            else -> if (type.isNotEmpty()) Unit
        }
    }

    private fun onReactStep(step: ReActStep) {
        _stage.value = step.stage
        _activeTask.value = ActiveTask(lastGoal.ifBlank { "Autonomous task" }, step.stage, step.stepNumber, step.totalSteps)
        appendLog(step.toLogLine(), step.stage)
        if (step.thought.isNotBlank()) synchronized(reasoningLock) { reasoning.appendLine(step.thought) }
        if (step.stage.isTerminal && step.stage != ReActStage.IDLE) {
            val msg = if (step.stage == ReActStage.COMPLETE) "Task complete" else "Task failed"
            notifier.postAlert("JAYRA · $msg", lastGoal.ifBlank { step.thought.take(120) })
        }
    }

    private fun appendLog(text: String, stage: ReActStage?) {
        _taskLog.update { (it + TaskLogLine(text, stage)).takeLast(MAX_LOG_LINES) }
    }

    // ───────────────────────── chat plumbing ─────────────────────────

    private fun onAssistantText(j: JSONObject) {
        val text = j.optString("text")
        val final = j.optBoolean("final", false) || j.optBoolean("done", false) || j.optBoolean("turnComplete", false)
        val isDelta = j.optBoolean("delta", !final)
        j.optStringOrNull("reasoning")?.let { synchronized(reasoningLock) { reasoning.appendLine(it) } }
        if (text.isNotEmpty()) streamBuffer.append(text)
        if (final || !isDelta) streamBuffer.endTurn()
    }

    private fun onTranscript(j: JSONObject) {
        val role = j.optString("role", "assistant").lowercase()
        if (role == "user") {
            val finalPart = j.optBoolean("final", true)
            val text = j.optString("text")
            if (finalPart && text.isNotBlank()) addChat(ChatMessage(role = ChatRole.USER, text = text))
        } else onAssistantText(j)
    }

    private fun onAssistantTurnEnd(text: String) {
        if (text.isBlank()) return
        val thoughts = synchronized(reasoningLock) { reasoning.toString().trim().also { reasoning.clear() } }
        addChat(ChatMessage(role = ChatRole.ASSISTANT, text = text, reasoning = thoughts.ifBlank { null }))
        if (!AppVisibility.foreground) notifier.postRemote("JAYRA", text.take(200), "default")
    }

    private fun addChat(m: ChatMessage) {
        _chat.update { (it + m).takeLast(MAX_CHAT) }
    }

    // ───────────────────────── audio ─────────────────────────

    private fun onAudioChunk(j: JSONObject) {
        if (!_routing.value.includesPhone) return
        val b64 = j.optString("audio", j.optString("dataBase64", j.optString("data")))
        if (b64.isEmpty()) return
        runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()?.let {
            player.enqueue(it, j.optInt("sampleRate", 24_000))
        }
    }

    private fun setRoutingLocal(mode: AudioRoutingMode, persist: Boolean) {
        _routing.value = mode
        if (persist) store.audioRouting = mode
        if (!mode.includesPhone) {
            player.stop()
            ambient.stop()
        }
    }

    fun setAudioRouting(mode: AudioRoutingMode) {
        setRoutingLocal(mode, persist = true)
        val sent = client.send(ChannelKind.BRIDGE, JSONObject().put("type", "set_audio_routing").put("mode", mode.name))
        if (!sent) scope.launch {
            val r = api.post("/api/mobile/audio-routing", JSONObject().put("mode", mode.name))
            if (!r.ok) toast("Routing saved on phone only: ${r.error}")
        }
    }

    // ───────────────────────── screenshot / telemetry ─────────────────────────

    private fun onScreenshot(j: JSONObject) {
        val b64 = listOf("image", "imageBase64", "dataBase64", "thumbnail", "data").firstNotNullOfOrNull {
            j.optString(it).takeIf { s -> s.length > 100 }
        } ?: return toast("Screenshot frame had no image")
        val clean = b64.substringAfter("base64,")
        runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull()?.let { emit(UiEvent.Screenshot(it)) }
    }

    private fun pretty(j: JSONObject): String = runCatching { j.toString(2) }.getOrDefault(j.toString())

    // ═════════════════════════ outbound user actions ═════════════════════════

    private fun requireConnected(): Boolean {
        if (connection.value != ConnectionState.CONNECTED) {
            toast("Not connected to your PC")
            return false
        }
        return true
    }

    fun sendUserInput(text: String) {
        val t = text.trim()
        if (t.isEmpty() || !requireConnected()) return
        addChat(ChatMessage(role = ChatRole.USER, text = t))
        synchronized(reasoningLock) { reasoning.clear() }
        client.send(ChannelKind.BRIDGE, JSONObject().put("type", "user_input").put("text", t))
    }

    fun triggerTask(task: String) {
        val t = task.trim()
        if (t.isEmpty() || !requireConnected()) return
        lastGoal = t
        _stage.value = ReActStage.PLANNING
        _activeTask.value = ActiveTask(t, ReActStage.PLANNING, 0, 0)
        appendLog("▶ $t", ReActStage.PLANNING)
        client.send(ChannelKind.BRIDGE, JSONObject().put("type", "trigger_task").put("task", t))
    }

    fun emergencyStop() {
        val frame = JSONObject().put("type", "emergency_stop")
        val a = client.send(ChannelKind.BRIDGE, frame)
        val b = client.send(ChannelKind.LIVE, frame)
        device.haptic(80)
        if (!a && !b) {
            scope.launch { api.post("/api/mobile/remote-command", JSONObject().put("type", "emergency_stop")) }
            toast("Bridge offline – abort sent over REST")
        }
        _stage.value = ReActStage.IDLE
        _activeTask.value = null
        appendLog("■ EMERGENCY STOP sent", ReActStage.FAILED)
        streamBuffer.reset()
    }

    fun requestScreenshot() {
        if (requireConnected()) client.send(ChannelKind.BRIDGE, JSONObject().put("type", "request_screenshot"))
    }

    fun pcMediaToggle() {
        if (requireConnected()) client.send(ChannelKind.BRIDGE, JSONObject().put("type", "pc_media_control").put("operation", "toggle"))
    }

    fun pcTelemetry() {
        scope.launch {
            val r = api.get("/api/mobile/bridge-status")
            emit(UiEvent.Info("PC telemetry", if (r.ok) r.json?.let { pretty(it) } ?: r.body else "Failed: ${r.error}"))
        }
    }

    fun testFinder() {
        scope.launch(Dispatchers.Main) {
            val r = device.startFinder(5)
            if (!r.optBoolean("success")) toast(r.optString("error"))
        }
    }

    fun stopFinder() = device.stopFinder()

    // ───────────────────────── mic ─────────────────────────

    fun startMic(): Boolean {
        if (!requireConnected()) return false
        if (!mic.hasPermission()) {
            toast("Microphone permission needed")
            return false
        }
        JayraForegroundService.setMic(context, true)
        val ok = mic.start { frame -> sendMicFrame(frame) }
        if (!ok) {
            JayraForegroundService.setMic(context, false)
            toast("Microphone is unavailable")
        }
        return ok
    }

    fun stopMic() {
        mic.stop()
        JayraForegroundService.setMic(context, false)
    }

    private fun sendMicFrame(pcm: ByteArray) {
        val frame = JSONObject().put("type", "mic_audio_frame")
            .put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP)).put("sampleRate", AudioRecordStreamer.SAMPLE_RATE)
        client.sendAny(ChannelKind.LIVE, frame)
    }

    // ───────────────────────── clipboard ─────────────────────────

    fun setClipboardSync(on: Boolean) {
        _clipboardSync.value = on
        store.clipboardSync = on
        if (on && connection.value == ConnectionState.CONNECTED) {
            clipboard.start()
            clipboard.readNow()
        } else if (!on) clipboard.stop()
    }

    private fun applyRemoteClipboard(text: String) {
        if (_clipboardSync.value && text.isNotEmpty()) clipboard.applyRemote(text)
    }

    /** Called when the app window regains focus – the only reliable moment to read the clipboard on Android 10+. */
    fun onAppFocused() {
        if (_clipboardSync.value && connection.value == ConnectionState.CONNECTED) clipboard.readNow()
    }

    // ───────────────────────── settings ─────────────────────────

    fun setBiometricGuard(on: Boolean) {
        _biometricGuard.value = on
        store.biometricGuard = on
    }

    fun setSassyLanguage(lang: SassyLanguage) {
        _sassyLanguage.value = lang
        store.sassyLanguage = lang
    }

    // ═════════════════════════ authorization ═════════════════════════

    private fun onAuthRequest(commandId: String, cmd: JSONObject) {
        val req = AuthRequest(
            commandId = commandId,
            authId = cmd.optString("authId"),
            riskLevel = cmd.optString("riskLevel", "HIGH"),
            actionDescription = cmd.optString("actionDescription", "High-risk action"),
            commandSnippet = cmd.optString("commandSnippet"),
            timeoutMs = cmd.optLong("timeoutMs", 45_000).coerceIn(5_000, 120_000)
        )
        _pendingAuth.value = req
        notifier.postAuth(req)
        device.haptic(120)
        emit(UiEvent.AuthRequired(req))
        authTimeoutJob?.cancel()
        authTimeoutJob = scope.launch {
            delay(req.timeoutMs)
            if (_pendingAuth.value?.authId == req.authId) respondAuth(false, "timeout", "Authorization timed out on the phone")
        }
    }

    fun respondAuth(approved: Boolean, method: String, reason: String) {
        val req = _pendingAuth.value ?: return
        authTimeoutJob?.cancel()
        _pendingAuth.value = null
        notifier.cancelAuth()
        val body = JSONObject().put("type", "authorization_response").put("authId", req.authId)
            .put("approved", approved).put("method", method).put("reason", reason)
        if (!client.send(ChannelKind.BRIDGE, body)) {
            scope.launch {
                val r = api.post(
                    "/api/mobile/authorize-response",
                    JSONObject().put("authId", req.authId).put("approved", approved).put("method", method).put("reason", reason)
                )
                if (!r.ok) toast("Could not deliver the authorization decision: ${r.error}")
            }
        }
    }

    /** Runs the biometric/PIN prompt (or a plain tap-confirm when the guard is switched off). */
    suspend fun authorizeCurrent(activity: FragmentActivity) {
        val req = _pendingAuth.value ?: return
        if (!_biometricGuard.value) {
            respondAuth(true, "tap", "Approved on phone (biometric guard disabled)")
            return
        }
        val out = biometric.authenticate(activity, "Authorize JAYRA action", req.actionDescription)
        if (out.approved) respondAuth(true, out.method, out.reason)
        else toast(out.reason) // sheet stays open so the user can retry or deny
    }

    // ═════════════════════════ handover / handoff / vibe / cards ═════════════════════════

    private fun applyHandover(session: JSONObject) {
        _handover.value = SessionHandover.fromJson(session)
        if (!AppVisibility.foreground) notifier.postAlert("JAYRA · task available on deck", "Tap to resume on your phone")
    }

    fun resumeHandover() {
        val h = _handover.value ?: return
        _handover.value = null
        h.activeTask?.let {
            lastGoal = it.goal
            _activeTask.value = it
            _stage.value = it.stage
            appendLog("↪ Resumed on deck: ${it.goal} (step ${it.stepNumber}/${it.totalSteps})", it.stage)
        }
        if (_chat.value.none { m -> m.text == h.lastUserQuery }) {
            if (h.lastUserQuery.isNotBlank()) addChat(ChatMessage(role = ChatRole.USER, text = h.lastUserQuery))
            if (h.lastAssistantResponse.isNotBlank()) addChat(ChatMessage(role = ChatRole.ASSISTANT, text = h.lastAssistantResponse))
        }
        emit(UiEvent.Navigate(TAB_TASKS))
    }

    fun dismissHandover() {
        _handover.value = null
    }

    private fun onHandoff(ev: HandoffEvent) {
        _handoff.value = ev
        runCatching { ProximityState.valueOf(ev.state) }.getOrNull()?.let { proximityEngine.forceState(it, ev.proximityScore) }
        setRoutingLocal(ev.newAudioMode, persist = true)
        val lang = _sassyLanguage.value
        val away = ev.state == "AWAY"
        val text = when {
            lang == SassyLanguage.HINGLISH && ev.message.isNotBlank() -> ev.message
            away -> SassyCopy.away(lang)
            else -> SassyCopy.near(lang)
        }
        if (ev.state == "AWAY" || ev.state == "NEAR") {
            emit(UiEvent.Sassy(text, if (away) "handoff_away" else "handoff_near"))
            if (away && !AppVisibility.foreground) notifier.postRemote("JAYRA", text, "high")
        }
    }

    private fun onVibeShift(shift: VibeShift) {
        _vibe.value = VibeUiState(shift.vibe, shift.hudBrightness, shift.dndEnabled, shift.reason)
        val dndOk = device.setDeepDnd(shift.dndEnabled)
        if (shift.dndEnabled && !dndOk) toast("Grant Do Not Disturb access in Settings so Deep Focus can silence notifications")
        val amb = shift.ambient
        val ep = _endpoint.value
        if (amb != null && amb.playing && _routing.value.includesPhone && ep != null && amb.track.startsWith("/")) {
            scope.launch(Dispatchers.Main) { ambient.play("http://${ep.host}:${ep.port}${amb.track}", amb.volume, store.token) }
        } else {
            ambient.stop()
        }
        val text = shift.sassy.pick(_sassyLanguage.value)
        if (text.isNotBlank()) {
            emit(UiEvent.Sassy(text, "vibe"))
            if (!AppVisibility.foreground) notifier.postRemote("JAYRA · ${shift.vibe.label}", text, "default")
        }
    }

    private fun onFlashcard(card: DailyFlashcard) {
        _flashcard.value = card
        addChat(ChatMessage(role = ChatRole.ASSISTANT, text = card.markdownCard, flashcard = card))
        emit(UiEvent.Sassy(card.sassy.pick(_sassyLanguage.value).ifBlank { card.title }, "summary"))
        if (!AppVisibility.foreground) notifier.postRemote(card.title, card.sassy.pick(_sassyLanguage.value), "default")
    }

    fun promoteTodos(todos: List<String>) {
        if (todos.isEmpty()) return
        val task = if (todos.size == 1) todos.first() else
            "Complete these tasks in order: " + todos.mapIndexed { i, t -> "${i + 1}) $t" }.joinToString("; ")
        triggerTask(task)
    }

    // ═════════════════════════ REST-backed feature toggles ═════════════════════════

    private fun enabledFlag(j: JSONObject?): Boolean? {
        if (j == null) return null
        if (j.has("enabled")) return j.optBoolean("enabled")
        return j.optJSONObject("config")?.takeIf { it.has("enabled") }?.optBoolean("enabled")
            ?: j.optJSONObject("status")?.takeIf { it.has("enabled") }?.optBoolean("enabled")
    }

    suspend fun refreshFeatureStatus() {
        _proximityEnabled.value = enabledFlag(api.get("/api/spatial-proximity/status").json)
        _vibeEnabled.value = enabledFlag(api.get("/api/vibe-shifting/status").json)
    }

    suspend fun toggleProximity() {
        val r = api.post("/api/spatial-proximity/toggle")
        if (r.ok) _proximityEnabled.value = enabledFlag(r.json) ?: _proximityEnabled.value?.not() else toast("Toggle failed: ${r.error}")
    }

    suspend fun toggleVibe() {
        val r = api.post("/api/vibe-shifting/toggle")
        if (r.ok) _vibeEnabled.value = enabledFlag(r.json) ?: _vibeEnabled.value?.not() else toast("Toggle failed: ${r.error}")
    }

    suspend fun fetchDailySummary() {
        val r = api.get("/api/knowledge-graph/summary")
        val j = r.json
        val card = j?.optJSONObject("flashcard") ?: j?.takeIf { it.has("cardId") || it.has("markdownCard") }
        if (r.ok && card != null) onFlashcard(DailyFlashcard.fromJson(card)) else toast("No summary available yet")
    }

    suspend fun generateDailySummary() {
        val r = api.post("/api/knowledge-graph/generate")
        if (!r.ok) return toast("Could not generate: ${r.error}")
        val card = r.json?.optJSONObject("flashcard") ?: r.json?.takeIf { it.has("markdownCard") }
        if (card != null) onFlashcard(DailyFlashcard.fromJson(card)) else fetchDailySummary()
    }

    // ═════════════════════════ files ═════════════════════════

    fun sendFileChat(uri: Uri, displayName: String?) {
        if (!requireConnected()) return
        scope.launch {
            files.uploadViaBridge(uri)
                .onSuccess { addChat(ChatMessage(role = ChatRole.USER, text = "Sent file to PC", attachmentName = displayName ?: it)) }
                .onFailure { toast("Transfer failed: ${it.message}") }
        }
    }

    fun sendFileRest(uri: Uri) {
        if (!requireConnected()) return
        scope.launch {
            files.uploadViaRest(uri).onSuccess { toast("Sent $it to PC") }.onFailure { toast("Upload failed: ${it.message}") }
        }
    }

    fun downloadFile(name: String) {
        scope.launch {
            files.downloadFromHost(name).onSuccess { toast("Saved ${it.name}") }.onFailure { toast("Download failed: ${it.message}") }
        }
    }

    // ═════════════════════════ host → phone commands ═════════════════════════

    private suspend fun runCommand(channel: ChannelKind, id: String, cmd: JSONObject) {
        val action = cmd.optString("action").uppercase()
        val result = try {
            execute(id, action, cmd)
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", e.message ?: e.javaClass.simpleName)
        }
        val ok = result.optBoolean("success", true)
        if (action == "FILE_TRANSFER_CHUNK" && ok) return // no per-chunk acks
        client.send(
            channel,
            JSONObject().put("type", "command_result").put("id", id).put("action", action).put("success", ok).put("result", result)
        )
    }

    private suspend fun execute(id: String, action: String, cmd: JSONObject): JSONObject = when (action) {
        "LAUNCH_APP" -> withContext(Dispatchers.Main) {
            apps.launch(cmd.optStringOrNull("packageName"), cmd.optStringOrNull("appName"), cmd.optStringOrNull("uri"))
        }
        "LIST_INSTALLED_APPS" -> apps.listInstalled()
        "TRIGGER_NOTIFICATION" -> {
            notifier.postRemote(cmd.optString("title", "JAYRA"), cmd.optString("body"), cmd.optStringOrNull("priority"))
            JSONObject().put("success", true)
        }
        "TRIGGER_RINGTONE_FINDER" -> withContext(Dispatchers.Main) { device.startFinder(cmd.optInt("durationSec", 30)) }
        "QUERY_PHONE_STATUS" -> device.queryStatus()
        "MEDIA_CONTROL" -> device.mediaControl(cmd.optString("operation", "toggle"))
        "ACCESSIBILITY_ACTION" -> {
            val svc = JayraAccessibilityService.instance
            if (svc == null) accessibilityMissing() else withContext(Dispatchers.Main) { svc.perform(cmd) }
        }
        "INSPECT_SCREEN_HIERARCHY" -> {
            val svc = JayraAccessibilityService.instance
            if (svc == null) accessibilityMissing() else withContext(Dispatchers.Main) { svc.inspect(cmd.optInt("maxDepth", 8)) }
        }
        "FILE_TRANSFER_START" -> files.onHostStart(cmd)
        "FILE_TRANSFER_CHUNK" -> files.onHostChunk(cmd)
        "FILE_TRANSFER_COMPLETE" -> files.onHostComplete(cmd)
        "SET_AUDIO_ROUTING" -> {
            setRoutingLocal(AudioRoutingMode.parse(cmd.optString("mode")), persist = true)
            JSONObject().put("success", true).put("mode", _routing.value.name)
        }
        "CLIPBOARD_SYNC" -> {
            applyRemoteClipboard(cmd.optString("text"))
            JSONObject().put("success", _clipboardSync.value).apply { if (!_clipboardSync.value) put("error", "Clipboard sync is off on the phone") }
        }
        "SESSION_HANDOVER" -> {
            cmd.optJSONObject("session")?.let { applyHandover(it) }
            JSONObject().put("success", true)
        }
        "REQUEST_AUTHORIZATION" -> {
            onAuthRequest(id, cmd)
            JSONObject().put("success", true).put("queued", true)
        }
        else -> JSONObject().put("success", false).put("error", "Unknown action '$action'")
    }

    private fun accessibilityMissing() = JSONObject().put("success", false)
        .put("error", "Accessibility service is not enabled on the phone")
        .put("hint", "Settings → Accessibility → JAYRA Companion Control")

    companion object {
        const val TAB_TASKS = 2
        private const val MAX_LOG_LINES = 400
        private const val MAX_CHAT = 300
    }
}

/** Fallback copy for proximity hand-offs when the host did not supply a localized message. */
object SassyCopy {
    fun away(lang: SassyLanguage) = when (lang) {
        SassyLanguage.URDU -> "دیکھا آپ مجھ سے دور گئے تو میں فون پر آ گئی... میرے بغیر ایک منٹ بھی نہیں رہ سکتے نا؟"
        SassyLanguage.HINGLISH -> "Dekha aap mujhse door gaye toh main phone pe aa gayi... mere bina ek min bhi nahi reh sakte na?"
        SassyLanguage.ENGLISH -> "You walked away so I followed you to your phone... can't last a minute without me, can you?"
    }

    fun near(lang: SassyLanguage) = when (lang) {
        SassyLanguage.URDU -> "واپس آ گئے؟ ڈیسک ٹاپ اسکرین آپ کا انتظار کر رہی تھی، اب فون کو آرام دو!"
        SassyLanguage.HINGLISH -> "Wapas aa gaye? Desktop screen aapka intezaar kar rahi thi, ab phone ko aaram do!"
        SassyLanguage.ENGLISH -> "Back already? Your desktop screen was waiting for you — let the phone rest!"
    }
}
