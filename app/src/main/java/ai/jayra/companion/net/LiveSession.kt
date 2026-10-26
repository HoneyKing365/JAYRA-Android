package ai.jayra.companion.net

import ai.jayra.companion.audio.MicStreamer
import ai.jayra.companion.audio.PcmPlayer
import ai.jayra.companion.data.PairingStore
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Remote-deck link to the host's /live gateway (spec 3.1-3.3): typed text, push-to-talk voice,
 * ReAct status, transcripts, quick PC actions and the emergency stop.
 * This is the mirroring channel - phone-control commands never travel over it.
 */
class LiveSession(
    private val scope: CoroutineScope,
    http: OkHttpClient,
    private val pairingStore: PairingStore,
    private val log: (String) -> Unit,
) {
    data class Line(val role: String, val text: String)

    private val player = PcmPlayer(scope)
    private val mic = MicStreamer(scope) { sendAudio(it) }

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()
    val react = MutableStateFlow<String?>(null)
    val activeClients = MutableStateFlow<Int?>(null)
    val talking = MutableStateFlow(false)
    val speaking = MutableStateFlow(false)
    private var speakingJob: Job? = null

    @Volatile private var pendingStopAt = 0L
    @Volatile private var lastAudioAt = 0L

    private val socket = ReconnectingSocket(
        http, scope, { request() },
        object : ReconnectingSocket.Listener {
            override fun onOpen() {
                log("Remote deck connected")
                val queued = pendingStopAt
                pendingStopAt = 0L
                if (queued != 0L && SystemClock.elapsedRealtime() - queued < 10_000) sendEmergencyStop()
            }

            override fun onText(text: String) = handle(text)

            override fun onClosed() {
                if (talking.value) stopTalking()
                activeClients.value = null
            }
        },
    )

    val state: StateFlow<LinkState> get() = socket.state
    val isWanted: Boolean get() = socket.isWanted

    fun start() = socket.start()
    fun nudge() = socket.nudge()

    fun stop() {
        socket.stop()
        mic.stop()
        talking.value = false
        player.release()
        activeClients.value = null
    }

    fun sendText(text: String): Boolean {
        val t = text.trim().take(4_000)
        if (t.isEmpty()) return false
        val ok = socket.send(JSONObject().put("type", "text").put("text", t).toString())
        if (ok) _lines.update { (it + Line("you", t)).takeLast(60) }
        return ok
    }

    /** Low-latency PC actions (e.g. volumeUp, muteToggle, setVolume). */
    fun sendRemoteAction(action: String, args: JSONObject = JSONObject()): Boolean =
        socket.send(
            JSONObject().put("type", "remote_action").put("action", action).put("args", args).toString(),
        )

    /**
     * KILL-SWITCH. If the link is down it connects and sends as soon as it opens (within 10 s).
     * Returns true only if the stop was actually written to an open socket right now.
     */
    fun emergencyStop(): Boolean {
        if (sendEmergencyStop()) return true
        pendingStopAt = SystemClock.elapsedRealtime()
        if (!socket.isWanted) socket.start() else socket.nudge()
        log("Live link down - emergency stop queued")
        return false
    }

    private fun sendEmergencyStop(): Boolean {
        val ok = socket.send(JSONObject().put("type", "remote_action").put("action", "emergencyStop").toString())
        if (ok) log("Emergency stop sent to laptop")
        return ok
    }

    /** Push-to-talk. Caller must already hold RECORD_AUDIO. */
    fun startTalking(): Boolean {
        if (socket.state.value !is LinkState.Open) return false
        if (SystemClock.elapsedRealtime() - lastAudioAt < 1_500) {   // barge-in while JAYRA is speaking
            socket.send(JSONObject().put("type", "interrupt").toString())
            player.flush()
        }
        if (!mic.start()) return false
        talking.value = true
        socket.send(conversationEvent("user_started_speaking"))
        return true
    }

    fun stopTalking() {
        if (!talking.value) return
        mic.stop()
        talking.value = false
        socket.send(conversationEvent("user_stopped_speaking"))
    }

    private fun conversationEvent(event: String) =
        JSONObject().put("type", "conversationEvent").put("event", event).toString()

    private fun sendAudio(chunk: ByteArray) {
        if (socket.queueSize() > 256 * 1024) return   // drop instead of building latency
        val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
        val media = JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)
        val msg = JSONObject().put("realtimeInput", JSONObject().put("mediaChunks", JSONArray().put(media)))
        socket.send(msg.toString())
    }

    private fun request(): Request? {
        val p = pairingStore.pairing.value ?: return null
        return Request.Builder()
            .url(p.wsUrl("/live"))
            .header("Origin", p.origin)
            .apply { p.gatewayToken?.let { header("X-JAYRA-Token", it) } }
            .build()
    }

    private fun handle(text: String) {
        if (text.length > 1_000_000) return
        val o = try { JSONObject(text) } catch (e: JSONException) { return }
        when (o.optString("type")) {
            "master_sync" -> activeClients.value = o.optInt("activeClients", -1).takeIf { it >= 0 }
            "reactStatus" -> react.value = describeReact(o)
            "audio" -> {
                val data = o.optString("audio")
                if (data.isNotEmpty()) {
                    val pcm = try { Base64.decode(data, Base64.DEFAULT) } catch (e: IllegalArgumentException) { null }
                    if (pcm != null) {
                        lastAudioAt = SystemClock.elapsedRealtime()
                        player.enqueue(pcm)
                        speaking.value = true
                        speakingJob?.cancel()
                        speakingJob = scope.launch {
                            delay(1_200)
                            speaking.value = false
                        }
                    }
                }
            }
            "transcription" -> {
                val t = o.optString("text").trim().take(1_000)
                if (t.isNotEmpty()) {
                    val role = if (o.optString("role") == "user") "you" else "jayra"
                    _lines.update { (it + Line(role, t)).takeLast(60) }
                }
            }
            "interrupted" -> {
                player.flush()
                speaking.value = false
            }
            "turnComplete" -> react.value = null
            "remote_action_result" -> {
                val action = o.optString("action").take(40)
                val status = o.optJSONObject("result")?.optString("status")?.take(40) ?: "done"
                log("PC action $action: $status")
            }
        }
    }

    private fun describeReact(o: JSONObject): String {
        val stage = o.optString("stage").take(20)
        val detail = when (stage) {
            "Thought" -> o.optString("plan").ifEmpty { o.optString("status") }
            "Action" -> "${o.optString("action")} ${o.optString("target")}".trim()
            "Observation" -> o.optString("details").ifEmpty { o.optString("status") }
            else -> ""
        }
        val max = o.optInt("maxIterations", 0)
        val step = if (max > 0) " ${o.optInt("iteration")}/$max" else ""
        return "[$stage$step] $detail".take(300)
    }
}
